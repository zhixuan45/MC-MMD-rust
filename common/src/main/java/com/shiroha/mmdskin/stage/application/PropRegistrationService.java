package com.shiroha.mmdskin.stage.application;

import com.shiroha.mmdskin.stage.domain.timeline.BoneParentMath;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.joml.Matrix4f;

import java.util.Objects;

/** Computes and previews one timeline bone registration without owning preview resources. */
public final class PropRegistrationService {
    @FunctionalInterface
    public interface Registrar {
        PropTimeline.BoneBinding register(int frame, PropTimeline baseline, String child, String target,
                                          PropTimeline.Offset offset, boolean keepWorld,
                                          PropTimeline.Offset placement);
    }

    public interface BindingPort {
        void setPhysicsEnabled(long model, boolean enabled);
        boolean evaluate(long model);
        void apply(long actor, long prop, Matrix4f propToActor, PropTimeline timeline,
                   int frame, PropTimeline.State state);
        Matrix4f[] pose(long model, String bone);
    }

    private final BindingPort bindings;

    public PropRegistrationService(BindingPort bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    public PropTimeline.BoneBinding register(long actor, long prop, Matrix4f propToActor,
                                             PropTimeline baseline, int frame, PropTimeline.State state,
                                             String child, String target, PropTimeline.Offset offset,
                                             boolean keepWorld) {
        return register(actor, prop, propToActor, baseline, frame, state, child, target, offset,
                keepWorld, null);
    }

    public PropTimeline.BoneBinding register(long actor, long prop, Matrix4f propToActor,
                                             PropTimeline baseline, int frame, PropTimeline.State state,
                                             String child, String target, PropTimeline.Offset offset,
                                             boolean keepWorld, PropTimeline.Offset placement) {
        Objects.requireNonNull(propToActor, "propToActor");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(state, "state");

        bindings.setPhysicsEnabled(actor, false);
        bindings.setPhysicsEnabled(prop, false);
        if (!bindings.evaluate(actor)) throw new IllegalStateException("Cannot evaluate character pose");

        bindings.apply(actor, prop, propToActor, baseline, frame, state);
        if (!bindings.evaluate(prop)) throw new IllegalStateException("Cannot evaluate prop pose");

        Matrix4f[] pose = bindings.pose(prop, child);
        Matrix4f targetMatrix = BoneParentMath.targetInProp(propToActor, bindings.pose(actor, target)[0]);
        PropTimeline previewTimeline = baseline;
        if (keepWorld) {
            var keys = new java.util.ArrayList<>(baseline.boneKeys());
            keys.add(new PropTimeline.Key<>(frame, new PropTimeline.BoneBinding(
                    child, target, PropTimeline.Offset.IDENTITY, PropTimeline.identityCompensation(),
                    false, false)));
            previewTimeline = new PropTimeline(
                    baseline.initial(), baseline.visibility(), baseline.attachments(), keys);
            bindings.apply(actor, prop, propToActor, previewTimeline, frame, state);
            if (!bindings.evaluate(prop)) throw new IllegalStateException("Cannot evaluate registered prop pose");
            Matrix4f[] registeredPose = bindings.pose(prop, child);
            registeredPose[0] = pose[0];
            pose = registeredPose;
        }

        PropTimeline.BoneBinding binding = BoneParentMath.registration(
                child, target, targetMatrix, pose, offset, keepWorld, placement);
        var currentBindings = new java.util.LinkedHashMap<>(state.boneBindings());
        currentBindings.put(child, binding);
        bindings.apply(actor, prop, propToActor, previewTimeline, frame,
                new PropTimeline.State(state.visible(), state.attachment(), currentBindings));
        return binding;
    }
}
