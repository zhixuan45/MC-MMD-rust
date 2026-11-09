package com.shiroha.mmdskin.asset.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** The inputs which give baked bone-parent compensation its meaning. No native handles or absolute paths are saved. */
public final class PropTimelineCalibration {
    private static final int OUTSIDE_PARENT_VERSION=2;
    private PropTimelineCalibration() { }

    public record Signature(String actor, String prop, String motion, String reference,
                            String bone, List<Float> transform) {
        public Signature {
            for(String hash:List.of(actor,prop,motion)) if(!hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid calibration asset hash");
            }
            if(reference==null || (!reference.isEmpty() && !reference.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid calibration reference hash");
            }
            if(bone==null) throw new IllegalArgumentException("Missing calibration attachment");
            transform=List.copyOf(transform);
            if(transform.size()!=10 || transform.stream().anyMatch(v -> !Float.isFinite(v))) {
                throw new IllegalArgumentException("Invalid calibration transform");
            }
        }
    }

    public static Signature capture(String modelName, ModelConfigData config, Path motion, Path reference) throws IOException {
        var actor=ModelCatalogEntry.resolveModelFile(modelName,config.mainPmxFile);
        var prop=HeldPmxAssets.resolve(modelName,config.heldPmxFile);
        if(actor==null || prop==null) throw new IOException("Calibration PMX is missing");
        return capture(Path.of(actor.getModelFilePath()),Path.of(prop.getModelFilePath()),motion,reference,config,
                com.shiroha.mmdskin.config.ModelConfigManager.getConfig(prop.getFolderName()).modelScale);
    }

    public static Signature capture(Path actor,Path prop,Path motion,Path reference,ModelConfigData config) throws IOException {
        return capture(actor,prop,motion,reference,config,config.modelScale);
    }

    private static Signature capture(Path actor,Path prop,Path motion,Path reference,ModelConfigData config,float propModelScale) throws IOException {
        return new Signature(hash(actor),hash(prop),hash(motion),reference==null?"":hash(reference),
                config.heldPmxBone,List.of(config.modelScale,config.heldItemScale,config.heldPmxScale,
                config.heldPmxOffsetX,config.heldPmxOffsetY,config.heldPmxOffsetZ,
                config.heldPmxRotX,config.heldPmxRotY,config.heldPmxRotZ,propModelScale));
    }

    public static String hash(Path path) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var stream=Files.newInputStream(path)) {
                byte[] buffer=new byte[65536];
                for(int count;(count=stream.read(buffer))!=-1;) digest.update(buffer,0,count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Signature read(PropTimelineFiles.Document document) {
        if(document.originalJson()==null) return null;
        var root=com.google.gson.JsonParser.parseString(document.originalJson()).getAsJsonObject();
        if(!root.has("calibration")) return null;
        // Deserialize through the constructor to apply the same validation to files and new captures.
        try {
            var value=root.getAsJsonObject("calibration");
            if(!value.has("outsideParentVersion") || value.get("outsideParentVersion").getAsInt()!=OUTSIDE_PARENT_VERSION) return null;
            var floats=new java.util.ArrayList<Float>();
            for(var number:value.getAsJsonArray("transform")) floats.add(number.getAsFloat());
            return new Signature(value.get("actor").getAsString(),value.get("prop").getAsString(),
                    value.get("motion").getAsString(),value.get("reference").getAsString(),
                    value.get("bone").getAsString(),floats);
        } catch(RuntimeException invalid) { return null; }
    }

    public static JsonObject json(Signature signature) {
        var value=new Gson().toJsonTree(signature).getAsJsonObject();
        value.addProperty("outsideParentVersion",OUTSIDE_PARENT_VERSION);
        return value;
    }

    /** Empty reference is an explicit rest-pose registration, reusable across actor motions. */
    public static String mismatch(Signature saved,Signature current,List<Path> actorMotions) throws IOException {
        return mismatch(saved,current,actorMotions,null);
    }

    /** Default hand settings do not participate in an independent stage prop's reference frame. */
    public static boolean matches(Signature saved,Signature current,PropTimeline timeline) {
        return sourceMismatch(saved,current,timeline)==null
                && (!requiresActorReference(timeline) || saved.reference().equals(current.reference()));
    }

    public static String mismatch(Signature saved,Signature current,List<Path> actorMotions,PropTimeline timeline) throws IOException {
        String mismatch=sourceMismatch(saved,current,timeline);
        if(mismatch!=null) return mismatch;
        if(requiresActorReference(timeline) && !saved.reference().isEmpty()
                && (actorMotions.size()!=1 || !saved.reference().equals(hash(actorMotions.get(0))))) {
            return "Character motion differs from the registration reference";
        }
        return null;
    }

    private static String sourceMismatch(Signature saved,Signature current,PropTimeline timeline) {
        if(saved==null) return "Bone compensation has no calibration source; recalculate and save it in the editor";
        if(current==null) return "Current character calibration is unavailable";
        if(!saved.actor().equals(current.actor())) return "Character PMX changed";
        if(!saved.prop().equals(current.prop())) return "Prop PMX changed";
        if(!saved.motion().equals(current.motion())) return "Prop VMD changed";
        boolean explicitGrip=timeline==null || timeline.initial().attachment()!=null || !timeline.attachments().isEmpty();
        if(timeline==null && !saved.bone().equals(current.bone())) return "Attachment configuration changed";
        for(int i=0;i<saved.transform().size();i++) {
            if((explicitGrip || i<3 || i==9) && !saved.transform().get(i).equals(current.transform().get(i))) {
                return "Attachment configuration changed";
            }
        }
        return null;
    }

    private static boolean requiresActorReference(PropTimeline timeline) {
        // A placed grip C=E*inverse(A0), or a snap C=I, does not depend on the actor's reference VMD.
        return timeline==null || timeline.boneKeys().stream().map(PropTimeline.Key::value)
                .anyMatch(binding -> !binding.unbind() && binding.keepWorld() && binding.placement()==null);
    }
}
