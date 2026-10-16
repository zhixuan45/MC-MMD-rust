package com.shiroha.mmdskin.ui.paperdoll;

import com.shiroha.mmdskin.config.PaperDollRotationMode;
import org.joml.Quaternionf;

/** Display-only root rotation; the evaluated local bone pose is left intact. */
final class PaperDollDisplayTransform {
    private static final float RADIANS = (float) Math.PI / 180.0f;

    private PaperDollDisplayTransform() { }

    static Quaternionf rotation(PaperDollRotationMode mode, float bodyYaw, float pitch, Quaternionf target) {
        target.identity().rotateZ((float) Math.PI);
        if (mode == PaperDollRotationMode.FIXED) {
            return target.rotateY(200.0f * RADIANS).rotateX(5.0f * RADIANS);
        }
        return target.rotateY((180.0f - bodyYaw) * RADIANS).rotateX(-pitch * RADIANS);
    }
}
