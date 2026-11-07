package com.shiroha.mmdskin.asset.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Attachment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Key;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.Offset;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.State;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.BoneBinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads the optional sidecar next to the VMD actually selected by HeldPmxMotionFiles. */
public final class PropTimelineFiles {
    private static final long MAX_BYTES = 1_048_576;
    private static final int MAX_KEYS = 10_000;

    private PropTimelineFiles() { }

    /** Retains the original text for conflict detection and unknown object metadata on save. */
    public record Document(Path motion, String pmxFile, String originalJson, PropTimeline timeline) { }

    public static Path sidecar(Path motion) {
        String name = motion.getFileName().toString();
        return motion.resolveSibling(name.substring(0, name.length() - 4) + ".timeline.json");
    }

    public static PropTimeline load(Path motion, String pmxFile) throws IOException {
        return open(motion,pmxFile).timeline();
    }

    public static Document open(Path motion, String pmxFile) throws IOException {
        Path path = sidecar(motion);
        String json = read(path);
        if (json == null) return new Document(motion,pmxFile,null,PropTimeline.EMPTY);
        try {
            return new Document(motion,pmxFile,json,parse(json,motion.getFileName().toString(),pmxFile));
        } catch (RuntimeException failure) {
            throw new IOException("Invalid prop timeline " + path + ": " + failure.getMessage(), failure);
        }
    }

    private static String read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) {
            throw new IOException("Timeline must be a regular file of at most " + MAX_BYTES + " bytes: " + path);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Write beside the selected VMD, replacing the destination only after a complete valid file exists. */
    public static Document save(Document document, PropTimeline timeline) throws IOException {
        return save(document,timeline,null);
    }

    public static String referenceMotion(Document document) {
        if (document.originalJson()==null) return "";
        var root=JsonParser.parseString(document.originalJson()).getAsJsonObject();
        return root.has("referenceMotion")?string(root.get("referenceMotion")):"";
    }

    public static Document save(Document document, PropTimeline timeline,String referenceMotion) throws IOException {
        return save(document,timeline,referenceMotion,null);
    }

    public static Document save(Document document, PropTimeline timeline,String referenceMotion,
                                PropTimelineCalibration.Signature calibration) throws IOException {
        Path path = sidecar(document.motion()).toAbsolutePath().normalize();
        if (!Objects.equals(document.originalJson(),read(path))) {
            throw new IOException("Timeline changed on disk; reopen the editor before saving: " + path);
        }
        JsonObject root = document.originalJson() == null ? new JsonObject()
                : JsonParser.parseString(document.originalJson()).getAsJsonObject();
        root.addProperty("version",timeline.boneKeys().isEmpty()?1:2);
        if (referenceMotion!=null) root.addProperty("referenceMotion",referenceMotion);
        if(timeline.boneKeys().isEmpty()) root.remove("calibration");
        else if(calibration!=null) root.add("calibration",PropTimelineCalibration.json(calibration));
        root.addProperty("motion",document.motion().getFileName().toString());
        JsonObject object;
        if (root.has("objects")) object = root.getAsJsonArray("objects").get(0).getAsJsonObject();
        else {
            object = new JsonObject();
            object.addProperty("id","prop");
            JsonArray objects = new JsonArray();
            objects.add(object);
            root.add("objects",objects);
        }
        object.addProperty("asset",document.pmxFile());
        JsonObject initial = object.has("initial") ? object.getAsJsonObject("initial") : new JsonObject();
        initial.addProperty("visible",timeline.initial().visible());
        if (timeline.initial().attachment() == null) initial.remove("attachment");
        else initial.add("attachment",attachmentJson(timeline.initial().attachment()));
        object.add("initial",initial);
        JsonArray visibility = new JsonArray(), attachments = new JsonArray();
        for (Key<Boolean> key : timeline.visibility()) {
            JsonObject value = new JsonObject();
            value.addProperty("frame",key.frame());
            value.addProperty("value",key.value());
            visibility.add(value);
        }
        for (Key<Attachment> key : timeline.attachments()) {
            JsonObject value = attachmentJson(key.value());
            value.addProperty("frame",key.frame());
            attachments.add(value);
        }
        JsonObject tracks = new JsonObject();
        tracks.add("visibility",visibility);
        tracks.add("attachment",attachments);
        if (!timeline.boneKeys().isEmpty()) {
            JsonArray bones = new JsonArray();
            for (Key<BoneBinding> key : timeline.boneKeys()) {
                BoneBinding binding = key.value();
                JsonObject value = binding.unbind()?new JsonObject():attachmentJson(new Attachment(binding.bone(),binding.offset()));
                value.addProperty("frame",key.frame());
                value.addProperty("childBone",binding.childBone());
                value.addProperty("unbind",binding.unbind());
                value.addProperty("mode",binding.keepWorld()?"keepWorld":"snap");
                JsonArray compensation = new JsonArray();
                binding.compensation().forEach(compensation::add);
                value.add("compensation",compensation);
                bones.add(value);
            }
            tracks.add("boneAttachment",bones);
        }
        object.add("tracks",tracks);
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Timeline is too large");
        parse(json,document.motion().getFileName().toString(),document.pmxFile());
        Path temporary = Files.createTempFile(path.getParent(),".timeline-",".tmp");
        try {
            Files.writeString(temporary,json,StandardCharsets.UTF_8);
            // Recheck after preparing the replacement so an external edit is never knowingly overwritten.
            if (!Objects.equals(document.originalJson(),read(path))) {
                throw new IOException("Timeline changed on disk; reopen the editor before saving: " + path);
            }
            try {
                Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Document(document.motion(),document.pmxFile(),json,timeline);
    }

    private static JsonObject attachmentJson(Attachment attachment) {
        JsonObject value = new JsonObject(), parent = new JsonObject();
        parent.addProperty("object","actor");
        parent.addProperty("bone",attachment.bone());
        value.add("parent",parent);
        value.addProperty("mode","snap");
        Offset offset = attachment.offset();
        JsonArray translation = new JsonArray(), rotation = new JsonArray();
        for (float number : new float[]{offset.x(),offset.y(),offset.z()}) translation.add(number);
        for (float number : new float[]{offset.rotationX(),offset.rotationY(),offset.rotationZ()}) rotation.add(number);
        value.add("offset",translation);
        value.add("rotation",rotation);
        return value;
    }

    static PropTimeline parse(String json, String motionFile, String pmxFile) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int version = integer(root.get("version"));
        if (version!=1 && version!=2) throw new IllegalArgumentException("Expected timeline version 1 or 2");
        if (root.has("motion") && !motionFile.equals(string(root.get("motion")))) {
            throw new IllegalArgumentException("Timeline motion does not match " + motionFile);
        }
        JsonArray objects = root.getAsJsonArray("objects");
        if (objects == null || objects.size() != 1) {
            throw new IllegalArgumentException("Version 1 requires exactly one independent prop");
        }
        JsonObject object = objects.get(0).getAsJsonObject();
        if (string(object.get("id")).isBlank() || !pmxFile.equals(string(object.get("asset")))) {
            throw new IllegalArgumentException("Timeline prop asset does not match " + pmxFile);
        }
        State initial = State.DEFAULT;
        if (object.has("initial")) {
            JsonObject value = object.getAsJsonObject("initial");
            initial = new State(!value.has("visible") || bool(value.get("visible")),
                    value.has("attachment") ? attachment(value.getAsJsonObject("attachment")) : null);
        }
        List<Key<Boolean>> visibility = new ArrayList<>();
        List<Key<Attachment>> attachments = new ArrayList<>();
        List<Key<BoneBinding>> boneKeys = new ArrayList<>();
        if (object.has("tracks")) {
            JsonObject tracks = object.getAsJsonObject("tracks");
            for (String name : tracks.keySet()) {
                if (!name.equals("visibility") && !name.equals("attachment")
                        && !(version==2 && name.equals("boneAttachment"))) {
                    throw new IllegalArgumentException("Unsupported prop track: " + name);
                }
            }
            if (tracks.has("boneAttachment")) {
                for (JsonElement element : keys(tracks.getAsJsonArray("boneAttachment"))) {
                    JsonObject key=element.getAsJsonObject();
                    String child=string(key.get("childBone"));
                    boolean unbind=key.has("unbind") && bool(key.get("unbind"));
                    if (unbind) {
                        boneKeys.add(new Key<>(integer(key.get("frame")),BoneBinding.detached(child)));
                        continue;
                    }
                    String mode=key.has("mode")?string(key.get("mode")):"keepWorld";
                    if (!mode.equals("keepWorld") && !mode.equals("snap")) throw new IllegalArgumentException("Invalid bone parent mode");
                    JsonObject parent=key.getAsJsonObject("parent");
                    if (!string(parent.get("object")).equals("actor")) throw new IllegalArgumentException("Bone parent must be actor");
                    var compensation=new ArrayList<Float>();
                    for (JsonElement number : key.getAsJsonArray("compensation")) {
                        if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid compensation");
                        compensation.add(number.getAsFloat());
                    }
                    float[] offset=vector(key,"offset"), rotation=vector(key,"rotation");
                    BoneBinding binding=new BoneBinding(child,string(parent.get("bone")),
                            new Offset(offset[0],offset[1],offset[2],rotation[0],rotation[1],rotation[2]),
                            compensation,mode.equals("keepWorld"),false);
                    boneKeys.add(new Key<>(integer(key.get("frame")),binding));
                }
            }
            if (tracks.has("visibility")) {
                for (JsonElement element : keys(tracks.getAsJsonArray("visibility"))) {
                    JsonObject key = element.getAsJsonObject();
                    visibility.add(new Key<>(integer(key.get("frame")), bool(key.get("value"))));
                }
            }
            if (tracks.has("attachment")) {
                for (JsonElement element : keys(tracks.getAsJsonArray("attachment"))) {
                    JsonObject key = element.getAsJsonObject();
                    attachments.add(new Key<>(integer(key.get("frame")), attachment(key)));
                }
            }
        }
        return new PropTimeline(initial, visibility, attachments,boneKeys);
    }

    private static JsonArray keys(JsonArray keys) {
        if (keys == null || keys.size() > MAX_KEYS) throw new IllegalArgumentException("Too many timeline keys");
        return keys;
    }

    private static Attachment attachment(JsonObject value) {
        if (value.has("mode") && !string(value.get("mode")).equals("snap")) {
            throw new IllegalArgumentException("Version 1 supports only snap attachment mode");
        }
        if (value.has("childBone") && !string(value.get("childBone")).isEmpty()) {
            throw new IllegalArgumentException("Version 1 attaches the entire prop, not a child bone");
        }
        JsonObject parent = value.getAsJsonObject("parent");
        if (parent == null || !string(parent.get("object")).equals("actor")) {
            throw new IllegalArgumentException("Version 1 attachment parent must be actor");
        }
        float[] offset = vector(value, "offset");
        float[] rotation = vector(value, "rotation");
        return new Attachment(string(parent.get("bone")),
                new Offset(offset[0], offset[1], offset[2], rotation[0], rotation[1], rotation[2]));
    }

    private static float[] vector(JsonObject value, String name) {
        float[] result = new float[3];
        if (!value.has(name)) return result;
        JsonArray array = value.getAsJsonArray(name);
        if (array.size() != 3) throw new IllegalArgumentException(name + " must contain three numbers");
        for (int i = 0; i < 3; i++) {
            JsonElement number = array.get(i);
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(name + " must contain numbers");
            }
            result[i] = number.getAsFloat();
        }
        return result;
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected an integer");
        }
        return value.getAsBigDecimal().intValueExact();
    }

    private static boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Expected a boolean");
        }
        return value.getAsBoolean();
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string");
        }
        return value.getAsString();
    }
}
