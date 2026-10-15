package com.shiroha.mmdskin.bridge.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePoseInputsTest {
    @Test void animationTransitionsInvalidateOnlyTheirLayerAssignments() {
        long h = -10005;
        try {
            NativePoseInputs.assign(h, "weight.0", NativePoseInputs.bits(1));
            NativePoseInputs.assign(h, "loop.0", 1);
            NativePoseInputs.assign(h, "weight.1", NativePoseInputs.bits(1));
            NativePoseInputs.animationChanged(h, 0);
            long epoch = NativePoseInputs.revision(h);
            NativePoseInputs.assign(h, "weight.1", NativePoseInputs.bits(1));
            assertEquals(epoch, NativePoseInputs.revision(h));
            NativePoseInputs.assign(h, "weight.0", NativePoseInputs.bits(1));
            assertNotEquals(epoch, NativePoseInputs.revision(h));
            epoch = NativePoseInputs.revision(h);
            NativePoseInputs.assign(h, "loop.0", 1);
            assertNotEquals(epoch, NativePoseInputs.revision(h));
        } finally { NativePoseInputs.forget(h); }
    }

    @Test void repeatedAssignmentsPreserveEpochButExactInputChangesInvalidate() {
        long h = -10001;
        try {
            NativePoseInputs.assign(h, "head", NativePoseInputs.bits(0.0f));
            long epoch = NativePoseInputs.revision(h);
            NativePoseInputs.assign(h, "head", NativePoseInputs.bits(0.0f));
            assertEquals(epoch, NativePoseInputs.revision(h));
            NativePoseInputs.assign(h, "head", NativePoseInputs.bits(-0.0f));
            assertNotEquals(epoch, NativePoseInputs.revision(h));
            epoch = NativePoseInputs.revision(h);
            NativePoseInputs.changed(h);
            assertNotEquals(epoch, NativePoseInputs.revision(h));
        } finally { NativePoseInputs.forget(h); }
    }

    @Test void configurationInvalidatesExistingHandlesAndMutationsRemainIndependent() {
        long a = -10002, b = -10003;
        try {
            long ea = NativePoseInputs.revision(a), eb = NativePoseInputs.revision(b);
            NativePoseInputs.changed(a);
            assertEquals(eb, NativePoseInputs.revision(b));
            assertNotEquals(ea, NativePoseInputs.revision(a));
            ea = NativePoseInputs.revision(a);
            NativePoseInputs.configurationChanged();
            assertNotEquals(ea, NativePoseInputs.revision(a));
            assertNotEquals(eb, NativePoseInputs.revision(b));
        } finally { NativePoseInputs.forget(a); NativePoseInputs.forget(b); }
    }

    @Test void externalPoseIsConservativeUntilHandleIsReleased() {
        long h = -10004;
        try {
            assertFalse(NativePoseInputs.hasExternalPose(h));
            NativePoseInputs.externalPose(h);
            assertTrue(NativePoseInputs.hasExternalPose(h));
            NativePoseInputs.changed(h);
            assertTrue(NativePoseInputs.hasExternalPose(h));
            NativePoseInputs.forget(h);
            assertFalse(NativePoseInputs.hasExternalPose(h));
        } finally { NativePoseInputs.forget(h); }
    }
}
