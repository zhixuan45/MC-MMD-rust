package com.shiroha.mmdskin.render.backend.opengl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenGlPaperDollPoseReuseTest {
    @Test void thirdPersonCanReuseTheFullBodyIndexBuffer() {
        var model = new OpenGlModelInstance();

        assertTrue(model.supportsPaperDollPoseReuse(false));
    }

    @Test void firstPersonReuseRequiresTheDedicatedClippedIndexBuffer() {
        var model = new OpenGlModelInstance();

        assertFalse(model.supportsPaperDollPoseReuse(true));
        model.firstPersonIndexBufferObject = 7;
        assertTrue(model.supportsPaperDollPoseReuse(true));
    }

    @Test void cpuModelsCanJoinTheWorldEvaluationBatch() {
        assertTrue(new OpenGlModelInstance().supportsParallelEvaluation());
    }
}
