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
