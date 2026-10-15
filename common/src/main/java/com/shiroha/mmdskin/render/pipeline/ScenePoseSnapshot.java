package com.shiroha.mmdskin.render.pipeline;

import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import java.util.Arrays;

/** Exact scene assignments. The current native head setter ignores its worldSpace flag. */
public final class ScenePoseSnapshot implements NativeScenePort {
    private final NativeScenePort delegate;
    private int[] values = new int[32];
    private int size;

    public ScenePoseSnapshot(NativeScenePort delegate) { this.delegate = delegate; }

    private void add(int operation, float... fields) {
        if (size + fields.length + 1 > values.length) values = Arrays.copyOf(values, values.length * 2);
        values[size++] = operation;
        for (float field : fields) values[size++] = Float.floatToRawIntBits(field);
    }

    public boolean sameInputs(ScenePoseSnapshot other) {
        return other != null && size == other.size && Arrays.equals(values, 0, size, other.values, 0, size);
    }

    @Override public void setHeadAngle(long h, float x, float y, float z, boolean world) {
        add(1, x, y, z);
        if (delegate != null) delegate.setHeadAngle(h, x, y, z, world);
    }
    @Override public void setModelPositionAndYaw(long h, float x, float y, float z, float yaw) {
        add(2, x, y, z, yaw);
        if (delegate != null) delegate.setModelPositionAndYaw(h, x, y, z, yaw);
    }
    @Override public void setAutoBlinkEnabled(long h, boolean enabled) {
        add(3, enabled ? 1 : 0);
        if (delegate != null) delegate.setAutoBlinkEnabled(h, enabled);
    }
    @Override public void setEyeTrackingEnabled(long h, boolean enabled) {
        add(4, enabled ? 1 : 0);
        if (delegate != null) delegate.setEyeTrackingEnabled(h, enabled);
    }
    @Override public void setEyeMaxAngle(long h, float angle) {
        add(5, angle);
        if (delegate != null) delegate.setEyeMaxAngle(h, angle);
    }
    @Override public void setTailPhysicsOptions(long h, boolean lift, boolean boost) {
        add(6, lift ? 1 : 0, boost ? 1 : 0);
        if (delegate != null) delegate.setTailPhysicsOptions(h, lift, boost);
    }
    @Override public void setEyeAngle(long h, float x, float y) {
        add(7, x, y);
        if (delegate != null) delegate.setEyeAngle(h, x, y);
    }
}
