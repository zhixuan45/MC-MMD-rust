package com.shiroha.mmdskin.render.material;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.compat.iris.IrisCompat;

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
 * are reused; GPU compute positions are read once per animation revision. This is centroid sorting,
 * not order-independent handling of intersecting triangles.
 */
public final class TranslucentDrawOrder implements AutoCloseable {
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
            long uvRevision) {
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
        return indexBuffer;
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
        int triangles = 0;
        MaterialRenderState state = new MaterialRenderState();
        if (batches.length < count) {
            batches = new int[count];
            includeOpaque = new boolean[count];
        }
        var batchIds = new HashMap<Batch, Integer>();
        int program = GL46C.glGetInteger(GL46C.GL_CURRENT_PROGRAM);
        boolean specular = GL46C.glGetUniformLocation(program, "MaterialSpecular") >= 0;
        int eyeLocation = GL46C.glGetUniformLocation(program, "EyeEmission");
        boolean eye = eyeLocation >= 0 && GL46C.glGetUniformf(program, eyeLocation) > 0;
        boolean splitPixels = GL46C.glGetUniformLocation(program, "AlphaPass") >= 0;
        boolean sphere = GL46C.glGetUniformLocation(program, "SphereMode") >= 0;
        // Pack entity shaders may deform vertices or derive alpha beyond the diffuse atlas.
        // Own Toon programs have the known MaterialColor/texture-alpha formula.
        boolean packEntity =
                IrisCompat.isIrisProgram(RenderSystem.getShader())
                        && GL46C.glGetUniformLocation(program, "MaterialColor") < 0;
        boolean coverageSafe = splitPixels && !packEntity;
        for (int s = 0; s < count; s++) {
            batches[s] = -1;
            if (!includes(source, s, materials, morphs, alphaResolver, state)) continue;
            triangles += source.getInt(s * 20 + 8) / 3;
            var material = materials[source.getInt(s * 20)];
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
            includeOpaque[s] = !splitPixels || state.alpha < MaterialRenderState.OPAQUE_ALPHA;
        }
        if (triangles == 0) {
            candidateCount = candidateVertexCount = drawCount = 0;
            candidateValid = false;
            return false;
        }
        int original = GL46C.glGetInteger(GL46C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int previousRead = GL46C.glGetInteger(GL46C.GL_COPY_READ_BUFFER_BINDING);
        try {
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, original);
            int bytes =
                    GL46C.glGetBufferParameteri(GL46C.GL_COPY_READ_BUFFER, GL46C.GL_BUFFER_SIZE);
            long revision = inputs.dynamicIndices() ? inputs.geometryRevision() : 0;
            boolean indicesReloaded = original != sourceBuffer
                    || bytes != sourceBytes
                    || indexSize != sourceIndexSize
                    || inputs.dynamicIndices()
                    || revision != indexRevision;
            if (indicesReloaded) {
                ByteBuffer data = MemoryUtil.memAlloc(bytes);
                try {
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, data);
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
                    MemoryUtil.memFree(data);
                }
                sourceBuffer = original;
                sourceBytes = bytes;
                sourceIndexSize = indexSize;
                indexRevision = revision;
                candidateValid = false;
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
                    GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
                    GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, inputs.positionBuffer());
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, gpuPositions);
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
                    GL46C.glMemoryBarrier(GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
                    GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, inputs.uvBuffer());
                    GL46C.glGetBufferSubData(GL46C.GL_COPY_READ_BUFFER, 0, gpuUvs);
                    uvRevision = inputs.uvRevision();
                    uvSource = inputs.uvBuffer();
                }
                uvs = gpuUvs;
            }
            if (!candidateCacheMatches(inputs, source, count, bytes, indexSize, coverageSafe, batches, includeOpaque, materials)) {
                buildCandidates(inputs, source, count, materials,
                        batches, includeOpaque, coverageSafe, uvs, bytes);
            }
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
            int used = 0;
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
            // 仅并行处理 CPU 深度键；OpenGL 操作仍在渲染线程。
            Arrays.parallelSort(keys, 0, used);
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
            if (indexBuffer == 0) indexBuffer = GL46C.glGenBuffers();
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GL46C.glBufferData(GL46C.GL_ELEMENT_ARRAY_BUFFER, sortedIndices, GL46C.GL_STREAM_DRAW);
            return true;
        } finally {
            GL46C.glBindBuffer(GL46C.GL_ELEMENT_ARRAY_BUFFER, original);
            GL46C.glBindBuffer(GL46C.GL_COPY_READ_BUFFER, previousRead);
        }
    }

    private static boolean includes(
            ByteBuffer source,
            int s,
            ModelMaterial[] materials,
            ByteBuffer morphs,
            SubMeshDrawHelper.AlphaResolver resolver,
            MaterialRenderState state) {
        int b = s * 20, id = source.getInt(b);
        if (source.get(b + 16) == 0
                || source.getInt(b + 8) <= 0
                || id < 0
                || id >= materials.length) return false;
        ModelMaterial material = materials[id];
        state.resolve(material, morphs, id, resolver.resolve(id, source.getFloat(b + 12)));
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
        return sortedIndices == null ? 0 : sortedIndices.limit();
    }

    public long ramBytes() {
        return (long) sourceIndices.length * 4
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
