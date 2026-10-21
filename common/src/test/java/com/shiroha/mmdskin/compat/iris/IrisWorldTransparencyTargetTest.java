package com.shiroha.mmdskin.compat.iris;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

import static com.shiroha.mmdskin.compat.iris.IrisWorldTransparencyTarget.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisWorldTransparencyTargetTest {
    public static class Pipeline {
        Object before = Set.of(), after = Set.of();
        boolean fail;

        public Object getFlippedAfterPrepare() {
            if (fail) throw new IllegalStateException("pipeline rebuilding");
            return before;
        }

        public Object getFlippedAfterTranslucent() { return after; }
    }

    private IrisWorldTransparencyTarget.Result inspect(Object pipeline) throws Exception {
        return IrisWorldTransparencyTarget.inspectPipeline(pipeline, Pipeline.class,
                Pipeline.class.getMethod("getFlippedAfterPrepare"),
                Pipeline.class.getMethod("getFlippedAfterTranslucent"));
    }

    @Test void bothMainAndAltStorageCanRemainStable() throws Exception {
        var pipeline = new Pipeline();
        assertTrue(inspect(pipeline).stable());
        pipeline.before = Set.of(0, 4);
        pipeline.after = Set.of(0, 1);
        var result = inspect(pipeline);
        assertEquals(STABLE, result.status());
        assertTrue(result.before());
        assertTrue(result.after());
    }

    @Test void colortex0SwitchIsDistinctFromOtherBuffersSwitching() throws Exception {
        var pipeline = new Pipeline();
        pipeline.after = Set.of(4);
        assertEquals(STABLE, inspect(pipeline).status());
        pipeline.after = Set.of(0, 4);
        assertEquals(COLOR_BUFFER_SWITCHED, inspect(pipeline).status());
        assertFalse(inspect(pipeline).before());
        assertTrue(inspect(pipeline).after());
        pipeline.before = Set.of(0);
        pipeline.after = Set.of(4);
        assertEquals(COLOR_BUFFER_SWITCHED, inspect(pipeline).status());
    }

    @Test void missingAndWrongPipelineHaveDifferentReasons() throws Exception {
        assertEquals(NO_PIPELINE, inspect(null).status());
        var mismatch = inspect(new Object());
        assertEquals(PIPELINE_TYPE_MISMATCH, mismatch.status());
        assertEquals("java.lang.Object", mismatch.pipeline());
        assertTrue(mismatch.detail().contains(Pipeline.class.getName()));
    }

    @Test void invocationFailureShowsUnderlyingCauseAndCanRecover() throws Exception {
        var pipeline = new Pipeline();
        pipeline.fail = true;
        var failure = inspect(pipeline);
        assertEquals(API_READ_FAILED, failure.status());
        assertEquals("java.lang.IllegalStateException: pipeline rebuilding", failure.detail());
        assertNull(failure.before());
        pipeline.fail = false;
        assertTrue(inspect(pipeline).stable());
    }

    @Test void invalidReturnDoesNotPretendARealBufferSwitch() throws Exception {
        var pipeline = new Pipeline();
        pipeline.before = null;
        assertEquals(INVALID_FLIP_STATE, inspect(pipeline).status());
        pipeline.before = "not a set";
        assertEquals(INVALID_FLIP_STATE, inspect(pipeline).status());
    }

    @Test void nestedInvocationWrappersDoNotHideMissingApiReason() {
        assertEquals("java.lang.NoSuchMethodException: getFlippedAfterPrepare",
                IrisWorldTransparencyTarget.describe(new InvocationTargetException(
                        new InvocationTargetException(new NoSuchMethodException("getFlippedAfterPrepare")))));
    }
}
