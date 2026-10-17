package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.render.pipeline.TranslucentDiagnostics;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;
import com.shiroha.mmdskin.texture.runtime.TextureAlphaCoverage;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Sorts translucent triangle centers across materials without changing their alpha. CPU positions
 * are reused; supported GPU passes prepare device indices without reading positions back.
 * Compatibility passes read GPU positions once per animation revision. This is centroid sorting,
 * not order-independent handling of intersecting triangles.
 */
public final class TranslucentDrawOrder implements AutoCloseable {
    private static boolean reusePreparation() {
        return !"false".equalsIgnoreCase(System.getProperty("mmdskin.reuseTranslucentPreparation"));
    }
    private final TranslucentBatchSubmission submission = new TranslucentBatchSubmission();
    private ProgramSamplerLayout batchLayout;
    private final GpuTranslucentPreparation device = new GpuTranslucentPreparation();
    private TranslucentBatchPlan deviceMaterials;
    private boolean deviceActive;
    private int batchWindowsSubmitted;
    private final PreparedGeometry prepared = new PreparedGeometry();
    private int topologyBuild = -1;
    private int[] topologyRows = new int[0], packedTopology = new int[0];

    int submitBatch(ModelMaterial[] materials, DrawMaterialCache materialCache) {
        if (!deviceActive && drawCount == 0) return 0;
        batchWindowsSubmitted = deviceActive
                ? submission.drawGpu(deviceMaterials, materials, materialCache, device, batchLayout)
                : submission.draw(submeshes, drawCount, materials, materialCache, batchLayout);
        return batchWindowsSubmitted;
    }

    /** 本次透明 pass 实际提交的连续纹理窗口数。 */
    public int batchWindowCount() { return Math.max(0, batchWindowsSubmitted); }
    public record Inputs(
            ByteBuffer positions,
            int positionBuffer,
            int vertexCount,
            FloatBuffer modelView,
            FloatBuffer projection,
            long geometryRevision,
            boolean dynamicIndices,
            ByteBuffer uvs,
            int uvBuffer,
            long uvRevision,
            ByteBuffer indices,
            long indicesRevision) {
        public Inputs(ByteBuffer positions, int positionBuffer, int vertexCount, FloatBuffer modelView,
                      FloatBuffer projection, long geometryRevision, boolean dynamicIndices,
                      ByteBuffer uvs, int uvBuffer, long uvRevision) {
            this(positions, positionBuffer, vertexCount, modelView, projection, geometryRevision,
                    dynamicIndices, uvs, uvBuffer, uvRevision, null, 0);
        }

        /** CPU bytes must describe the currently bound EBO; bump revision on every rewrite. */
        public Inputs withIndices(ByteBuffer bytes, long revision) {
            return new Inputs(positions, positionBuffer, vertexCount, modelView, projection,
                    geometryRevision, dynamicIndices, uvs, uvBuffer, uvRevision, bytes, revision);
        }
        public static Inputs cpu(
                ByteBuffer positions, int positionBuffer, int vertexCount,
                FloatBuffer modelView, FloatBuffer projection, long geometryRevision,
                boolean dynamicIndices, ByteBuffer uvs, int uvBuffer, boolean hasUvMorph) {
            return new Inputs(positions, positionBuffer, vertexCount, modelView, projection,
                    geometryRevision, dynamicIndices, uvs, uvBuffer,
                    hasUvMorph ? geometryRevision : 0);
        }

        public static Inputs gpu(
                int positionBuffer, int vertexCount, FloatBuffer modelView, FloatBuffer projection,
                long geometryRevision, boolean dynamicIndices, ByteBuffer baseUvs,
                int baseUvBuffer, int skinnedUvBuffer, boolean hasUvMorph) {
            // A compute UV buffer exists even without UV morphs; its allocation is not a revision.
            return new Inputs(null, positionBuffer, vertexCount, modelView, projection,
                    geometryRevision, dynamicIndices, hasUvMorph ? null : baseUvs,
                    hasUvMorph ? skinnedUvBuffer : baseUvBuffer,
                    hasUvMorph ? geometryRevision : 0);
        }

        public Inputs(
                ByteBuffer positions,
                int positionBuffer,
                int vertexCount,
                FloatBuffer view,
                long revision,
                boolean dynamic) {
            this(positions, positionBuffer, vertexCount, view, null, revision, dynamic, null, 0, 0);
        }
    }

    private int indexBuffer;
    private int sourceBuffer = -1, sourceBytes = -1, sourceIndexSize = -1;
    private long indexRevision = Long.MIN_VALUE, positionRevision = Long.MIN_VALUE;
    private int positionSource = -1;
    private long uvRevision = Long.MIN_VALUE;
    private int uvSource = -1;
    private int[] sourceIndices = new int[0];
    private long[] keys = new long[0];
    private int[] candidateStarts = new int[0], candidateOwners = new int[0];
    private int[] candidateVertices = new int[0];
    private int candidateCount, candidateVertexCount;
    private int candidateSourceBuffer = -1, candidateSourceBytes = -1, candidateIndexSize = -1;
    private long candidateIndexRevision = Long.MIN_VALUE;
    private int candidateVertexLimit = -1, candidateSubmeshCount = -1;
    private int candidateUvBuffer = -1;
    private ByteBuffer candidateUvObject;
    private long candidateUvRevision = Long.MIN_VALUE;
    private boolean candidateCoverageSafe, candidateValid;
    private int[] candidateMaterialIds = new int[0], candidateFirsts = new int[0], candidateCounts = new int[0];
    private boolean[] candidateVisible = new boolean[0], candidateActive = new boolean[0], candidateIncludeOpaque = new boolean[0];
    private TextureAlphaCoverage[] candidateCoverage = new TextureAlphaCoverage[0];
    private int[] batches = new int[0];
    private boolean[] includeOpaque = new boolean[0];
    private float[] vertexDepths = new float[0];
    private int[] clipCodes = new int[0];
    private ByteBuffer gpuPositions, gpuUvs, sortedIndices, submeshes;
    private int drawCount;

    private record Batch(
            int texture,
            int sphereMaterial,
            boolean cull,
            boolean hasAlpha,
            boolean hasTranslucent,
            float r,
            float g,
            float b,
            float alpha,
            float sr,
            float sg,
            float sb,
            float power,
            float eye) {}

    public int indexBuffer() {
        return deviceActive ? device.indexBuffer() : indexBuffer;
    }

    public ByteBuffer submeshes() {
        return submeshes;
    }

    public int drawCount() {
        return drawCount;
    }

    private int candidateBuildCount;

    public int candidateBuildCount() { return candidateBuildCount; }
    public int candidateTriangleCount() { return candidateCount; }
    public int candidateVertexCount() { return candidateVertexCount; }

    public boolean prepare(
            Inputs inputs,
            ByteBuffer source,
            int count,
            int indexSize,
            ModelMaterial[] materials,
            ByteBuffer morphs,
            SubMeshDrawHelper.AlphaResolver alphaResolver) {
        return prepare(inputs, source, count, indexSize, materials, morphs, alphaResolver, id -> materials[id].tex);
    }

    public boolean prepare(Inputs inputs, ByteBuffer source, int count, int indexSize,
                           ModelMaterial[] materials, ByteBuffer morphs,
                           SubMeshDrawHelper.AlphaResolver alphaResolver, SubMeshDrawHelper.TextureResolver textures) {
        return prepare(inputs, source, count, indexSize, materials, morphs, alphaResolver, textures,
                new DrawMaterialCache(materials, morphs, alphaResolver, textures));
    }

    boolean prepare(Inputs inputs, ByteBuffer source, int count, int indexSize,
                    ModelMaterial[] materials, ByteBuffer morphs,
                    SubMeshDrawHelper.AlphaResolver alphaResolver, SubMeshDrawHelper.TextureResolver textures,
                    DrawMaterialCache materialCache) {
        return prepare(inputs, source, count, indexSize, materials, morphs, alphaResolver, textures, materialCache, true);
    }

    /** 批量提交不可用时仍按远近顺序绘制；仅 GPU 准备需要重建。 */
    boolean prepareCompatibility(Inputs inputs, ByteBuffer source, int count, int indexSize,
                                 ModelMaterial[] materials, ByteBuffer morphs,
                                 SubMeshDrawHelper.AlphaResolver alphaResolver, SubMeshDrawHelper.TextureResolver textures,
                                 DrawMaterialCache materialCache) {
        return !deviceActive || prepare(inputs, source, count, indexSize, materials, morphs,
                alphaResolver, textures, materialCache, false);
    }

    private boolean prepare(Inputs inputs, ByteBuffer source, int count, int indexSize,
                            ModelMaterial[] materials, ByteBuffer morphs,
                            SubMeshDrawHelper.AlphaResolver alphaResolver, SubMeshDrawHelper.TextureResolver textures,
                            DrawMaterialCache materialCache, boolean allowDevice) {
        var batchUniforms = com.shiroha.mmdskin.render.shader.TranslucentBatchShader.current();
        int activeProgram = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        batchLayout = batchUniforms == null ? null : ProgramSamplerLayout.capture(activeProgram);
        batchWindowsSubmitted = 0;
        if (batchUniforms == null) TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.BATCH_REJECT_PROGRAM, 1);
        else if (batchLayout == null) TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.BATCH_REJECT_SAMPLERS, 1);
        boolean previousDevice = deviceActive;
        deviceActive = false;
        int triangles = 0;
        long classifyTimer = TranslucentDiagnostics.startTimer();
        if (batches.length < count) {
            batches = new int[count];
            includeOpaque = new boolean[count];
        }
        var batchIds = new HashMap<Batch, Integer>();
        boolean packEntity;
        boolean coverageSafe;
        try {
            int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
            boolean specular = GL46C.glGetUniformLocation(program, "MaterialSpecular") >= 0;
            int eyeLocation = GL46C.glGetUniformLocation(program, "EyeEmission");
            boolean eye = eyeLocation >= 0 && GL46C.glGetUniformf(program, eyeLocation) > 0;
            boolean splitPixels = GL46C.glGetUniformLocation(program, "AlphaPass") >= 0;
            boolean sphere = GL46C.glGetUniformLocation(program, "SphereMode") >= 0;
            // Pack entity shaders may deform vertices or derive alpha beyond the diffuse atlas.
            // Own Toon programs have the known MaterialColor/texture-alpha formula.
            packEntity =
                    IrisCompat.isIrisProgram(RenderSystem.getShader())
                            && GL46C.glGetUniformLocation(program, "MaterialColor") < 0;
            coverageSafe = splitPixels && !packEntity;
            for (int s = 0; s < count; s++) {
                batches[s] = -1;
                if (!includes(source, s, materials, materialCache)) continue;
                triangles += source.getInt(s * 20 + 8) / 3;
                var material = materials[source.getInt(s * 20)];
                var state = materialCache.resolve(source.getInt(s * 20), source.getFloat(s * 20 + 12));
                boolean power =
                        specular
                                && (state.specular[0] != 0
                                        || state.specular[1] != 0
                                        || state.specular[2] != 0);
                var batch =
                        new Batch(
                                material.tex,
                                // 球面模式及 morph 属于逐材质状态，不能只按主贴图合并。
                                sphere && material.sphereTex > 0 && (material.sphereMode == 1 || material.sphereMode == 2)
                                        ? source.getInt(s * 20) : -1,
                                source.get(s * 20 + 17) == 0,
                                material.hasAlpha,
                                material.hasTranslucentPixels,
                                state.color[0],
                                state.color[1],
                                state.color[2],
                                state.alpha,
                                specular ? state.specular[0] : 0,
                                specular ? state.specular[1] : 0,
                                specular ? state.specular[2] : 0,
                                power ? state.specularPower : 0,
                                eye ? material.eyeEmissionMaskMode() : 0);
                batches[s] = batchIds.computeIfAbsent(batch, k -> batchIds.size());
                // 材质透明时，贴图实心像素也必须进入透明排序。
                includeOpaque[s] = !splitPixels || state.alpha < MaterialRenderState.OPAQUE_ALPHA;
            }
        } finally {
            TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.CLASSIFY, classifyTimer);
        }
        if (triangles == 0) {
            prepared.invalidate();
            candidateCount = candidateVertexCount = drawCount = 0;
            candidateValid = false;
            return false;
        }
        int original = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int previousRead = GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
        try {
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, original);
            int bytes = inputs.indices() != null ? inputs.indices().remaining()
                    : GL46C.glGetBufferParameteri(GL46C.GL_COPY_READ_BUFFER, GL46C.GL_BUFFER_SIZE);
            long revision = inputs.indices() != null ? inputs.indicesRevision()
                    : inputs.dynamicIndices() ? inputs.geometryRevision() : 0;
            boolean indicesReloaded = original != sourceBuffer
                    || bytes != sourceBytes
                    || indexSize != sourceIndexSize
                    || (inputs.dynamicIndices() && inputs.indices() == null)
                    || revision != indexRevision;
            if (indicesReloaded) {
                if ((indexSize != 1 && indexSize != 2 && indexSize != 4) || bytes % indexSize != 0)
                    throw new IllegalArgumentException("Invalid source index format");
                ByteBuffer data = inputs.indices() != null ? inputs.indices().slice().order(java.nio.ByteOrder.nativeOrder())
                        : MemoryUtil.memAlloc(bytes);
                try {
                    if (inputs.indices() == null) {
                    long readbackTimer = TranslucentDiagnostics.startTimer();
                    try {
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, data);
                    } finally {
                        TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.READBACK, readbackTimer);
                        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.READBACK_CALLS, 1);
                        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.READBACK_BYTES, bytes);
                    }
                    }
                    sourceIndices = new int[bytes / indexSize];
                    for (int i = 0; i < sourceIndices.length; i++)
                        sourceIndices[i] =
                                switch (indexSize) {
                                    case 1 -> data.get(i) & 255;
                                    case 2 -> data.getShort(i * 2) & 65535;
                                    case 4 -> data.getInt(i * 4);
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "Unsupported index size " + indexSize);
                                };
                } finally {
                    if (inputs.indices() == null) MemoryUtil.memFree(data);
                }
                sourceBuffer = original;
                sourceBytes = bytes;
                sourceIndexSize = indexSize;
                indexRevision = revision;
                candidateValid = false;
            }
            boolean deviceEligible = allowDevice && inputs.positions() == null && !packEntity
                    && batchLayout != null && GpuTranslucentPreparation.enabled();
            if (deviceEligible) {
                // UV-morphed candidates remain a conservative superset; the fragment alpha pass
                // still rejects opaque/empty pixels. Never read dynamic UVs back just to prune.
                boolean pruneCoverage = coverageSafe && inputs.uvs() != null;
                long candidateTimer = TranslucentDiagnostics.startTimer();
                boolean cached;
                try {
                    cached = candidateCacheMatches(inputs, source, count, bytes, indexSize,
                            pruneCoverage, batches, includeOpaque, materials);
                    if (!cached) buildCandidates(inputs, source, count, materials, batches, includeOpaque,
                            pruneCoverage, inputs.uvs(), bytes);
                } finally {
                    TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.CANDIDATES, candidateTimer);
                }
                TranslucentDiagnostics.add(cached ? TranslucentDiagnostics.Counter.CANDIDATE_REUSE
                        : TranslucentDiagnostics.Counter.CANDIDATE_BUILD, 1);
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.CANDIDATE_TRIANGLES, candidateCount);
                if (candidateCount == 0) {
                    prepared.invalidate();
                    drawCount = 0;
                    return true;
                }
                // Alpha coverage can eliminate entire submeshes. Their unused textures must not
                // push an otherwise valid single-window pass back onto the CPU.
                var devicePlan = TranslucentBatchPlan.singleWindowForCandidates(source, count,
                        candidateOwners, candidateCount, materials, materialCache::texture, batchLayout.capacity());
                if (devicePlan != null && TranslucentBatchSubmission.supports(devicePlan.materials(), batchLayout)) {
                    boolean reuse = reusePreparation() && previousDevice && prepared.matches(inputs, candidateBuildCount, source, count, batches, packEntity);
                    if (reuse || device.prepare(inputs, deviceTopology(devicePlan.rowBySubmesh()))) {
                        deviceMaterials = devicePlan.materials();
                        deviceActive = true;
                        drawCount = 0; // Device command owns the visible count; do not read it on the CPU.
                        prepared.capture(inputs, candidateBuildCount, source, count, batches, packEntity);
                        if (reuse) TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.PREPARED_REUSE, 1);
                        return true;
                    }
                } else {
                    TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.GPU_WINDOW_FALLBACK, 1);
                    if (devicePlan == null) TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.BATCH_REJECT_CAPACITY, 1);
                }
            }
            ByteBuffer positions = inputs.positions();
            if (positions == null) {
                int required = inputs.vertexCount() * 12;
                if (gpuPositions == null || gpuPositions.capacity() != required) {
                    if (gpuPositions != null) MemoryUtil.memFree(gpuPositions);
                    gpuPositions = MemoryUtil.memAlloc(required);
                    positionRevision = Long.MIN_VALUE;
                }
                if (positionRevision != inputs.geometryRevision()
                        || positionSource != inputs.positionBuffer()) {
                    // GPU compute writes precede the host read; do not use stale bind-pose centers.
                    // READBACK CPU 时间包含 barrier 可能触发的等待，不代表纯 GPU 等待时间。
                    long readbackTimer = TranslucentDiagnostics.startTimer();
                    try {
                    GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
                    GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, inputs.positionBuffer());
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, gpuPositions);
                    } finally {
                        TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.READBACK, readbackTimer);
                        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.READBACK_CALLS, 1);
                        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.READBACK_BYTES, required);
                    }
                    positionRevision = inputs.geometryRevision();
                    positionSource = inputs.positionBuffer();
                }
                positions = gpuPositions;
            }
            ByteBuffer uvs = inputs.uvs();
            if (uvs == null && inputs.uvBuffer() > 0) {
                int required = inputs.vertexCount() * 8;
                if (gpuUvs == null || gpuUvs.capacity() != required) {
                    if (gpuUvs != null) MemoryUtil.memFree(gpuUvs);
                    gpuUvs = MemoryUtil.memAlloc(required);
                    uvRevision = Long.MIN_VALUE;
                }
                if (uvRevision != inputs.uvRevision() || uvSource != inputs.uvBuffer()) {
                    long readbackTimer = TranslucentDiagnostics.startTimer();
                    try {
                    GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
                    GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, inputs.uvBuffer());
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, gpuUvs);
                    } finally {
                        TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.READBACK, readbackTimer);
                        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.READBACK_CALLS, 1);
                        TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.READBACK_BYTES, required);
                    }
                    uvRevision = inputs.uvRevision();
                    uvSource = inputs.uvBuffer();
                }
                uvs = gpuUvs;
            }
            long candidateTimer = TranslucentDiagnostics.startTimer();
            boolean cacheMatches;
            try {
                cacheMatches = candidateCacheMatches(inputs, source, count, bytes, indexSize, coverageSafe, batches, includeOpaque, materials);
                if (!cacheMatches) buildCandidates(inputs, source, count, materials,
                        batches, includeOpaque, coverageSafe, uvs, bytes);
            } finally {
                TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.CANDIDATES, candidateTimer);
            }
            TranslucentDiagnostics.add(cacheMatches
                    ? TranslucentDiagnostics.Counter.CANDIDATE_REUSE
                    : TranslucentDiagnostics.Counter.CANDIDATE_BUILD, 1);
            TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.CANDIDATE_TRIANGLES, candidateCount);
            if (reusePreparation() && !previousDevice && prepared.matches(inputs, candidateBuildCount, source, count, batches, packEntity)) {
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.PREPARED_REUSE, 1);
                return true;
            }
            prepared.invalidate();
            if (keys.length < candidateCount) keys = new long[candidateCount];
            float mx = inputs.modelView().get(2),
                    my = inputs.modelView().get(6),
                    mz = inputs.modelView().get(10);
            if (vertexDepths.length < inputs.vertexCount()) {
                vertexDepths = new float[inputs.vertexCount()];
                clipCodes = new int[inputs.vertexCount()];
            }
            Matrix4f clip =
                    inputs.projection() == null || packEntity
                            ? null
                            : new Matrix4f()
                                    .set(inputs.projection())
                                    .mul(new Matrix4f().set(inputs.modelView()));
            int used = 0;
            long depthTimer = TranslucentDiagnostics.startTimer();
            try {
                for (int i = 0; i < candidateVertexCount; i++) {
                    int v = candidateVertices[i];
                    float x = positions.getFloat(v * 12),
                            y = positions.getFloat(v * 12 + 4),
                            z = positions.getFloat(v * 12 + 8);
                    vertexDepths[v] = mx * x + my * y + mz * z;
                    clipCodes[v] = 0;
                    if (clip != null) {
                        float cx = clip.m00() * x + clip.m10() * y + clip.m20() * z + clip.m30();
                        float cy = clip.m01() * x + clip.m11() * y + clip.m21() * z + clip.m31();
                        float cz = clip.m02() * x + clip.m12() * y + clip.m22() * z + clip.m32();
                        float cw = clip.m03() * x + clip.m13() * y + clip.m23() * z + clip.m33();
                        clipCodes[v] =
                                (cx < -cw ? 1 : 0)
                                        | (cx > cw ? 2 : 0)
                                        | (cy < -cw ? 4 : 0)
                                        | (cy > cw ? 8 : 0)
                                        | (cz < -cw ? 16 : 0)
                                        | (cz > cw ? 32 : 0);
                    }
                }
                for (int candidate = 0; candidate < candidateCount; candidate++) {
                    int start = candidateStarts[candidate];
                    float z = 0;
                    int outside = 63;
                    for (int v = 0; v < 3; v++) {
                        int id = sourceIndices[start + v];
                        z += vertexDepths[id];
                        outside &= clipCodes[id];
                    }
                    if (outside != 0) continue;
                    int bits = Float.floatToIntBits(z);
                    int ordered = bits ^ ((bits >> 31) & 0x7fffffff);
                    // Stable ties retain author ordering for coincident facial surfaces.
                    keys[used] = ((long) ordered << 32) | (candidate & 0xffffffffL);
                    used++;
                }
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.VISIBLE_TRIANGLES, used);
            } finally {
                TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.DEPTH_KEYS, depthTimer);
            }
            // 仅并行处理 CPU 深度键；OpenGL 操作仍在渲染线程。
            long sortTimer = TranslucentDiagnostics.startTimer();
            try {
                Arrays.parallelSort(keys, 0, used);
            } finally {
                TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.SORT, sortTimer);
            }
            long indexTimer = TranslucentDiagnostics.startTimer();
            try {
                sortedIndices = resize(sortedIndices, Math.max(4, used * 12));
                submeshes = resize(submeshes, Math.max(20, used * 20));
                drawCount = 0;
                int previousOwner = -1;
                for (int t = 0; t < used; t++) {
                    int ordinal = (int) keys[t],
                            owner = candidateOwners[ordinal],
                            start = candidateStarts[ordinal];
                    for (int v = 0; v < 3; v++)
                        sortedIndices.putInt((t * 3 + v) * 4, sourceIndices[start + v]);
                    if (previousOwner >= 0 && batches[owner] == batches[previousOwner])
                        submeshes.putInt(
                                (drawCount - 1) * 20 + 8,
                                submeshes.getInt((drawCount - 1) * 20 + 8) + 3);
                    else {
                        int b = drawCount * 20;
                        for (int i = 0; i < 20; i++) submeshes.put(b + i, source.get(owner * 20 + i));
                        submeshes.putInt(b + 4, t * 3);
                        submeshes.putInt(b + 8, 3);
                        drawCount++;
                        previousOwner = owner;
                    }
                }
                sortedIndices.position(0).limit(used * 12);
                submeshes.position(0).limit(drawCount * 20);
            } finally {
                TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.INDEX_BUILD, indexTimer);
            }
            if (used > 0) TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.RUNS, drawCount);
            if (indexBuffer == 0) indexBuffer = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            long uploadTimer = TranslucentDiagnostics.startTimer();
            try {
                GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, sortedIndices, GL46C.GL_STREAM_DRAW);
            } finally {
                TranslucentDiagnostics.endTimer(TranslucentDiagnostics.Stage.UPLOAD, uploadTimer);
                TranslucentDiagnostics.add(TranslucentDiagnostics.Counter.UPLOAD_BYTES, used * 12L);
            }
            prepared.capture(inputs, candidateBuildCount, source, count, batches, packEntity);
            return true;
        } finally {
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, original);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, previousRead);
        }
    }

    private int[] deviceTopology(int[] rowBySubmesh) {
        if (topologyBuild == candidateBuildCount && Arrays.equals(topologyRows, rowBySubmesh)) return packedTopology;
        int[] data = new int[Math.multiplyExact(candidateCount, 4)];
        for (int i = 0; i < candidateCount; i++) {
            int start = candidateStarts[i];
            for (int v = 0; v < 3; v++) data[i * 4 + v] = sourceIndices[start + v];
            data[i * 4 + 3] = rowBySubmesh[candidateOwners[i]];
        }
        topologyBuild = candidateBuildCount;
        topologyRows = rowBySubmesh.clone();
        return packedTopology = data;
    }

    /** Single-result cache: exact comparisons, bounded by this instance's submesh count. */
    private static final class PreparedGeometry {
        private Inputs input;
        private int build;
        private boolean pack;
        private int[] state = new int[0];
        private final int[] matrices = new int[32];
        boolean matches(Inputs next, int nextBuild, ByteBuffer source, int count, int[] batches, boolean nextPack) {
            if (input == null || build != nextBuild || pack != nextPack || state.length != count * 6
                    || input.geometryRevision() != next.geometryRevision() || input.positions() != next.positions()
                    || input.positionBuffer() != next.positionBuffer() || input.vertexCount() != next.vertexCount()
                    || input.uvRevision() != next.uvRevision() || input.uvBuffer() != next.uvBuffer()
                    || input.dynamicIndices() != next.dynamicIndices()) return false;
            for (int i = 0; i < 32; i++) if (matrices[i] != matrixBits(next, i)) return false;
            for (int i = 0; i < count; i++) {
                for (int v = 0; v < 5; v++) if (state[i * 6 + v] != source.getInt(i * 20 + v * 4)) return false;
                if (state[i * 6 + 5] != batches[i]) return false;
            }
            return true;
        }
        void capture(Inputs next, int nextBuild, ByteBuffer source, int count, int[] batches, boolean nextPack) {
            input = next; build = nextBuild; pack = nextPack;
            if (state.length != count * 6) state = new int[count * 6];
            for (int i = 0; i < 32; i++) matrices[i] = matrixBits(next, i);
            for (int i = 0; i < count; i++) {
                for (int v = 0; v < 5; v++) state[i * 6 + v] = source.getInt(i * 20 + v * 4);
                state[i * 6 + 5] = batches[i];
            }
        }
        private static int matrixBits(Inputs input, int i) {
            FloatBuffer matrix = i < 16 ? input.modelView() : input.projection();
            return matrix == null ? 0x7fc00000 : Float.floatToRawIntBits(matrix.get(i % 16));
        }
        void invalidate() { input = null; }
    }

    private static boolean includes(ByteBuffer source, int s, ModelMaterial[] materials,
                                    DrawMaterialCache materialCache) {
        int b = s * 20, id = source.getInt(b);
        if (source.get(b + 16) == 0
                || source.getInt(b + 8) <= 0
                || id < 0
                || id >= materials.length) return false;
        ModelMaterial material = materials[id];
        MaterialRenderState state = materialCache.resolve(id, source.getFloat(b + 12));
        return state.alpha >= .001f
                && (state.alpha < MaterialRenderState.OPAQUE_ALPHA || material.hasAlpha);
    }

    private boolean candidateCacheMatches(
            Inputs inputs, ByteBuffer source, int count, int bytes, int indexSize,
            boolean coverageSafe, int[] currentBatches, boolean[] currentIncludeOpaque,
            ModelMaterial[] materials) {
        if (!candidateValid
                || candidateSourceBuffer != sourceBuffer || candidateSourceBytes != bytes
                || candidateIndexSize != indexSize || candidateIndexRevision != indexRevision
                || candidateVertexLimit != inputs.vertexCount() || candidateSubmeshCount != count
                || candidateUvBuffer != inputs.uvBuffer() || candidateUvObject != inputs.uvs()
                || candidateUvRevision != inputs.uvRevision()
                || candidateCoverageSafe != coverageSafe) return false;
        for (int s = 0; s < count; s++) {
            int base = s * 20;
            int id = source.getInt(base);
            boolean active = currentBatches[s] >= 0;
            var coverage = id >= 0 && id < materials.length ? materials[id].alphaCoverage : null;
            if (candidateMaterialIds[s] != id
                    || candidateFirsts[s] != source.getInt(base + 4)
                    || candidateCounts[s] != source.getInt(base + 8)
                    || candidateVisible[s] != (source.get(base + 16) != 0)
                    || candidateActive[s] != active
                    || (active && candidateIncludeOpaque[s] != currentIncludeOpaque[s])
                    || candidateCoverage[s] != coverage) return false;
        }
        return true;
    }

    private void buildCandidates(
            Inputs inputs, ByteBuffer source, int count,
            ModelMaterial[] materials,
            int[] currentBatches, boolean[] currentIncludeOpaque,
            boolean coverageSafe, ByteBuffer uvs, int bytes) {
        candidateValid = false;
        if (candidateMaterialIds.length < count) {
            candidateMaterialIds = new int[count];
            candidateFirsts = new int[count];
            candidateCounts = new int[count];
            candidateVisible = new boolean[count];
            candidateActive = new boolean[count];
            candidateIncludeOpaque = new boolean[count];
            candidateCoverage = new TextureAlphaCoverage[count];
        }
        boolean[] referenced = new boolean[inputs.vertexCount()];
        int maxTriangles = 0;
        for (int s = 0; s < count; s++) {
            int base = s * 20, id = source.getInt(base);
            boolean active = currentBatches[s] >= 0;
            candidateMaterialIds[s] = id;
            candidateFirsts[s] = source.getInt(base + 4);
            candidateCounts[s] = source.getInt(base + 8);
            candidateVisible[s] = source.get(base + 16) != 0;
            candidateActive[s] = active;
            candidateIncludeOpaque[s] = active && currentIncludeOpaque[s];
            candidateCoverage[s] = id >= 0 && id < materials.length ? materials[id].alphaCoverage : null;
            if (active) maxTriangles += source.getInt(base + 8) / 3;
        }
        int[] starts = new int[maxTriangles], owners = new int[maxTriangles];
        int used = 0, referencedCount = 0;
        for (int s = 0; s < count; s++) {
            if (!candidateActive[s]) continue;
            int first = candidateFirsts[s], n = candidateCounts[s];
            var coverage = candidateCoverage[s];
            for (int t = 0; t + 2 < n; t += 3) {
                int start = first + t;
                int a = sourceIndices[start], b = sourceIndices[start + 1], c = sourceIndices[start + 2];
                // Validate every active source triangle, including triangles later rejected by UV bounds.
                if (a < 0 || a >= inputs.vertexCount()) throw new IllegalArgumentException("Invalid model vertex " + a);
                if (b < 0 || b >= inputs.vertexCount()) throw new IllegalArgumentException("Invalid model vertex " + b);
                if (c < 0 || c >= inputs.vertexCount()) throw new IllegalArgumentException("Invalid model vertex " + c);
                if (coverageSafe && coverage != null && uvs != null) {
                    int ua = a * 8, ub = b * 8, uc = c * 8;
                    if (!coverage.mayBlend(uvs.getFloat(ua), uvs.getFloat(ua + 4),
                            uvs.getFloat(ub), uvs.getFloat(ub + 4),
                            uvs.getFloat(uc), uvs.getFloat(uc + 4), candidateIncludeOpaque[s])) continue;
                }
                starts[used] = start;
                owners[used] = s;
                used++;
                if (!referenced[a]) { referenced[a] = true; referencedCount++; }
                if (!referenced[b]) { referenced[b] = true; referencedCount++; }
                if (!referenced[c]) { referenced[c] = true; referencedCount++; }
            }
        }
        candidateStarts = Arrays.copyOf(starts, used);
        candidateOwners = Arrays.copyOf(owners, used);
        candidateVertices = new int[referencedCount];
        int out = 0;
        for (int v = 0; v < referenced.length; v++) if (referenced[v]) candidateVertices[out++] = v;
        candidateCount = used;
        candidateVertexCount = referencedCount;
        candidateSourceBuffer = sourceBuffer;
        candidateSourceBytes = bytes;
        candidateIndexSize = sourceIndexSize;
        candidateIndexRevision = indexRevision;
        candidateVertexLimit = inputs.vertexCount();
        candidateSubmeshCount = count;
        candidateUvBuffer = inputs.uvBuffer();
        candidateUvObject = inputs.uvs();
        candidateUvRevision = inputs.uvRevision();
        candidateCoverageSafe = coverageSafe;
        candidateValid = true;
        candidateBuildCount++;
    }

    private static ByteBuffer resize(ByteBuffer buffer, int size) {
        if (buffer == null || buffer.capacity() < size) {
            if (buffer != null) MemoryUtil.memFree(buffer);
            buffer = MemoryUtil.memAlloc(size);
        }
        buffer.clear();
        return buffer;
    }

    public long vramBytes() {
        return device.vramBytes() + submission.vramBytes() + (sortedIndices == null ? 0 : sortedIndices.limit());
    }

    public long ramBytes() {
        return (long) (topologyRows.length + packedTopology.length + prepared.state.length + 32) * 4
                + device.ramBytes() + submission.ramBytes() + (long) sourceIndices.length * 4
                + (long) keys.length * Long.BYTES
                + (long) batches.length * 4
                + (long) candidateStarts.length * 4
                + (long) candidateOwners.length * 4
                + (long) candidateVertices.length * 4
                + (long) candidateMaterialIds.length * 12
                + (long) candidateVisible.length * 3
                + (long) candidateCoverage.length * 8
                + (long) vertexDepths.length * 8
                + (gpuPositions == null ? 0 : gpuPositions.capacity())
                + (sortedIndices == null ? 0 : sortedIndices.capacity())
                + (gpuUvs == null ? 0 : gpuUvs.capacity())
                + includeOpaque.length
                + (submeshes == null ? 0 : submeshes.capacity());
    }

    @Override
    public void close() {
        device.close();
        deviceActive = false;
        deviceMaterials = null;
        prepared.invalidate();
        topologyBuild = -1;
        topologyRows = packedTopology = new int[0];
        submission.close();
        if (indexBuffer != 0) GL46C.glDeleteBuffers(indexBuffer);
        indexBuffer = 0;
        if (gpuPositions != null) MemoryUtil.memFree(gpuPositions);
        if (gpuUvs != null) MemoryUtil.memFree(gpuUvs);
        if (sortedIndices != null) MemoryUtil.memFree(sortedIndices);
        if (submeshes != null) MemoryUtil.memFree(submeshes);
        gpuPositions = gpuUvs = sortedIndices = submeshes = null;
        sourceIndices = new int[0];
        keys = new long[0];
        candidateStarts = candidateOwners = candidateVertices = batches = clipCodes = new int[0];
        vertexDepths = new float[0];
        includeOpaque = new boolean[0];
        drawCount = 0;
        sourceBuffer = sourceBytes = sourceIndexSize = positionSource = uvSource = -1;
        indexRevision = positionRevision = uvRevision = Long.MIN_VALUE;
        candidateCount = candidateVertexCount = 0;
        candidateValid = false;
        candidateSourceBuffer = candidateSourceBytes = candidateIndexSize = candidateVertexLimit = candidateSubmeshCount = candidateUvBuffer = -1;
        candidateUvObject = null;
        candidateUvRevision = Long.MIN_VALUE;
        candidateMaterialIds = candidateFirsts = candidateCounts = new int[0];
        candidateVisible = candidateActive = candidateIncludeOpaque = new boolean[0];
        candidateCoverage = new TextureAlphaCoverage[0];
        candidateBuildCount = 0;
    }
}
