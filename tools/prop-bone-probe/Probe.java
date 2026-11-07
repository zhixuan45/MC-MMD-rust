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
    private static void seek(long model, float frame, boolean cpu) {
        API.SeekLayer(model, 0, frame);
        if (cpu) API.UpdateModel(model, 0);
        else API.UpdateAnimationOnly(model, 0);
    }
    public static void main(String[] args) {
        System.load(args[0]);
        Path fixture = Path.of(args[1]);
        NativeMatrixPort matrices = (NativeMatrixPort) Proxy.newProxyInstance(Probe.class.getClassLoader(),
                new Class<?>[]{NativeMatrixPort.class}, (p, method, a) -> switch (method.getName()) {
                    case "getPropBonePose" -> API.GetPropBonePose((long)a[0], (int)a[1], (java.nio.ByteBuffer)a[2]);
                    case "setPropBoneParents" -> API.SetPropBoneParents((long)a[0], (int[])a[1], (float[])a[2]);
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
                            PropBoneParenting.compensation(target, pose[0], pose[1], true), true, false);
                    bindings.put(child, binding);
                    parenting.apply(actor, prop, outer, new State(true, null, bindings));
                    check(API.EvaluatePropPose(prop), "evaluate registered pose");
                    near(rootBefore, parenting.pose(prop, "Root")[0], "root registration continuity");
                    near(centerBefore, parenting.pose(prop, "Center")[0], "center registration continuity");
                }
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
                // The stage and ordinary paths load identical assets under separate model identities.
                // Exercise the ordinary instance again after stage VMD, parenting, physics and cleanup.
                if (cpu) API.UpdateModel(ordinary, 0);
                else API.UpdateAnimationOnly(ordinary, 0);
                near(ordinaryCenter, parenting.pose(ordinary, "Center")[0], "stage must never alter the ordinary prop pose");
                API.DeleteModel(prop); prop = 0;
                API.EvaluatePropPose(ordinary);
                near(ordinaryCenter, parenting.pose(ordinary, "Center")[0], "stage disposal must preserve the ordinary prop");
                System.out.println("PASS real JNI " + (cpu ? "CPU" : "GPU pose")
                        + ": same-frame Root/Center, keep-world, VMD, atomic rejection, physics/joint, clear, ordinary-prop isolation");
            } finally {
                if (prop != 0) API.DeleteModel(prop);
                if (actor != 0) API.DeleteModel(actor);
                if (ordinary != 0) API.DeleteModel(ordinary);
                if (animation != 0) API.DeleteAnimation(animation);
            }
        }
    }
}
