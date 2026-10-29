package com.shiroha.mmdskin.render.backend;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.bridge.runtime.NativeRenderBackendPort;
import com.shiroha.mmdskin.render.pipeline.RenderFrameClock;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HeldPmxEvaluationTest {
    @Test void mainAndShadowDrawsEvaluateThePropOnlyOnceWithoutTogglingPhysics() {
        PropModel prop=new PropModel();
        RenderFrameClock.begin();
        prop.draw(RenderScene.HELD_ITEM_NO_PHYSICS);
        prop.draw(RenderScene.HELD_ITEM_NO_PHYSICS);
        assertEquals(2,prop.draws);
        assertEquals(1,prop.updates);
        assertEquals(List.of(false),prop.physics);
        RenderFrameClock.begin();
        prop.draw(RenderScene.HELD_ITEM);
        prop.draw(RenderScene.HELD_ITEM);
        assertEquals(2,prop.updates);
        assertEquals(List.of(false,true),prop.physics);
    }

    private static final class PropModel extends BaseModelInstance {
        int draws,updates;
        final List<Boolean> physics=new ArrayList<>();
        PropModel() {
            model=1;
            nativeRenderBackendPort=(NativeRenderBackendPort)Proxy.newProxyInstance(NativeRenderBackendPort.class.getClassLoader(),
                    new Class<?>[]{NativeRenderBackendPort.class},(proxy,method,args)->{
                        if (method.getName().equals("setPhysicsEnabled")) { physics.add((Boolean)args[1]); return null; }
                        throw new AssertionError("Unexpected native call: "+method.getName());
                    });
        }
        void draw(RenderScene scene) { render(null,0,0,new Vector3f(),0,new PoseStack(),0,scene); }
        @Override protected boolean update() { updates++; return true; }
        @Override protected void onUpdate(float delta) { fail("Update override was bypassed"); }
        @Override protected void doRenderModel(Entity entity,float yaw,float pitch,Vector3f translation,PoseStack stack,int light,RenderScene scene) { draws++; }
        @Override public void dispose() { }
    }
}
