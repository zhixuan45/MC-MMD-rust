package com.shiroha.mmdskin;

import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.pipeline.GpuTimerQueryPool;
import com.shiroha.mmdskin.stage.client.bootstrap.StageClientBootstrap;
import com.shiroha.mmdskin.util.VectorParseUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

public class MmdSkinClient {
    public static final Logger logger = LogManager.getLogger();

    public static void initClient() {
        StageClientBootstrap.initialize();
        MmdClientResourceBootstrap.initialize();
        ClientRenderRuntime.initialize();
    }

    /** 平台生命周期在窗口 OpenGL 上下文仍可用时调用。 */
    public static void onClientStopping() {
        com.shiroha.mmdskin.render.pipeline.NativeEvaluationBatch.shutdown();
        com.shiroha.mmdskin.ui.paperdoll.PaperDollRenderer.releaseCache();
        GpuTimerQueryPool.disposeAll();
    }

    public static String calledFrom(int i){
        StackTraceElement[] steArray = Thread.currentThread().getStackTrace();
        if (steArray.length <= i) {
            return "";
        }
        return steArray[i].getClassName();
    }

    public static Vector3f str2Vec3f(String arg){
        return VectorParseUtil.parseVec3f(arg);
    }

}
