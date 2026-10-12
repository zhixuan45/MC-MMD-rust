package com.shiroha.mmdskin.render.material;

import org.junit.jupiter.api.Test;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import static org.junit.jupiter.api.Assertions.*;

class EyeBoneMaskTest {
    @Test void includesReturnedEyeBonesAndExcludesScleraBoundToHead() {
        String[] names = {"頭", "左目", "右目戻", "左目瞼", "eyebrow.L", "LeftEye"};
        var indices = IntBuffer.wrap(new int[]{0,-1,-1,-1, 1,2,-1,-1, 3,4,-1,-1, 5,0,-1,-1});
        var weights = FloatBuffer.wrap(new float[]{1,0,0,0, .4f,.6f,0,0, .5f,.5f,0,0, .75f,.25f,0,0});
        assertArrayEquals(new float[]{0,1,0,.75f}, EyeBoneMask.build(names, indices, weights, 4));
        assertEquals(0, indices.position());
        assertEquals(0, weights.position());
    }

    @Test void rejectsInvalidWeightsAndTruncatedBuffers() {
        String[] names = {"右目"};
        assertArrayEquals(new float[]{0}, EyeBoneMask.build(names,
                IntBuffer.wrap(new int[]{-1,100,0,0}),
                FloatBuffer.wrap(new float[]{1,1,Float.NaN,-1}), 1));
        assertThrows(IllegalArgumentException.class, () -> EyeBoneMask.build(names,
                IntBuffer.wrap(new int[]{0}), FloatBuffer.wrap(new float[]{1}), 1));
        assertFalse(EyeBoneMask.isEyeBone("頭"));
        assertFalse(EyeBoneMask.isEyeBone("eye_lid"));
        assertFalse(EyeBoneMask.isEyeBone("eyewear"));
        assertFalse(EyeBoneMask.isEyeBone("eye_shadow"));
        assertTrue(EyeBoneMask.isEyeBone("右目戻"));
        assertTrue(EyeBoneMask.isEyeBone("J_Adj_L_FaceEye"));
    }
}
