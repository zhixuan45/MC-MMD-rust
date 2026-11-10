import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration;
import com.shiroha.mmdskin.asset.catalog.PropTimelineFiles;
import com.shiroha.mmdskin.bridge.runtime.*;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.player.render.ItemRenderHelper;
import com.shiroha.mmdskin.player.render.PropBoneParenting;
import com.shiroha.mmdskin.stage.client.playback.StagePropAnimationController;
import com.shiroha.mmdskin.stage.domain.model.StagePropAssignment;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline.*;
import com.shiroha.mmdskin.ui.selector.timeline.PreviewMotionBinding;
import org.joml.Matrix4f;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Production stage/preview orchestration, real animation/pose calls; no rendering shims. */
public final class Probe {
    private static final NativeFunc API=new NativeFunc();
    private static final UUID OWNER=UUID.randomUUID();
    private static final float ACTOR_SCALE=.09f, PROP_SCALE=.12f;
    private static Path dir;
    private static NativeRuntimePort runtime;
    private static float maxError;
    private static int frameScale;
    private static int f(int frame) { return frame*frameScale; }

    private static void check(boolean valid,String message) {
        if(!valid) throw new AssertionError(message);
    }
    private static void near(Matrix4f expected,Matrix4f actual,String message) {
        float[] a=expected.get(new float[16]),b=actual.get(new float[16]);
        for(int i=0;i<16;i++) maxError=Math.max(maxError,Math.abs(a[i]-b[i]));
        check(expected.equals(actual,.0004f),message+"\nexpected "+expected+"\nactual "+actual);
    }
    private static final NativeAnimationPort ANIMATIONS=(NativeAnimationPort)Proxy.newProxyInstance(
            Probe.class.getClassLoader(),new Class<?>[]{NativeAnimationPort.class},(p,m,a) -> switch(m.getName()) {
                case "loadAnimation" -> API.LoadAnimation((long)a[0],(String)a[1]);
                case "deleteAnimation" -> { API.DeleteAnimation((long)a[0]); yield null; }
                case "hasBoneData" -> API.HasBoneData((long)a[0]);
                case "hasMorphData" -> API.HasMorphData((long)a[0]);
                case "getAnimationMaxFrame" -> API.GetAnimMaxFrame((long)a[0]);
                case "getMotionEndFrame" -> API.GetMotionEndFrame((long)a[0]);
                case "seekLayer" -> { API.SeekLayer((long)a[0],(long)a[1],(float)a[2]); yield null; }
                default -> throw new AssertionError(m.getName());
            });
    private static ModelInstance model(long handle) {
        return (ModelInstance)Proxy.newProxyInstance(Probe.class.getClassLoader(),new Class<?>[]{ModelInstance.class},
                (p,m,a) -> switch(m.getName()) {
                    case "getModelHandle" -> handle;
                    case "changeAnim" -> { API.ChangeModelAnim(handle,(long)a[0],(long)a[1]); yield null; }
                    case "setLayerLoop" -> { API.SetLayerLoop(handle,(long)a[0],(boolean)a[1]); yield null; }
                    case "setLayerSpeed" -> { API.SetLayerSpeed(handle,(long)a[0],(float)a[1]); yield null; }
                    case "resetPhysics" -> { API.ResetPhysics(handle); yield null; }
                    default -> throw new AssertionError(m.getName());
                });
    }
    private static long load(String file) {
        long model=API.LoadModelPMX(dir.resolve(file).toString(),dir.toString(),1);
        check(model!=0,"PMX load "+file);
        API.SetPhysicsEnabled(model,false);
        return model;
    }
    private static void evaluate(long model,boolean cpu) {
        // A nonzero render delta verifies externally sought layers do not also advance by elapsed time.
        if(cpu) API.UpdateModel(model,1f/60);
        else API.UpdateAnimationOnly(model,1f/60);
    }
    private static Matrix4f outer(PropTimeline.State state,ModelConfigData config,long actor,PropBoneParenting parenting) {
        var attachment=state.attachment();
        boolean independent=ItemRenderHelper.usesIndependentPropSpace(true,attachment);
        return ItemRenderHelper.propRenderTransform(independent?null:parenting.pose(actor,attachment.bone())[0],
                config,attachment==null?Offset.IDENTITY:attachment.offset(),ACTOR_SCALE,independent).scale(PROP_SCALE);
    }
    private static PropTimelineCalibration.Signature signature(ModelConfigData config,Path reference) throws java.io.IOException {
        return PropTimelineCalibration.capture(dir.resolve("actor.pmx"),dir.resolve("fan.pmx"),
                dir.resolve("prop.vmd"),reference,config);
    }
    private static Matrix4f pose(PropBoneParenting parenting,long model) {
        return parenting.pose(model,"Center")[0];
    }
    private static void run(boolean cpu,String mode,boolean explicit) throws Exception {
        long actor=load("actor.pmx"),previewActor=load("actor.pmx"),previewProp=load("fan.pmx"),prop=load("fan.pmx");
        long actorMotion=API.LoadAnimation(actor,dir.resolve("actor.vmd").toString());
        var parenting=new PropBoneParenting(runtime,runtime);
        var controller=new StagePropAnimationController(ANIMATIONS);
        ModelConfigData config=new ModelConfigData();
        config.heldItemScale=.8f; config.heldPmxScale=1.5f;
        config.heldPmxOffsetX=2; config.heldPmxRotY=35;
        var saved=signature(config,dir.resolve("actor.vmd"));
        try(var actorPreview=new PreviewMotionBinding(ANIMATIONS,model(previewActor),dir.resolve("actor.vmd"));
            var propPreview=new PreviewMotionBinding(ANIMATIONS,model(previewProp),dir.resolve("prop.vmd"))) {
            API.ChangeModelAnim(actor,actorMotion,0);
            API.SetLayerSpeed(actor,0,0);
            API.SetLayerLoop(actor,0,true); // Same as the stage character path.
            actorPreview.seek(f(30)); propPreview.seek(f(30));
            evaluate(previewActor,cpu); evaluate(previewProp,cpu);
            var initial=State.DEFAULT;
            var attaches=explicit?List.of(new Key<>(f(80),new Attachment("Tip",new Offset(1,2,3,10,20,30))),
                    new Key<>(f(160),new Attachment("Root",Offset.IDENTITY))):List.<Key<Attachment>>of();
            Matrix4f base=outer(initial,config,previewActor,parenting);
            Matrix4f target=PropBoneParenting.targetInProp(base,parenting.pose(previewActor,"Tip")[0]);
            Matrix4f[] registration=parenting.pose(previewProp,"Center");
            Offset placement=mode.equals("placed")?new Offset(.6f,-.4f,1.2f,25,-35,80):null;
            Matrix4f desired=placement==null?registration[0]:PropBoneParenting.placementPose(target,placement);
            var service=new com.shiroha.mmdskin.stage.application.PropRegistrationService(
                    new com.shiroha.mmdskin.stage.application.PropRegistrationService.BindingPort() {
                @Override public void setPhysicsEnabled(long handle,boolean enabled) { API.SetPhysicsEnabled(handle,enabled); }
                @Override public boolean evaluate(long handle) { return API.EvaluatePropPose(handle); }
                @Override public void apply(long owner,long child,Matrix4f transform,PropTimeline source,int frame,State state) {
                    check(parenting.apply(owner,child,transform,source,frame,state),"registration transaction");
                }
                @Override public Matrix4f[] pose(long handle,String bone) { return parenting.pose(handle,bone); }
            });
            var binding=service.register(previewActor,previewProp,base,
                    new PropTimeline(initial,List.of(),attaches),f(30),initial,
                    "Center","Tip",Offset.IDENTITY,!mode.equals("snap"),placement);
            check(API.EvaluatePropPose(previewProp),"registration evaluation");
            if(!mode.equals("snap")) near(desired,pose(parenting,previewProp),"registration continuity "+mode);
            var timeline=new PropTimeline(initial,List.of(new Key<>(f(100),false),new Key<>(f(110),true)),attaches,
                    List.of(new Key<>(f(30),binding),new Key<>(f(200),BoneBinding.detached("Center"))));
            var document=PropTimelineFiles.open(dir.resolve("prop.vmd"),"fan.pmx");
            PropTimelineFiles.save(document,timeline,"actor.vmd",saved);
            if(!explicit) {
                // Unrelated default-hand edits must neither reject the VMD nor alter its coordinates.
                config.heldPmxBone="MissingDefaultHand"; config.heldPmxOffsetX=90; config.heldPmxRotY=-140;
            }
            controller.begin(OWNER,new StagePropAssignment("fan.pmx","prop.vmd"),List.of(dir.resolve("actor.vmd")));
            check(controller.bind(OWNER,model(prop),dir,"fan.pmx",motion -> signature(config,null)),"stage bind");
            check(controller.takeWarning(OWNER)==null,"valid calibration warning");
            var sequential=new LinkedHashMap<Integer,Matrix4f>();
            // Continuous samples, stage beyond actor and prop endpoints, then backward samples.
            var frames=new ArrayList<Integer>();
            for(int frame=0;frame<=f(300);frame+=frameScale==1?5:1) frames.add(frame);
            for(int frame:List.of(60,30,199,0,120,300)) frames.add(f(frame));
            for(int frame:frames) {
                API.SeekLayer(actor,0,frame);
                evaluate(actor,cpu);
                actorPreview.seek(frame); evaluate(previewActor,cpu);
                near(parenting.pose(previewActor,"Tip")[0],parenting.pose(actor,"Tip")[0],
                        "stage and preview actor agree at "+frame);
                controller.seek(OWNER,frame);
                var state=controller.state(OWNER);
                check(state.equals(timeline.evaluate(frame)),"absolute controls at "+frame);
                Matrix4f transform=outer(state,config,actor,parenting);
                check(controller.applyBoneParents(OWNER,actor,prop,transform),"stage parenting at "+frame);
                evaluate(prop,cpu);
                parenting.clear(previewProp);
                var window=timeline.outsideParentWindow("Center",frame);
                check(API.SetPropBoneWindows(previewProp,new int[]{1},
                        new float[]{window.previousFrame(),window.nextFrame()}),"reference switch window");
                propPreview.seek(frame); evaluate(previewProp,cpu);
                Matrix4f expected=pose(parenting,previewProp);
                if(frame>=f(30) && frame<f(200)) {
                    Matrix4f currentTarget=PropBoneParenting.targetInProp(transform,parenting.pose(previewActor,"Tip")[0]);
                    Matrix4f animation=PropBoneParenting.animationLocal(parenting.pose(previewProp,"Center")[1],
                            parenting.pose(previewProp,"Center")[2]);
                    expected=PropBoneParenting.parentFrame(currentTarget,binding).mul(animation);
                }
                Matrix4f actual=pose(parenting,prop);
                near(expected,actual,"authored motion and absolute parent at "+frame+" "+mode+" explicit="+explicit);
                Matrix4f prior=sequential.putIfAbsent(frame,new Matrix4f(actual));
                if(prior!=null) near(prior,actual,"rewind must not accumulate "+frame);
                if(frame==f(30) && !mode.equals("snap")) near(desired,actual,"stage registration frame continuity");
                // An extra draw at the same clock must not modify the selected bone.
                controller.applyBoneParents(OWNER,actor,prop,transform);
                evaluate(prop,cpu);
                near(actual,pose(parenting,prop),"duplicate render evaluation "+frame);
            }
            // Reload a fresh native prop for direct seeks so an earlier accumulated error cannot cancel out.
            controller.stop(OWNER);
            API.DeleteModel(prop); prop=load("fan.pmx");
            for(int sourceFrame:List.of(0,30,60,80,120,180,200,240,300,30)) {
                int frame=f(sourceFrame);
                controller.begin(OWNER,new StagePropAssignment("fan.pmx","prop.vmd"),List.of(dir.resolve("actor.vmd")));
                controller.seek(OWNER,frame);
                check(controller.bind(OWNER,model(prop),dir,"fan.pmx",motion -> signature(config,null)),"late stage bind");
                API.SeekLayer(actor,0,frame); evaluate(actor,cpu);
                controller.applyBoneParents(OWNER,actor,prop,outer(controller.state(OWNER),config,actor,parenting));
                evaluate(prop,cpu);
                near(sequential.get(frame),pose(parenting,prop),"direct/continuous seek agree "+frame);
            }
            // A real calibration mismatch keeps usable VMD and non-bone tracks running and reports once.
            controller.begin(OWNER,new StagePropAssignment("fan.pmx","prop.vmd"),List.of(dir.resolve("actor.vmd")));
            var current=signature(config,null);
            var invalid=new PropTimelineCalibration.Signature("a".repeat(64),current.prop(),current.motion(),
                    current.reference(),current.bone(),current.transform());
            check(controller.bind(OWNER,model(prop),dir,"fan.pmx",motion -> invalid),"VMD survives calibration mismatch");
            check(controller.takeWarning(OWNER)!=null && controller.takeWarning(OWNER)==null,"warning consumed once");
            controller.seek(OWNER,f(120)); evaluate(prop,cpu);
            check(API.SetPropBoneWindows(previewProp,new int[0],new float[0]),"clear reference window");
            propPreview.seek(f(120)); evaluate(previewProp,cpu);
            check(controller.state(OWNER).boneBindings().isEmpty(),"unsafe bone parenting skipped");
            near(pose(parenting,previewProp),pose(parenting,prop),"mismatched VMD still animated");
            System.out.printf("PASS stage %s %s explicit=%s: frame 0..%d, short actor endpoint, default grip, VMD, register, unbind, duplicate draws, rewind/direct/late seek, calibration isolation%n",
                    cpu?"CPU":"GPU pose",mode,explicit,f(300));
        } finally {
            controller.clear(); parenting.clear(previewProp);
            API.DeleteAnimation(actorMotion);
            API.DeleteModel(actor); API.DeleteModel(previewActor); API.DeleteModel(previewProp); API.DeleteModel(prop);
        }
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); dir=Path.of(args[1]); frameScale=Integer.parseInt(args[2]);
        runtime=(NativeRuntimePort)Proxy.newProxyInstance(Probe.class.getClassLoader(),new Class<?>[]{NativeRuntimePort.class},
                (p,m,a) -> switch(m.getName()) {
                    case "getBoneNames" -> API.GetBoneNames((long)a[0]);
                    case "getPropBonePose" -> API.GetPropBonePosePacket((long)a[0],(int)a[1],(ByteBuffer)a[2],192)==3;
                    case "setPropBindings" -> API.SetPropBindings((long)a[0],(int[])a[1],(float[])a[2],(int[])a[3],(float[])a[4]);
                    case "setPropBoneParents" -> API.SetPropBoneParents((long)a[0],(int[])a[1],(float[])a[2]);
                    case "setPropBoneWindows" -> API.SetPropBoneWindows((long)a[0],(int[])a[1],(float[])a[2]);
                    case "evaluatePropPose" -> API.EvaluatePropPose((long)a[0]);
                    default -> throw new AssertionError(m.getName());
                });
        NativeRuntimeBridgeHolder.set(runtime);
        for(boolean cpu:List.of(false,true)) for(String mode:List.of("snap","keepWorld","placed"))
            for(boolean explicit:List.of(false,true)) run(cpu,mode,explicit);
        System.out.printf("PASS production stage JNI: maximum matrix element error=%.9f%n",maxError);
    }
}
