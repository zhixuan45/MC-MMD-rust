package com.shiroha.mmdskin.render.pipeline.transparency;

import com.shiroha.mmdskin.render.gl.DepthAttachment;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryStack;

/** Opt-in, bounded GPU readback for one normalized screen pixel every 120 frames. */
final class WorldTransparencyPixelDiagnostics {
    private WorldTransparencyPixelDiagnostics() {}

    static void trace(int frame, String stage, String model, int[] viewport,
                      int meta, int nodes, int capacity, int depthFbo, boolean snapshotReady) {
        String selected = System.getProperty("mmdskin.debugTransparencyPixel");
        if (selected == null || frame % 120 != 1 || viewport == null || meta == 0) return;
        String[] xy = selected.split(",");
        if (xy.length != 2) return;
        double x, y;
        try {
            x = Double.parseDouble(xy[0]); y = Double.parseDouble(xy[1]);
        } catch (NumberFormatException invalid) { return; }
        if (!(x >= 0 && x < 1 && y >= 0 && y < 1)) return;
        int px = (int) (x * viewport[2]), py = (int) (y * viewport[3]);
        int read = GL46C.glGetInteger(GL46C.GL_READ_FRAMEBUFFER_BINDING);
        int buffer = GL46C.glGetInteger(GL46C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int pack = GL46C.glGetInteger(GL46C.GL_PIXEL_PACK_BUFFER_BINDING);
        int skipPixels = GL46C.glGetInteger(GL46C.GL_PACK_SKIP_PIXELS);
        int skipRows = GL46C.glGetInteger(GL46C.GL_PACK_SKIP_ROWS);
        int rowLength = GL46C.glGetInteger(GL46C.GL_PACK_ROW_LENGTH);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL46C.glBindBuffer(GL46C.GL_PIXEL_PACK_BUFFER, 0);
            GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_PIXELS, 0);
            GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_ROWS, 0);
            GL46C.glPixelStorei(GL46C.GL_PACK_ROW_LENGTH, 0);
            var depth = DepthAttachment.inspect();
            var color = FramebufferColorTarget.capture();
            var value = stack.mallocFloat(1);
            var rgba = stack.mallocFloat(4);
            String currentColor = "unavailable";
            float current = Float.NaN;
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER,
                    GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING));
            if (color != null) {
                int readBuffer = GL46C.glGetInteger(GL46C.GL_READ_BUFFER);
                try {
                    GL46C.glReadBuffer(GL46C.GL_COLOR_ATTACHMENT0);
                    GL46C.glReadPixels(px + viewport[0], py + viewport[1], 1, 1,
                            GL46C.GL_RGBA, GL46C.GL_FLOAT, rgba);
                    currentColor = rgba.get(0) + "," + rgba.get(1) + "," + rgba.get(2) + "," + rgba.get(3);
                } finally { GL46C.glReadBuffer(readBuffer); }
            }
            if (depth != null && depth.samples() == 0 && depth.contains(viewport)) {
                GL46C.glReadPixels(px + viewport[0], py + viewport[1], 1, 1,
                        GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, value);
                current = value.get(0);
            }
            float snapshot = Float.NaN;
            if (snapshotReady) {
                GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, depthFbo);
                GL46C.glReadPixels(px, py, 1, 1, GL46C.GL_DEPTH_COMPONENT, GL46C.GL_FLOAT, value);
                snapshot = value.get(0);
            }
            GL46C.glMemoryBarrier(GL46C.GL_SHADER_STORAGE_BARRIER_BIT | GL46C.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, meta);
            var header = stack.mallocInt(4);
            GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, 0, header);
            var entry = stack.mallocInt(7);
            GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER,
                    16L + ((long) py * viewport[2] + px) * 28, entry);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, nodes);
            var node = stack.mallocInt(4);
            StringBuilder fragments = new StringBuilder();
            int id = entry.get(0);
            for (int i = 0; id != 0 && i < 8; i++) {
                if (id < 1 || id > capacity) { fragments.append("invalid:").append(id); break; }
                GL46C.glGetBufferSubData(GL46C.GL_SHADER_STORAGE_BUFFER, (id - 1L) * 16, node);
                float z = Float.intBitsToFloat(node.get(2));
                fragments.append(id).append(":z=").append(z).append(",visible=").append(z <= snapshot)
                        .append(",rgba=").append(Float.float16ToFloat((short) node.get(0)))
                        .append(',').append(Float.float16ToFloat((short) (node.get(0) >>> 16)))
                        .append(',').append(Float.float16ToFloat((short) node.get(1)))
                        .append(',').append(Float.float16ToFloat((short) (node.get(1) >>> 16))).append(';');
                id = node.get(3);
            }
            LogManager.getLogger().info("[MMD transparency pixel] frame={} stage={} model={} pixel={},{}"
                            + " drawFbo={} color={} rgba={} depth={} currentZ={} snapshotZ={} allocated={} overflow={}"
                            + " head={} nodes(first8)={}", frame, stage, model, px, py,
                    GL46C.glGetInteger(GL46C.GL_DRAW_FRAMEBUFFER_BINDING), color, currentColor,
                    depth, current, snapshot, Integer.toUnsignedLong(header.get(0)),
                    Integer.toUnsignedLong(header.get(1)), entry.get(0), fragments);
        } finally {
            GL46C.glBindFramebuffer(GL46C.GL_READ_FRAMEBUFFER, read);
            GL46C.glBindBuffer(GL46C.GL_SHADER_STORAGE_BUFFER, buffer);
            GL46C.glBindBuffer(GL46C.GL_PIXEL_PACK_BUFFER, pack);
            GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_PIXELS, skipPixels);
            GL46C.glPixelStorei(GL46C.GL_PACK_SKIP_ROWS, skipRows);
            GL46C.glPixelStorei(GL46C.GL_PACK_ROW_LENGTH, rowLength);
        }
    }
}
