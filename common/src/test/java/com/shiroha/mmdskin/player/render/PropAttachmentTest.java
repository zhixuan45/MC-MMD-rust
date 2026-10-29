package com.shiroha.mmdskin.player.render;

import com.shiroha.mmdskin.bridge.runtime.NativeMatrixPort;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class PropAttachmentTest {
    @Test void namedBoneUsesItsEvaluatedGlobalMatrixWithoutTheHandTransform() {
        Port port=new Port();
        Matrix4f selected=ItemRenderHelper.resolvePropAttachment(port,1,2,buffer(),"head");
        assertTrue(selected.equals(new Matrix4f().translation(3,8,-4).rotateY(.6f),1e-5f));
        assertEquals(0,port.handReads);
        assertEquals(1,port.boneReads);
    }
    @Test void absentAndEmptyBonesUseTheCurrentHandRatherThanAStalePreviousBone() {
        Port port=new Port();
        ItemRenderHelper.resolvePropAttachment(port,1,2,buffer(),"head");
        Matrix4f missing=ItemRenderHelper.resolvePropAttachment(port,1,2,buffer(),"missing");
        assertTrue(missing.equals(new Matrix4f().translation(1,2,3),0));
        Matrix4f automatic=ItemRenderHelper.resolvePropAttachment(port,1,2,buffer(),"");
        assertTrue(automatic.equals(missing,0));
        assertEquals(2,port.handReads);
    }
    @Test void physicsAttachmentMatchesTheDrawTransformWithoutCameraCoordinates() {
        var config = new com.shiroha.mmdskin.config.ModelConfigData();
        config.heldPmxOffsetX = 0.4f;
        config.heldPmxOffsetY = -0.2f;
        config.heldPmxRotZ = 75;
        config.heldItemScale = 0.8f;
        config.heldPmxScale = 1.5f;
        var bone = new Matrix4f().translation(3, 8, -4).rotateY(0.6f);
        var original = new Matrix4f(bone);
        var draw = new com.mojang.blaze3d.vertex.PoseStack();
        draw.scale(0.09f, 0.09f, 0.09f);
        draw.last().pose().mul(bone);
        draw.translate(config.heldPmxOffsetX, config.heldPmxOffsetY, config.heldPmxOffsetZ);
        draw.mulPose(new org.joml.Quaternionf().rotationXYZ(0, 0, (float) Math.toRadians(75)));
        float total = 10 * config.heldItemScale * config.heldPmxScale * 0.12f;
        draw.scale(total, total, total);
        assertTrue(draw.last().pose().equals(ItemRenderHelper.propPhysicsTransform(bone, config, 0.09f, 0.12f), 1e-5f));
        assertTrue(original.equals(bone, 0));
    }

    private static ByteBuffer buffer() { return ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN); }
    private static final class Port implements NativeMatrixPort {
        Matrix4f value=new Matrix4f();
        int handReads,boneReads;
        @Override public long createMatrix() { return 2; }
        @Override public void deleteMatrix(long matrix) { }
        @Override public void populateHandMatrix(long model,long matrix,boolean mainHand) { assertTrue(mainHand); handReads++; value.translation(1,2,3); }
        @Override public boolean populateBoneMatrix(long model,long matrix,String name) {
            boneReads++; if (!name.equals("head")) return false;
            value.translation(3,8,-4).rotateY(.6f); return true;
        }
        @Override public boolean copyMatrixToBuffer(long matrix,ByteBuffer buffer) { value.get(0,buffer); return true; }
    }
}
