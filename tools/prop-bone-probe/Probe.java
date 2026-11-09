import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort;
import com.shiroha.mmdskin.bridge.runtime.NativeModelQueryPort;
import com.shiroha.mmdskin.player.render.PropBoneParenting;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.*;
import org.joml.Matrix4f;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Real native calls; only library discovery is replaced with an explicit DLL path. */
public final class Probe {
    private static final NativeFunc API = new NativeFunc();
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void near(Matrix4f expected, Matrix4f actual, String message) {
        check(expected.equals(actual, .0003f), message + "\nexpected " + expected + "\nactual " + actual);
    }
    private static void localVmdNear(Matrix4f expected, Matrix4f actual, String message) {
        float[] left=expected.get(new float[16]),right=actual.get(new float[16]);
        for(int i=0;i<16;i++) if(Math.abs(left[i]-right[i])>=.0003f) {
            System.out.printf("VMD ERROR index=%d expected=%.9f actual=%.9f delta=%.9f%n",
                    i,left[i],right[i],right[i]-left[i]);
            check(false,message+"\nexpected "+expected+"\nactual "+actual);
        }
    }
    private static void seek(long model, float frame, boolean cpu) {
        API.SeekLayer(model, 0, frame);
        if (cpu) API.UpdateModel(model, 0);
        else API.UpdateAnimationOnly(model, 0);
    }
    private static void placedRegistration(long actor,long prop,boolean cpu,Matrix4f outer,PropBoneParenting parenting) {
        parenting.clear(prop);
        seek(prop,30,cpu);
        Matrix4f target=PropBoneParenting.targetInProp(outer,parenting.pose(actor,"Tip")[0]);
        Matrix4f[] pose=parenting.pose(prop,"Center");
        Matrix4f animationAtRegistration=PropBoneParenting.animationLocal(pose[1],pose[2]);
        var aligned=PropBoneParenting.registration("Center","Tip",target,pose,Offset.IDENTITY,true,Offset.IDENTITY);
        parenting.apply(actor,prop,outer,new State(true,null,java.util.Map.of("Center",aligned)));
        API.EvaluatePropPose(prop);
        near(target,parenting.pose(prop,"Center")[0],"Placement aligns the actual animated bone, including orientation");
        var adjustment=new Offset(.6f,-.4f,1.2f,25,-35,80);
        var draft=PropBoneParenting.registration("Center","Tip",target,parenting.pose(prop,"Center"),
                Offset.IDENTITY,true,adjustment);
        parenting.apply(actor,prop,outer,new State(true,null,java.util.Map.of("Center",draft)));
        API.EvaluatePropPose(prop);
        Matrix4f preview=parenting.pose(prop,"Center")[0];
        Matrix4f desired=new Matrix4f(target).translate(.6f,-.4f,1.2f)
                .rotateXYZ((float)Math.toRadians(25),(float)Math.toRadians(-35),(float)Math.toRadians(80));
        near(desired,preview,"Placement fine adjustment pivots around selected bone and is applied exactly once");
        var timeline=new PropTimeline(State.DEFAULT,java.util.List.of(),java.util.List.of(),
                java.util.List.of(new Key<>(30,draft)));
        // Force a fresh native evaluation so pose reuse cannot hide a discontinuity.
        parenting.clear(prop);
        parenting.apply(actor,prop,outer,timeline.evaluate(30));
        seek(prop,30,cpu);
        Matrix4f registered=parenting.pose(prop,"Center")[0];
        near(preview,registered,"Placement confirmation must not jump");
        float[] before=preview.get(new float[16]),after=registered.get(new float[16]);
        float matrixError=0;
        for(int i=0;i<16;i++) matrixError=Math.max(matrixError,Math.abs(before[i]-after[i]));
        float positionError=preview.getTranslation(new org.joml.Vector3f())
                .distance(registered.getTranslation(new org.joml.Vector3f()));
        System.out.printf("Placement %s continuity: positionError=%.9f, matrixMaxError=%.9f%n",
                cpu?"CPU":"GPU pose",positionError,matrixError);
        check(draft.equals(PropBoneParenting.registration("Center","Tip",target,parenting.pose(prop,"Center"),
                Offset.IDENTITY,true,draft.placement())),"Recalculation must preserve the same grip and compensation");
        for(int frame:new int[]{45,60,30}) {
            parenting.apply(actor,prop,outer,timeline.evaluate(frame));
            seek(prop,frame,cpu);
            Matrix4f[] current=parenting.pose(prop,"Center");
            Matrix4f authored=frame==60?new Matrix4f().translation(4,1,2).rotateZ((float)Math.PI/2)
                    :PropBoneParenting.animationLocal(current[1],current[2]);
            Matrix4f expected=new Matrix4f(desired).mul(new Matrix4f(animationAtRegistration).invert()).mul(authored);
            near(expected,current[0],"Placed VMD continues at frame "+frame);
            if(frame>30) check(!current[0].equals(preview,.01f),"Placement must not freeze the VMD pose");
            else near(preview,current[0],"Placement rewind must return to registration pose without drift");
        }
        parenting.clear(prop);
    }
    private static void windowedTimeline(long actor,long prop,boolean cpu,Matrix4f outer,PropBoneParenting parenting) {
        API.SetPropBoneWindows(prop,new int[]{1},new float[]{0,60});
        seek(prop,30,cpu);
        localVmdNear(new Matrix4f().translation(0,1,0).translate(2,0,-1).rotateY(-.4f),parenting.pose(prop,"Center")[1],
                "Direct JNI window holds the initial VMD pose");
        var keys=new java.util.ArrayList<Key<BoneBinding>>();
        var root=new BoneBinding("Root","Center",Offset.IDENTITY,PropTimeline.identityCompensation(),false,false);
        var center=new BoneBinding("Center","Tip",Offset.IDENTITY,PropTimeline.identityCompensation(),false,false);
        keys.add(new Key<>(0,root));
        keys.add(new Key<>(0,center));
        keys.add(new Key<>(30,new BoneBinding("Root","Tip",Offset.IDENTITY,PropTimeline.identityCompensation(),false,false)));
        keys.add(new Key<>(30,new BoneBinding("Center","Root",Offset.IDENTITY,PropTimeline.identityCompensation(),false,false)));
        keys.add(new Key<>(45,root));
        keys.add(new Key<>(45,center));
        keys.add(new Key<>(70,BoneBinding.detached("Root")));
        keys.add(new Key<>(75,BoneBinding.detached("Center")));
        var timeline=new PropTimeline(State.DEFAULT,java.util.List.of(),java.util.List.of(),keys);
        for(int frame:new int[]{15,30,45,60,70,75,15}) {
            seek(prop,frame,cpu);
            var state=timeline.evaluate(frame);
            parenting.apply(actor,prop,outer,timeline,frame,state);
            var pose=parenting.pose(prop,"Center");
            if(frame==75) {
                near(new Matrix4f().translation(0,1,0),pose[1],
                        "Unbind after the final authored key resets local VMD pose");
                near(new Matrix4f(parenting.pose(prop,"Root")[0]).mul(pose[1]),pose[0],
                        "Unbound center returns to the PMX parent hierarchy");
            } else if(frame==15) {
                localVmdNear(new Matrix4f().translation(0,1,0).translate(2,0,-1).rotateY(-.4f),pose[1],
                        "Future outside-parent switches hold the initial VMD pose at frame "+frame);
            } else {
                localVmdNear(new Matrix4f().translation(0,1,0).translate(4,1,2).rotateZ((float)Math.PI/2),pose[1],
                        "Switch selects or holds the later VMD key at frame "+frame);
            }
        }
        var onlyInitial=new PropTimeline(State.DEFAULT,java.util.List.of(),java.util.List.of(),
                java.util.List.of(new Key<>(0,root),new Key<>(0,center)));
        var state=onlyInitial.evaluate(15);
        seek(prop,15,cpu);
        parenting.apply(actor,prop,outer,onlyInitial,15,state);
        var local=parenting.pose(prop,"Center")[1];
        check(local.m30()>2 && local.m30()<4 && Math.abs(local.m31())>.1,
                "Only frame-zero registration retains ordinary VMD interpolation: "+local);
        parenting.clear(prop);
        seek(prop,30,cpu);
        System.out.println("PASS real JNI window integration "+(cpu?"CPU":"GPU pose")
                +": switches across VMD keys, unbound reset, reverse seek, frame-zero-only interpolation");
    }
    public static void main(String[] args) {
        System.load(args[0]);
        Path fixture = Path.of(args[1]);
        NativeMatrixPort matrices = (NativeMatrixPort) Proxy.newProxyInstance(Probe.class.getClassLoader(),
                new Class<?>[]{NativeMatrixPort.class}, (p, method, a) -> switch (method.getName()) {
                    case "getPropBonePose" -> API.GetPropBonePose((long)a[0], (int)a[1], (java.nio.ByteBuffer)a[2]);
                    case "setPropBoneParents" -> API.SetPropBoneParents((long)a[0], (int[])a[1], (float[])a[2]);
                    case "setPropBoneWindows" -> API.SetPropBoneWindows((long)a[0], (int[])a[1], (float[])a[2]);
                    case "evaluatePropPose" -> API.EvaluatePropPose((long)a[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        NativeModelQueryPort queries = (NativeModelQueryPort) Proxy.newProxyInstance(Probe.class.getClassLoader(),
                new Class<?>[]{NativeModelQueryPort.class}, (p, method, a) -> {
                    if (method.getName().equals("getBoneNames")) return API.GetBoneNames((long)a[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
        for (boolean cpu : new boolean[]{false, true}) {
            long actor = 0, prop = 0, ordinary = 0, animation = 0;
            var parenting = new PropBoneParenting(matrices, queries);
            try {
                actor = API.LoadModelPMX(fixture.resolve("fixture.pmx").toString(), fixture.toString(), 1);
                prop = API.LoadModelPMX(fixture.resolve("fixture.pmx").toString(), fixture.toString(), 1);
                ordinary = API.LoadModelPMX(fixture.resolve("fixture.pmx").toString(), fixture.toString(), 1);
                check(actor != 0 && prop != 0 && ordinary != 0, "load independent PMX instances");
                API.SetPhysicsEnabled(ordinary, false);
                API.EvaluatePropPose(ordinary);
                Matrix4f ordinaryCenter = parenting.pose(ordinary, "Center")[0];
                check(API.GetVertexCount(prop) == 3, "fixture mesh retained");
                API.SetPhysicsEnabled(actor, false);
                API.SetPhysicsEnabled(prop, false);
                animation = API.LoadAnimation(prop, fixture.resolve("fixture.vmd").toString());
                check(animation != 0, "load VMD");
                API.ChangeModelAnim(prop, animation, 0);
                API.SetLayerSpeed(prop, 0, 0);
                API.SetLayerLoop(prop, 0, false);
                seek(actor, 0, cpu);
                seek(prop, 30, cpu);
                Matrix4f outer = new Matrix4f().translation(4, -2, 1).rotateY(.3f).scale(.8f);
                var bindings = new LinkedHashMap<String, BoneBinding>();
                Matrix4f rootBefore = parenting.pose(prop, "Root")[0];
                Matrix4f centerBefore = parenting.pose(prop, "Center")[0];
                for (String child : new String[]{"Root", "Center"}) {
                    String targetName = child.equals("Root") ? "Center" : "Tip";
                    Matrix4f target = PropBoneParenting.targetInProp(outer, parenting.pose(actor, targetName)[0]);
                    Matrix4f[] pose = parenting.pose(prop, child);
                    var binding = new BoneBinding(child, targetName, Offset.IDENTITY,
                            PropBoneParenting.compensation(target, pose[0], PropBoneParenting.animationLocal(pose[1],pose[2]), true), true, false);
                    bindings.put(child, binding);
                    parenting.apply(actor, prop, outer, new State(true, null, bindings));
                    check(API.EvaluatePropPose(prop), "evaluate registered pose");
                    near(rootBefore, parenting.pose(prop, "Root")[0], "root registration continuity");
                    near(centerBefore, parenting.pose(prop, "Center")[0], "center registration continuity");
                }
                Matrix4f shiftedOuter=new Matrix4f().translation(-8,5,3).rotateZ(.7f).scale(.8f);
                parenting.apply(actor,prop,shiftedOuter,new State(true,null,bindings));
                API.EvaluatePropPose(prop);
                near(new Matrix4f(outer).mul(rootBefore),
                        new Matrix4f(shiftedOuter).mul(parenting.pose(prop,"Root")[0]),"keep-world ignores moving outer reference");
                near(new Matrix4f(outer).mul(centerBefore),
                        new Matrix4f(shiftedOuter).mul(parenting.pose(prop,"Center")[0]),"same-frame child ignores moving outer reference");
                parenting.apply(actor,prop,outer,new State(true,null,bindings));
                API.EvaluatePropPose(prop);
                // Reject an invalid replacement atomically, keeping both registered controls.
                check(!API.SetPropBoneParents(prop, new int[]{3}, new Matrix4f().get(new float[16])),
                        "physics-owned bone must reject direct control");
                check(!API.SetPropBoneParents(prop, new int[]{0}, new float[15]), "JNI array bounds");
                API.EvaluatePropPose(prop);
                near(centerBefore, parenting.pose(prop, "Center")[0], "invalid input preserves active parents");
                seek(prop, 60, cpu);
                Matrix4f rootAfter = parenting.pose(prop, "Root")[0];
                Matrix4f centerAfter = parenting.pose(prop, "Center")[0];
                check(rootAfter.m30() > rootBefore.m30() + 2.9f, "VMD translation continues");
                check(!centerAfter.equals(centerBefore, .1f), "VMD rotation continues");
                Matrix4f[] tip = parenting.pose(prop, "Tip");
                near(new Matrix4f(centerAfter).mul(tip[1]), tip[0], "descendants inherit registered center");
                API.SetPhysicsEnabled(prop, true);
                Matrix4f tasselBefore = parenting.pose(prop, "Tassel")[0];
                for (int i = 0; i < 30; i++) {
                    if (cpu) API.UpdateModel(prop, 1f / 60);
                    else API.UpdateAnimationOnly(prop, 1f / 60);
                }
                Matrix4f tassel = parenting.pose(prop, "Tassel")[0];
                check(tassel.isFinite(), "physics stays finite with bone parents and joint");
                check(!tassel.equals(tasselBefore, .001f), "dynamic tassel actually moves");
                near(centerAfter, parenting.pose(prop, "Center")[0], "physics writeback retains external parents");
                parenting.clear(prop);
                API.SetPhysicsEnabled(prop, false);
                seek(prop, 30, cpu);
                Matrix4f[] center = parenting.pose(prop, "Center");
                near(new Matrix4f(parenting.pose(prop, "Root")[0]).mul(center[1]), center[0],
                        "clear restores original hierarchy");
                // MMD outside parenting removes static PMX origin, never the authored motion.
                near(new Matrix4f().translation(0,1,0),center[2],"JNI static origin excludes all VMD input");
                var oldBuffer=java.nio.ByteBuffer.allocateDirect(128).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                check(API.GetPropBonePose(prop,1,oldBuffer),"Original 128-byte pose callers remain supported");
                Matrix4f snapTarget=PropBoneParenting.targetInProp(outer,parenting.pose(actor,"Tip")[0]);
                var snap=new BoneBinding("Center","Tip",Offset.IDENTITY,PropTimeline.identityCompensation(),false,false);
                var snapState=new State(true,null,java.util.Map.of("Center",snap));
                for(int frame:new int[]{0,60,0}) {
                    parenting.apply(actor,prop,outer,snapState);
                    seek(prop,frame,cpu);
                    // The VMD loader reflects MMD's Z axis into the engine coordinate system.
                    Matrix4f authored=frame==0?new Matrix4f().translation(2,0,-1).rotateY(-.4f)
                            :new Matrix4f().translation(4,1,2).rotateZ((float)Math.PI/2);
                    Matrix4f snapGlobal=parenting.pose(prop,"Center")[0];
                    near(new Matrix4f(snapTarget).mul(authored),snapGlobal,"snap retains absolute VMD at frame "+frame);
                    Matrix4f movedOuter=new Matrix4f().translation(-8,5,3).rotateZ(.7f).scale(.8f);
                    parenting.apply(actor,prop,movedOuter,snapState);
                    API.EvaluatePropPose(prop);
                    near(new Matrix4f(outer).mul(snapGlobal),
                            new Matrix4f(movedOuter).mul(parenting.pose(prop,"Center")[0]),
                            "Moving legacy outer coordinates cannot drag an externally parented bone twice");
                }
                placedRegistration(actor,prop,cpu,outer,parenting);
                windowedTimeline(actor,prop,cpu,outer,parenting);
                parenting.apply(actor,prop,shiftedOuter,snapState);
                API.ChangeModelAnim(prop,0,0);
                API.EvaluatePropPose(prop);
                near(PropBoneParenting.targetInProp(shiftedOuter,parenting.pose(actor,"Tip")[0]),
                        parenting.pose(prop,"Center")[0],"Zero motion coincides with target despite nonzero PMX origin");
                parenting.clear(prop);
                // The stage and ordinary paths load identical assets under separate model identities.
                // Exercise the ordinary instance again after stage VMD, parenting, physics and cleanup.
                if (cpu) API.UpdateModel(ordinary, 0);
                else API.UpdateAnimationOnly(ordinary, 0);
                near(ordinaryCenter, parenting.pose(ordinary, "Center")[0], "stage must never alter the ordinary prop pose");
                API.DeleteModel(prop); prop = 0;
                API.EvaluatePropPose(ordinary);
                near(ordinaryCenter, parenting.pose(ordinary, "Center")[0], "stage disposal must preserve the ordinary prop");
                System.out.println("PASS real JNI " + (cpu ? "CPU" : "GPU pose")
                        + ": placement align/fine-adjust/confirm/recalculate/playback, same-frame Root/Center, absolute VMD snap, rewind, origin, outer-space cancellation, physics/joint, clear, ordinary-prop isolation");
            } finally {
                if (prop != 0) API.DeleteModel(prop);
                if (actor != 0) API.DeleteModel(actor);
                if (ordinary != 0) API.DeleteModel(ordinary);
                if (animation != 0) API.DeleteAnimation(animation);
            }
        }
    }
}
