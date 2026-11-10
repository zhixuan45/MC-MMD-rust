package com.shiroha.mmdskin.stage.client.playback;

import com.shiroha.mmdskin.asset.catalog.HeldPmxMotionFiles;
import com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration;
import com.shiroha.mmdskin.bridge.runtime.NativeAnimationPort;
import com.shiroha.mmdskin.bridge.runtime.PropMotionHandle;
import com.shiroha.mmdskin.bridge.runtime.PropBoneBindingAdapter;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.stage.domain.model.StagePropAssignment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.application.PropTimelineApplicationService;
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
    private final java.util.function.Consumer<ModelRequestKey> releaseModel;

    private static final class Session {
        final StagePropAssignment assignment;
        final String id=UUID.randomUUID().toString();
        final java.util.List<Path> actorMotions;
        ModelRequestKey requestedKey;
        float frame;
        ModelInstance attempted, model;
        Path attemptedDir;
        long attemptedHandle, modelHandle;
        PropMotionHandle motion;
        float maxFrame;
        PropTimeline timeline=PropTimeline.EMPTY;
        PropTimeline.State state=PropTimeline.State.DEFAULT;
        PropBoneBindingAdapter.ApplyResult bindResult=new PropBoneBindingAdapter.ApplyResult(
                PropBoneBindingAdapter.Status.PENDING,-1,"Prop model is not ready");
        final java.util.Set<String> missingBones=new java.util.HashSet<>();
        String warning;
        PropBoneBindingAdapter parenting;
        Session(StagePropAssignment assignment,java.util.List<Path> actorMotions) {
            this.assignment=assignment; this.actorMotions=java.util.List.copyOf(actorMotions);
        }
    }

    public StagePropAnimationController(NativeAnimationPort animations) { this(animations,key -> {}); }
    public StagePropAnimationController(NativeAnimationPort animations,java.util.function.Consumer<ModelRequestKey> releaseModel) {
        this.animations=Objects.requireNonNull(animations); this.releaseModel=Objects.requireNonNull(releaseModel);
    }

    public synchronized void begin(UUID owner,StagePropAssignment assignment) {
        begin(owner,assignment,java.util.List.of());
    }

    public synchronized void begin(UUID owner,StagePropAssignment assignment,java.util.List<Path> actorMotions) {
        if (owner==null) return;
        stop(owner);
        if (assignment!=null && assignment.isValid()) sessions.put(owner,new Session(assignment,actorMotions));
    }

    public synchronized ModelRequestKey requestKey(UUID owner,String modelName) {
        Session session=sessions.get(owner);
        if(session==null) return null;
        var key=ModelRequestKey.stageProp(owner,session.id,modelName,session.assignment.pmxFile());
        if(session.requestedKey!=null && !session.requestedKey.equals(key)) {
            closeBinding(session);
            releaseModel.accept(session.requestedKey);
            session.attempted=null;
            session.bindResult=new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.PENDING,-1,"Prop model is not ready");
        }
        session.requestedKey=key;
        return key;
    }

    @FunctionalInterface public interface CalibrationSource {
        PropTimelineCalibration.Signature capture(Path motion) throws java.io.IOException;
    }

    public synchronized StagePropAssignment assignment(UUID owner) {
        Session session=sessions.get(owner);
        return session==null?null:session.assignment;
    }

    public synchronized PropTimeline.State state(UUID owner) {
        Session session=sessions.get(owner);
        return session==null?PropTimeline.State.DEFAULT:session.state;
    }

    public synchronized boolean usesBoneParenting(UUID owner) {
        Session session=sessions.get(owner);
        return session!=null && !session.timeline.boneKeys().isEmpty();
    }

    /** Read once per load attempt, so render retries cannot flood the player's chat. */
    public synchronized String takeWarning(UUID owner) {
        Session session=sessions.get(owner);
        if(session==null) return null;
        String warning=session.warning;
        session.warning=null;
        return warning;
    }

    private void warn(Session session,String message) {
        session.warning=message;
        LOGGER.warn("[StageProp] {} / {}: {}",session.assignment.pmxFile(),session.assignment.motionFile(),message);
    }

    public synchronized PropBoneBindingAdapter.ApplyResult applyBoneParentsResult(UUID owner,long actor,long prop,org.joml.Matrix4f propToActor) {
        Session session=sessions.get(owner);
        if(session==null) return new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.PENDING,-1,"Stage prop session is not ready");
        if(session.model==null || session.modelHandle!=prop || session.model.getModelHandle()!=prop)
            return new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.PENDING,-1,"Prop model is not ready");
        if(session.parenting==null && session.state.boneBindings().isEmpty()
                && session.timeline.outsideParentWindows(session.frame).isEmpty())
            return new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.READY,0,null);
        if(session.parenting==null) {
            var runtime=com.shiroha.mmdskin.bridge.runtime.NativeRuntimeBridgeHolder.get();
            session.parenting=new PropBoneBindingAdapter(runtime,runtime);
        }
        PropBoneBindingAdapter.ApplyResult result=session.parenting.applyResult(actor,prop,propToActor,
                session.timeline,session.frame,session.state);
        if(result.status()==PropBoneBindingAdapter.Status.FAILED
                && session.missingBones.add("bone-parent:"+result.code()+":"+result.message())) {
            LOGGER.warn("[StageProp] Bone parenting failed for {}; prop hidden at this binding: {}",owner,result.message());
        }
        return result;
    }

    /** Compatibility visibility result for the existing renderer call site. */
    public synchronized boolean applyBoneParents(UUID owner,long actor,long prop,org.joml.Matrix4f propToActor) {
        return applyBoneParentsResult(owner,actor,prop,propToActor).status()!=PropBoneBindingAdapter.Status.FAILED;
    }

    public synchronized void warnMissingBone(UUID owner,String bone) {
        Session session=sessions.get(owner);
        if (session!=null && session.missingBones.add(bone)) {
            LOGGER.warn("[StageProp] Timeline parent bone '{}' is missing for {}; prop is hidden at this binding",bone,owner);
        }
    }

    /** Compatibility boolean API retained for existing callers. */
    public boolean bind(UUID owner,ModelInstance model,Path modelDir,String pmxFile) {
        return bind(owner,model,modelDir,pmxFile,null);
    }

    public boolean bind(UUID owner,ModelInstance model,Path modelDir,String pmxFile,CalibrationSource calibration) {
        return bindResult(owner,model,modelDir,pmxFile,calibration).ready();
    }

    /**
     * Reads timeline/calibration files outside the controller monitor, then rechecks session identity
     * before the native animation handle is attached to the model.
     */
    public PropBoneBindingAdapter.ApplyResult bindResult(UUID owner,ModelInstance model,Path modelDir,String pmxFile) {
        return bindResult(owner,model,modelDir,pmxFile,null);
    }

    public PropBoneBindingAdapter.ApplyResult bindResult(UUID owner,ModelInstance model,Path modelDir,
                                                          String pmxFile,CalibrationSource calibration) {
        final Session session;
        final long handle;
        final java.util.List<Path> actorMotions;
        synchronized(this) {
            session=sessions.get(owner);
            if(session==null) return pending("Stage prop session is not ready");
            if(model==null || model.getModelHandle()==0) return pending("Prop model is not ready");
            if(!session.assignment.pmxFile().equals(pmxFile)) return failed(-6,"Prop model does not match the stage assignment");
            handle=model.getModelHandle();
            if(session.attempted==model && session.attemptedHandle==handle && Objects.equals(modelDir,session.attemptedDir))
                return session.bindResult;
            closeBinding(session);
            session.warning=null;
            session.attempted=model; session.attemptedHandle=handle; session.attemptedDir=modelDir;
            session.bindResult=pending("Prop motion is loading");
            actorMotions=session.actorMotions;
        }

        Path motionFile=HeldPmxMotionFiles.resolve(modelDir,session.assignment.motionFile());
        if(motionFile==null) return failAttempt(owner,session,model,handle,
                "Missing local prop motion: "+session.assignment.motionFile(),-7);
        PropTimelineApplicationService.PlaybackResult playback=PropTimelineApplicationService.loadForPlayback(
                motionFile,com.shiroha.mmdskin.asset.catalog.HeldPmxAssets.fileName(pmxFile),
                calibration==null?null:calibration::capture,actorMotions);

        synchronized(this) {
            if(sessions.get(owner)!=session || session.attempted!=model || session.attemptedHandle!=handle
                    || model.getModelHandle()!=handle) return pending("Prop binding attempt was superseded");
            PropMotionHandle candidate;
            try {
                candidate=PropMotionHandle.open(animations,model,motionFile);
            } catch(RuntimeException failure) {
                return failAttemptLocked(session,"Failed to validate or load prop VMD: "+failure.getMessage(),-7);
            }
            session.motion=candidate;
            session.model=model; session.modelHandle=handle;
            session.maxFrame=candidate.endFrame();
            try {
                session.timeline=playback.timeline();
                session.state=session.timeline.evaluate(session.frame);
                if(playback.warning()!=null) warn(session,playback.warning());
                applyFrame(session);
                session.bindResult=ready();
                return session.bindResult;
            } catch(RuntimeException failure) {
                closeBinding(session);
                return failAttemptLocked(session,"Failed to apply prop VMD: "+failure.getMessage(),-7);
            }
        }
    }

    private PropBoneBindingAdapter.ApplyResult failAttempt(UUID owner,Session session,ModelInstance model,
                                                            long handle,String message,int code) {
        synchronized(this) {
            if(sessions.get(owner)!=session || session.attempted!=model || session.attemptedHandle!=handle)
                return pending("Prop binding attempt was superseded");
            return failAttemptLocked(session,message,code);
        }
    }

    private PropBoneBindingAdapter.ApplyResult failAttemptLocked(Session session,String message,int code) {
        session.timeline=PropTimeline.EMPTY;
        session.state=PropTimeline.State.DEFAULT;
        session.bindResult=failed(code,message);
        if(session.missingBones.add("bind:"+code+":"+message)) warn(session,message);
        return session.bindResult;
    }

    private static PropBoneBindingAdapter.ApplyResult ready() {
        return new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.READY,0,null);
    }
    private static PropBoneBindingAdapter.ApplyResult pending(String message) {
        return new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.PENDING,-1,message);
    }
    private static PropBoneBindingAdapter.ApplyResult failed(int code,String message) {
        return new PropBoneBindingAdapter.ApplyResult(PropBoneBindingAdapter.Status.FAILED,code,message);
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
        if (session.model==null || session.motion==null || session.motion.animation()==0) return;
        float frame=session.frame;
        session.motion.seek(frame);
    }

    public synchronized void stop(UUID owner) {
        Session session=sessions.remove(owner);
        if (session!=null) {
            try { closeBinding(session); }
            finally { if(session.requestedKey!=null) releaseModel.accept(session.requestedKey); }
        }
    }

    public synchronized void clear() {
        for (UUID owner:java.util.List.copyOf(sessions.keySet())) stop(owner);
    }

    private void closeBinding(Session session) {
        ModelInstance model=session.model;
        long handle=session.modelHandle;
        PropMotionHandle motion=session.motion; session.motion=null; session.model=null; session.modelHandle=0;
        session.timeline=PropTimeline.EMPTY;
        session.state=PropTimeline.State.DEFAULT;
        session.bindResult=pending("Prop model is not ready");
        try {
            if (model!=null && model.getModelHandle()==handle) {
                if(session.parenting!=null) session.parenting.clear(handle);
                if(motion!=null) motion.close();
                model.setLayerSpeed(0,1);
                model.setLayerLoop(0,true);
                model.resetPhysics();
            }
        } catch (RuntimeException failure) { LOGGER.warn("[StageProp] Could not restore prop animation state",failure); }
        finally {
            session.parenting=null;
            if (motion!=null) {
                try { motion.close(); }
                catch (RuntimeException failure) { LOGGER.warn("[StageProp] Could not release prop animation",failure); }
            }
        }
    }
}
