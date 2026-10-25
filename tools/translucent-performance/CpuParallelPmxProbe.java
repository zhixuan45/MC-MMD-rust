import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.bridge.runtime.PhysicsSolverNative;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Compares CPU PMX evaluation across sequential, outer-parallel, and Rayon-budget paths. */
public final class CpuParallelPmxProbe {
    private static final NativeFunc API = new NativeFunc();
    private static final float DT = 1.0f / 60.0f;
    private static final int MODEL_COUNT = 4;
    private static final int FRAMES = 60;
    private static final float TOLERANCE = 2.0e-4f;

    private CpuParallelPmxProbe() { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static void configurePhysics() {
        // Match the inspected game configuration, in this isolated probe process only.
        API.SetPhysicsConfig(true, -98.0f, 60.0f, 5, 0.3f,
                20.0f, 20.0f, true, false, true, 0, 0.8f, false);
        check(PhysicsSolverNative.SetIterations(10), "set solver iterations to inspected value");
    }

    private static long load(Path pmx) {
        long handle = API.LoadModelPMX(pmx.toAbsolutePath().toString(),
                pmx.getParent().toAbsolutePath().toString(), 3);
        check(handle != 0, "load real PMX: " + pmx.getFileName());
        API.SetAutoBlinkEnabled(handle, false);
        API.SetTailPhysicsOptions(handle, false, false);
        API.SetPhysicsEnabled(handle, true);
        return handle;
    }

    private static final class Model {
        final long handle;
        final ByteBuffer profile = direct(512);
        Model(long handle) { this.handle = handle; }
    }

    private static Model[] loadGroup(Path pmx, int count) {
        Model[] group = new Model[count];
        for (int i = 0; i < count; i++) group[i] = new Model(load(pmx));
        return group;
    }

    private static void update(Model model, int internalBudget) {
        model.profile.clear();
        check(API.UpdateModelParallel(model.handle, DT, true, internalBudget, false, model.profile),
                "CPU evaluation handle=" + model.handle);
        check(model.profile.getLong(0) == 1, "profile schema handle=" + model.handle);
    }

    private static float[] copyAddress(long address, int floatCount, String label) {
        check(address != 0, label + " pointer");
        ByteBuffer bytes = direct(Math.multiplyExact(floatCount, Float.BYTES));
        API.CopyDataToByteBuffer(bytes, address, bytes.capacity());
        FloatBuffer values = bytes.asFloatBuffer();
        float[] result = new float[floatCount];
        values.get(result);
        for (int i = 0; i < result.length; i++) {
            check(Float.isFinite(result[i]), label + " non-finite value at " + i);
        }
        return result;
    }

    private static float[] matrices(long handle) {
        int bones = API.GetBoneCount(handle);
        check(bones > 0, "PMX has bones");
        ByteBuffer bytes = direct(Math.multiplyExact(bones, 16 * Float.BYTES));
        check(API.CopySkinningMatricesToBuffer(handle, bytes) == bones, "copy skinning matrices");
        float[] result = new float[bones * 16];
        bytes.asFloatBuffer().get(result);
        return result;
    }

    private static float[] positions(long handle, int vertices) {
        return copyAddress(API.GetPoss(handle), Math.multiplyExact(vertices, 3), "CPU positions");
    }

    private static float[] normals(long handle, int vertices) {
        return copyAddress(API.GetNormals(handle), Math.multiplyExact(vertices, 3), "CPU normals");
    }

    private static float maxDifference(float[] expected, float[] actual, String label) {
        check(expected.length == actual.length, label + " length: " + expected.length + " vs " + actual.length);
        float maximum = 0.0f;
        int worst = -1;
        for (int i = 0; i < expected.length; i++) {
            check(Float.isFinite(expected[i]) && Float.isFinite(actual[i]), label + " non-finite value at " + i);
            float difference = Math.abs(expected[i] - actual[i]);
            if (difference > maximum) { maximum = difference; worst = i; }
        }
        check(maximum <= TOLERANCE,
                label + " diverged: maxAbs=" + maximum + " at " + worst + " (tol=" + TOLERANCE + ")");
        return maximum;
    }

    private static float compare(Model expected, Model actual, int vertices, String label) {
        float max = maxDifference(matrices(expected.handle), matrices(actual.handle), label + " bones");
        max = Math.max(max, maxDifference(positions(expected.handle, vertices),
                positions(actual.handle, vertices), label + " vertices"));
        return Math.max(max, maxDifference(normals(expected.handle, vertices),
                normals(actual.handle, vertices), label + " normals"));
    }

    private static void updateSequential(Model[] models, int budget) {
        for (Model model : models) update(model, budget);
    }

    private static void updateParallel(Model[] models, ExecutorService workers) throws Exception {
        CountDownLatch ready = new CountDownLatch(models.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(models.length);
        for (Model model : models) futures.add(workers.submit(() -> {
            ready.countDown();
            try {
                start.await();
                update(model, 1); // Production multi-model batches cap nested Rayon/Bullet work at one.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        }));
        ready.await();
        start.countDown();
        for (Future<?> future : futures) future.get();
    }

    private static void close(Model[] models) {
        if (models == null) return;
        for (Model model : models) if (model != null && model.handle != 0) API.DeleteModel(model.handle);
    }

    public static void main(String[] args) throws Exception {
        check(args.length >= 2, "usage: CpuParallelPmxProbe <native-dll> <real-pmx>");
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        var instance = NativeFunc.class.getDeclaredField("inst");
        instance.setAccessible(true);
        instance.set(null, API);

        long capabilities = API.GetEvaluationCapabilities();
        check((capabilities & 3) == 3, "budgeted profile JNI capability required; caps=" + capabilities);
        configurePhysics();
        Path pmx = Path.of(args[1]).toAbsolutePath();
        Model[] sequential = null, outerParallel = null;
        Model rayonBudget = null;
        ExecutorService workers = Executors.newFixedThreadPool(MODEL_COUNT);
        try {
            sequential = loadGroup(pmx, MODEL_COUNT);
            outerParallel = loadGroup(pmx, MODEL_COUNT);
            rayonBudget = new Model(load(pmx));
            int vertices = Math.toIntExact(API.GetVertexCount(sequential[0].handle));
            check(vertices >= 2048, "real model must exercise Rayon CPU vertex skinning; vertices=" + vertices);
            int bones = API.GetBoneCount(sequential[0].handle);
            for (Model model : sequential) {
                check(API.GetBoneCount(model.handle) == bones, "sequential group bone layout");
                check(API.GetVertexCount(model.handle) == vertices, "sequential group vertex layout");
            }
            for (Model model : outerParallel) {
                check(API.GetBoneCount(model.handle) == bones, "parallel group bone layout");
                check(API.GetVertexCount(model.handle) == vertices, "parallel group vertex layout");
            }
            check(API.GetBoneCount(rayonBudget.handle) == bones, "Rayon model bone layout");
            check(API.GetVertexCount(rayonBudget.handle) == vertices, "Rayon model vertex layout");

            // Warm each identical physics world with the same fixed dt and default internal budget.
            for (int frame = 0; frame < 5; frame++) {
                updateSequential(sequential, 1);
                updateSequential(outerParallel, 1);
                update(rayonBudget, 1);
            }
            float maxOuter = 0.0f, maxRayon = 0.0f;
            int rayonBudgetWidth = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            for (int frame = 0; frame < FRAMES; frame++) {
                updateSequential(sequential, 1);
                update(rayonBudget, rayonBudgetWidth);
                updateParallel(outerParallel, workers);
                if (frame % 5 == 4 || frame == FRAMES - 1) {
                    for (int i = 0; i < MODEL_COUNT; i++) {
                        maxOuter = Math.max(maxOuter, compare(sequential[i], outerParallel[i], vertices,
                                "outer-parallel frame=" + frame + " model=" + i));
                    }
                    maxRayon = Math.max(maxRayon, compare(sequential[0], rayonBudget, vertices,
                            "Rayon-budget frame=" + frame));
                }
            }
            System.out.println("PASS real PMX CPU JNI: file=" + pmx.getFileName()
                    + " vertices=" + vertices + " bones=" + bones + " frames=" + FRAMES
                    + " dt=" + DT + " outerHandles=" + MODEL_COUNT
                    + " outerMaxAbs=" + maxOuter + " rayonBudget=" + rayonBudgetWidth
                    + " rayonMaxAbs=" + maxRayon
                    + " physicsConfig={fps:60,maxSubsteps:5,solverIterations:10}");
        } finally {
            workers.shutdown();
            close(sequential);
            close(outerParallel);
            close(rayonBudget == null ? null : new Model[]{rayonBudget});
        }
    }
}
