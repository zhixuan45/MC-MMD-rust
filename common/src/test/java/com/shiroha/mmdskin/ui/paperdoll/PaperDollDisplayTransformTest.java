package com.shiroha.mmdskin.ui.paperdoll;

import com.shiroha.mmdskin.config.PaperDollRotationMode;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperDollDisplayTransformTest {
    private static final float EPSILON = .00001f;

    @Test void fixedFacingIgnoresPlayerYawAndPitchAndRetainsTheExistingDisplayAngle() {
        var target = new Quaternionf();
        assertSame(target, PaperDollDisplayTransform.rotation(PaperDollRotationMode.FIXED, 0, 0, target));
        Vector3f facing = target.transform(new Vector3f(0, 0, 1));
        assertTrue(facing.equals(new Vector3f(.34071865f, .08715574f, -.9361168f), EPSILON));
        for (float yaw : new float[]{-180, -45, 90, 360}) {
            for (float pitch : new float[]{-90, 0, 90}) {
                var rotated = PaperDollDisplayTransform.rotation(PaperDollRotationMode.FIXED, yaw, pitch, target);
                assertTrue(rotated.transform(new Vector3f(0, 0, 1)).equals(facing, EPSILON));
            }
        }
    }

    @Test void followModeStillRespondsToBodyYawAndViewPitch() {
        var rotation = new Quaternionf();
        PaperDollDisplayTransform.rotation(PaperDollRotationMode.FOLLOW_PLAYER, 180, 0, rotation);
        assertTrue(rotation.transform(new Vector3f(0, 0, 1)).equals(new Vector3f(0, 0, 1), EPSILON));
        PaperDollDisplayTransform.rotation(PaperDollRotationMode.FOLLOW_PLAYER, 0, 0, rotation);
        assertTrue(rotation.transform(new Vector3f(0, 0, 1)).equals(new Vector3f(0, 0, -1), EPSILON));
        PaperDollDisplayTransform.rotation(PaperDollRotationMode.FOLLOW_PLAYER, 180, 90, rotation);
        assertTrue(rotation.transform(new Vector3f(0, 0, 1)).equals(new Vector3f(0, -1, 0), EPSILON));
    }
}
