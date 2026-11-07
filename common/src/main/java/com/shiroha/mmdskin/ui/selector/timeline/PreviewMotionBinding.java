package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.model.runtime.ModelInstance;

import java.nio.file.Path;

/** Owns one preview-only VMD handle and never borrows a player's animation layer. */
public final class PreviewMotionBinding implements AutoCloseable {
    private final NativeAnimationPort animations;
    private final ModelInstance model;
    private final long modelHandle;
    private long animation;
    private float endFrame, appliedFrame = Float.NaN;

    public PreviewMotionBinding(NativeAnimationPort animations, ModelInstance model, Path motion) {
        this.animations = animations;
        this.model = model;
        modelHandle = model.getModelHandle();
        if (modelHandle == 0) throw new IllegalArgumentException("Preview model is not ready");
        try {
            if (motion != null) {
                animation = animations.loadAnimation(modelHandle,motion.toString());
                if (animation == 0 || (!animations.hasBoneData(animation) && !animations.hasMorphData(animation))) {
                    throw new IllegalArgumentException("No bone or morph animation in " + motion.getFileName());
                }
                endFrame = animations.getMotionEndFrame(animation);
                if (!Float.isFinite(endFrame) || endFrame < 0) endFrame = animations.getAnimationMaxFrame(animation);
                if (!Float.isFinite(endFrame) || endFrame < 0 || endFrame > Integer.MAX_VALUE - 1) {
                    throw new IllegalArgumentException("Invalid animation duration");
                }
            }
            for (int layer = 0; layer < 3; layer++) model.changeAnim(layer == 0 ? animation : 0,layer);
            model.setLayerLoop(0,false);
            model.setLayerSpeed(0,0);
            model.resetPhysics();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public float endFrame() { return endFrame; }

    public void seek(int frame) {
        if (model.getModelHandle() != modelHandle) throw new IllegalStateException("Preview model was released");
        if (animation == 0) return;
        float target = Math.min(frame,endFrame);
        if (Float.compare(target,appliedFrame) != 0) {
            animations.seekLayer(modelHandle,0,target);
            appliedFrame = target;
        }
    }

    @Override public void close() {
        if (animation == 0) return;
        long handle = animation;
        animation = 0;
        try {
            if (model.getModelHandle() == modelHandle) model.changeAnim(0,0);
        } finally {
            animations.deleteAnimation(handle);
        }
    }
}
