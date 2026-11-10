package com.shiroha.mmdskin.ui.selector.timeline;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.bridge.runtime.PropMotionHandle;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import java.nio.file.Path;

/** Preview wrapper around the shared prop VMD lifecycle. */
public final class PreviewMotionBinding implements AutoCloseable {
    private final PropMotionHandle motion;
    public PreviewMotionBinding(NativeAnimationPort animations,ModelInstance model,Path path) {
        motion=PropMotionHandle.open(animations,model,path);
    }
    public float endFrame() { return motion.endFrame(); }
    public void seek(int frame) { motion.seek(Math.max(0,frame)); }
    @Override public void close() { motion.close(); }
}
