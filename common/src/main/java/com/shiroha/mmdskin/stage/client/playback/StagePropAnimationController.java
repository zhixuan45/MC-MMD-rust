package com.shiroha.mmdskin.stage.client.playback;

import com.shiroha.mmdskin.asset.catalog.HeldPmxMotionFiles;
import com.shiroha.mmdskin.asset.catalog.PropTimelineFiles;
import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.stage.domain.model.StagePropAssignment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns prop VMD handles for the lifetime of a stage, independently of character animation layers. */
public final class StagePropAnimationController {
    private static final Logger LOGGER=LogManager.getLogger();
    private final NativeAnimationPort animations;
    private final Map<UUID,Session> sessions=new HashMap<>();

    private static final class Session {
        final StagePropAssignment assignment;
        float frame;
        ModelInstance attempted, model;
        Path attemptedDir;
        long attemptedHandle, modelHandle, animation;
        float maxFrame, appliedFrame=Float.NaN;
        PropTimeline timeline=PropTimeline.EMPTY;
        PropTimeline.State state=PropTimeline.State.DEFAULT;
        final java.util.Set<String> missingBones=new java.util.HashSet<>();
        com.shiroha.mmdskin.player.render.PropBoneParenting parenting;
        Session(StagePropAssignment assignment) { this.assignment=assignment; }
    }

    public StagePropAnimationController(NativeAnimationPort animations) { this.animations=Objects.requireNonNull(animations); }

    public synchronized void begin(UUID owner,StagePropAssignment assignment) {
        if (owner==null) return;
        stop(owner);
        if (assignment!=null && assignment.isValid()) sessions.put(owner,new Session(assignment));
    }

    public synchronized StagePropAssignment assignment(UUID owner) {
        Session session=sessions.get(owner);
        return session==null?null:session.assignment;
    }

    public synchronized PropTimeline.State state(UUID owner) {
        Session session=sessions.get(owner);
        return session==null?PropTimeline.State.DEFAULT:session.state;
    }

    public synchronized boolean applyBoneParents(UUID owner,long actor,long prop,org.joml.Matrix4f propToActor) {
        Session session=sessions.get(owner);
        if(session==null) return true;
        if(session.parenting==null && session.state.boneBindings().isEmpty()) return true;
        if(session.parenting==null) {
            var runtime=com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.get();
            session.parenting=new com.shiroha.mmdskin.player.render.PropBoneParenting(runtime,runtime);
        }
        try {
            return session.parenting.apply(actor,prop,propToActor,session.state);
        } catch (IllegalArgumentException invalid) {
            if(session.missingBones.add("bone-parent:"+invalid.getMessage())) {
                LOGGER.warn("[StageProp] Bone parenting failed for {}; prop hidden at this binding: {}",owner,invalid.getMessage());
            }
            return false;
        }
    }

    public synchronized void warnMissingBone(UUID owner,String bone) {
        Session session=sessions.get(owner);
        if (session!=null && session.missingBones.add(bone)) {
            LOGGER.warn("[StageProp] Timeline parent bone '{}' is missing for {}; prop is hidden at this binding",bone,owner);
        }
    }

    /** A model that finished loading late is immediately evaluated at the current stage frame. */
    public synchronized boolean bind(UUID owner,ModelInstance model,Path modelDir,String pmxFile) {
        Session session=sessions.get(owner);
        if (session==null || model==null || model.getModelHandle()==0 || !session.assignment.pmxFile().equals(pmxFile)) return false;
        long handle=model.getModelHandle();
        if (session.attempted==model && session.attemptedHandle==handle && modelDir.equals(session.attemptedDir)) return session.animation!=0;
        closeBinding(session);
        session.attempted=model; session.attemptedHandle=handle; session.attemptedDir=modelDir;
        session.timeline=PropTimeline.EMPTY;
        session.state=PropTimeline.State.DEFAULT;
        Path file=HeldPmxMotionFiles.resolve(modelDir,session.assignment.motionFile());
        if (file==null) {
            LOGGER.warn("[StageProp] Missing local prop motion: {} / {}",modelDir,session.assignment.motionFile());
            return false;
        }
        try {
            session.timeline=PropTimelineFiles.load(file,com.shiroha.mmdskin.asset.catalog.HeldPmxAssets.fileName(pmxFile));
            session.state=session.timeline.evaluate(session.frame);
        } catch (java.io.IOException failure) {
            LOGGER.warn("[StageProp] {}",failure.getMessage());
        }
        try {
            long animation=animations.loadAnimation(handle,file.toString());
            if (animation==0) return false;
            session.animation=animation;
            if (!animations.hasBoneData(animation) && !animations.hasMorphData(animation)) {
                closeBinding(session);
                return false;
            }
            session.model=model; session.modelHandle=handle; session.animation=animation;
            float maximum=animations.getMotionEndFrame(animation);
            if (!Float.isFinite(maximum) || maximum<0) maximum=animations.getAnimationMaxFrame(animation);
            session.maxFrame=Float.isFinite(maximum)?Math.max(0,maximum):0;
            model.changeAnim(animation,0);
            model.setLayerLoop(0,false);
            model.setLayerSpeed(0,0);
            model.resetPhysics();
            applyFrame(session);
            return true;
        } catch (RuntimeException failure) {
            closeBinding(session);
            LOGGER.warn("[StageProp] Failed to apply prop motion {}",file,failure);
            return false;
        }
    }

    public synchronized void seek(UUID owner,float frame) {
        if (!Float.isFinite(frame) || frame<0) return;
        Session session=sessions.get(owner);
        if (session==null) return;
        boolean rewind=frame<session.frame;
        session.frame=frame;
        PropTimeline.State state=session.timeline.evaluate(frame);
        boolean attachmentChanged=!Objects.equals(session.state.attachment(),state.attachment())
                || !session.state.boneBindings().equals(state.boneBindings());
        session.state=state;
        if (session.model!=null && session.model.getModelHandle()!=session.modelHandle) closeBinding(session);
        if ((rewind || attachmentChanged) && session.model!=null) session.model.resetPhysics();
        applyFrame(session);
    }

    private void applyFrame(Session session) {
        if (session.model==null || session.animation==0) return;
        float frame=Math.min(session.frame,session.maxFrame);
        if (Float.compare(frame,session.appliedFrame)==0) return;
        animations.seekLayer(session.modelHandle,0,frame);
        session.appliedFrame=frame;
    }

    public synchronized void stop(UUID owner) {
        Session session=sessions.remove(owner);
        if (session!=null) closeBinding(session);
    }

    public synchronized void clear() {
        for (UUID owner:java.util.List.copyOf(sessions.keySet())) stop(owner);
    }

    private void closeBinding(Session session) {
        ModelInstance model=session.model;
        long handle=session.modelHandle, animation=session.animation;
        session.model=null; session.animation=0; session.modelHandle=0; session.appliedFrame=Float.NaN;
        try {
            if (model!=null && model.getModelHandle()==handle) {
                if(session.parenting!=null) session.parenting.clear(handle);
                model.changeAnim(0,0);
                model.setLayerSpeed(0,1);
                model.setLayerLoop(0,true);
                model.resetPhysics();
            }
        } catch (RuntimeException failure) { LOGGER.warn("[StageProp] Could not restore prop animation state",failure); }
        finally {
            if (animation!=0) {
                try { animations.deleteAnimation(animation); }
                catch (RuntimeException failure) { LOGGER.warn("[StageProp] Could not release prop animation {}",animation,failure); }
            }
        }
    }
}
