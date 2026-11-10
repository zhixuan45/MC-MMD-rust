package com.shiroha.mmdskin.stage.application;

import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropRegistrationServiceTest {
    @Test
    void registerRunsPreviewAndRegistrationSequenceWithKeepWorld() {
        FakeBindingPort port=new FakeBindingPort();
        var service=new PropRegistrationService(port);
        var placement=new PropTimeline.Offset(1,2,3,4,5,6);

        var binding=service.register(11,22,new Matrix4f(),PropTimeline.EMPTY,8,
                PropTimeline.State.DEFAULT,"fan","rightHand",PropTimeline.Offset.IDENTITY,
                true,placement);

        assertEquals(3,port.evaluations);
        assertEquals(3,port.applications);
        assertEquals(1,port.finalState.boneBindings().size());
        assertTrue(port.lastTimeline.boneKeys().size()==1);
        assertTrue(binding.keepWorld());
        assertSame(placement,binding.placement());
        assertSame(binding,port.finalState.boneBindings().get("fan"));
    }

    private static final class FakeBindingPort implements PropRegistrationService.BindingPort {
        private int evaluations;
        private int applications;
        private PropTimeline lastTimeline;
        private PropTimeline.State finalState;

        @Override public void setPhysicsEnabled(long model, boolean enabled) { }

        @Override public boolean evaluate(long model) {
            evaluations++;
            return true;
        }

        @Override public void apply(long actor, long prop, Matrix4f propToActor,
                                   PropTimeline timeline, int frame, PropTimeline.State state) {
            applications++;
            lastTimeline=timeline;
            finalState=state;
        }

        @Override public Matrix4f[] pose(long model, String bone) {
            return new Matrix4f[]{new Matrix4f(),new Matrix4f(),new Matrix4f()};
        }

    }
}
