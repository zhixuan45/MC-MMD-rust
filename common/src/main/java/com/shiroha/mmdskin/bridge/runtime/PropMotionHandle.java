package com.shiroha.mmdskin.bridge.runtime;

import com.shiroha.mmdskin.model.runtime.ModelInstance;
import java.nio.file.Path;
import java.util.Objects;

/** Owns one model's VMD handle and its layer-zero playback lifecycle. */
public final class PropMotionHandle implements AutoCloseable {
    private final NativeAnimationPort animations;
    private final ModelInstance model;
    private final long modelHandle;
    private final long animation;
    private final float endFrame;
    private float appliedFrame=Float.NaN;
    private boolean closed;

    private PropMotionHandle(NativeAnimationPort animations,ModelInstance model,long modelHandle,long animation,float endFrame) {
        this.animations=animations; this.model=model; this.modelHandle=modelHandle; this.animation=animation; this.endFrame=endFrame;
    }
    public static PropMotionHandle open(NativeAnimationPort animations,ModelInstance model,Path motion) {
        Objects.requireNonNull(animations); Objects.requireNonNull(model);
        long modelHandle=model.getModelHandle();
        if(modelHandle==0) throw new IllegalArgumentException("Motion model is not ready");
        long animation=0;
        boolean layerZeroTouched=false;
        try {
            if(motion!=null) {
                animation=animations.loadAnimation(modelHandle,motion.toString());
                if(animation==0 || (!animations.hasBoneData(animation) && !animations.hasMorphData(animation)))
                    throw new IllegalArgumentException("No bone or morph animation in "+motion.getFileName());
            }
            float end=0;
            if(animation!=0) {
                end=animations.getMotionEndFrame(animation);
                if(!Float.isFinite(end)||end<0) end=animations.getAnimationMaxFrame(animation);
                if(!Float.isFinite(end)||end<0||end>Integer.MAX_VALUE-1) throw new IllegalArgumentException("Invalid animation duration");
            }
            layerZeroTouched=true;
            if(model.getModelHandle()!=modelHandle) throw new IllegalStateException("Motion model was released during load");
            model.changeAnim(animation,0);
            model.changeAnim(0,1); model.changeAnim(0,2);
            model.setLayerLoop(0,false); model.setLayerSpeed(0,0); model.resetPhysics();
            return new PropMotionHandle(animations,model,modelHandle,animation,end);
        } catch(RuntimeException failure) {
            try {
                if(layerZeroTouched && model.getModelHandle()==modelHandle) restoreAfterFailedOpen(model);
            } catch(RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            if(animation!=0) {
                try { animations.deleteAnimation(animation); }
                catch(RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
            }
            throw failure;
        }
    }
    private static void restoreAfterFailedOpen(ModelInstance model) {
        restoreLayers(model);
        try { model.resetPhysics(); } catch(RuntimeException ignored) { }
    }
    private static void restoreLayers(ModelInstance model) {
        try { model.changeAnim(0,0); } catch(RuntimeException ignored) { }
        try { model.setLayerSpeed(0,1); } catch(RuntimeException ignored) { }
        try { model.setLayerLoop(0,true); } catch(RuntimeException ignored) { }
        try { model.changeAnim(0,1); } catch(RuntimeException ignored) { }
        try { model.changeAnim(0,2); } catch(RuntimeException ignored) { }
    }
    public long animation() { return animation; }
    public long modelHandle() { return modelHandle; }
    public float endFrame() { return endFrame; }
    public void seek(float frame) {
        if(closed || model.getModelHandle()!=modelHandle) throw new IllegalStateException("Motion model was released");
        if(animation==0) return;
        if(Float.compare(frame,appliedFrame)!=0) { animations.seekLayer(modelHandle,0,frame); appliedFrame=frame; }
    }
    @Override public void close() {
        if(closed) return; closed=true;
        try {
            if(animation!=0 && model.getModelHandle()==modelHandle) restoreLayers(model);
        } finally { if(animation!=0) animations.deleteAnimation(animation); }
    }
}
