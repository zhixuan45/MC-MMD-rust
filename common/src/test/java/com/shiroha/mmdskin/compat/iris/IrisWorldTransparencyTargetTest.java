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
        int binds;

        public Object getFlippedAfterPrepare() {
            if (fail) throw new IllegalStateException("pipeline rebuilding");
            return before;
        }

        public Object getFlippedAfterTranslucent() { return after; }
        public void bindDefault() {
            if (fail) throw new IllegalStateException("target destroyed");
            binds++;
        }
    }

    public static class OculusPipeline {
        private final RenderTargets renderTargets = new RenderTargets();
        public boolean isBeforeTranslucent = true;
        public Object getFlippedAfterPrepare() { return Set.of(); }
        public Object getFlippedAfterTranslucent() { return Set.of(0); }
    }

    public static class RenderTargets {
        int mainCount, altCount;
        final Framebuffer main = new Framebuffer(), alt = new Framebuffer();

        public Framebuffer createFramebufferWritingToMain(int[] outputs) {
            assertArrayEquals(new int[]{0}, outputs);
            mainCount++;
            return main;
        }

        public Framebuffer createFramebufferWritingToAlt(int[] outputs) {
            assertArrayEquals(new int[]{0}, outputs);
            altCount++;
            return alt;
        }
    }

    public static class Framebuffer {
        int binds;
        public void bind() { binds++; }
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

    @Test void colorBindingReportsFailureAndCanRecover() throws Exception {
        var pipeline = new Pipeline();
        var bind = Pipeline.class.getMethod("bindDefault");
        assertTrue(IrisWorldTransparencyTarget.bindTarget(pipeline, bind));
        assertEquals(1, pipeline.binds);
        pipeline.fail = true;
        assertFalse(IrisWorldTransparencyTarget.bindTarget(pipeline, bind));
        pipeline.fail = false;
        assertTrue(IrisWorldTransparencyTarget.bindTarget(pipeline, bind));
        assertEquals(2, pipeline.binds);
    }

    @Test void oculusPipelineSupportsStageFramebufferWithoutBindDefault() {
        assertNotNull(IrisWorldTransparencyTarget.findBindDefault(Pipeline.class));
        assertNull(IrisWorldTransparencyTarget.findBindDefault(OculusPipeline.class));
        assertFalse(IrisWorldTransparencyTarget.usesAltFramebuffer(Set.of()));
        assertFalse(IrisWorldTransparencyTarget.usesAltFramebuffer(Set.of(4)));
        assertTrue(IrisWorldTransparencyTarget.usesAltFramebuffer(Set.of(0)));
    }

    @Test void bindsStageColorAndReusesOwnedTargetsAcrossDimensionSwitchAndReset() throws Exception {
        var first = new OculusPipeline();
        var second = new OculusPipeline();
        var api = IrisWorldTransparencyTarget.FramebufferAccess.resolve(OculusPipeline.class);
        assertNotNull(api);
        var before = OculusPipeline.class.getMethod("getFlippedAfterPrepare");
        var after = OculusPipeline.class.getMethod("getFlippedAfterTranslucent");
        api.bind(first, before, after);
        first.isBeforeTranslucent = false;
        api.bind(first, before, after);
        api.bind(second, before, after);
        IrisWorldTransparencyTarget.reset();
        first.isBeforeTranslucent = true;
        api.bind(first, before, after);
        first.isBeforeTranslucent = false;
        api.bind(first, before, after);
        assertEquals(1, first.renderTargets.mainCount);
        assertEquals(1, first.renderTargets.altCount);
        assertEquals(2, first.renderTargets.main.binds);
        assertEquals(2, first.renderTargets.alt.binds);
        assertEquals(1, second.renderTargets.main.binds);
        assertEquals(0, second.renderTargets.altCount);
    }
}
