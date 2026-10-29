package com.shiroha.mmdskin.stage.client.camera;

import com.shiroha.mmdskin.config.StageEndPolicy;

final class StageEndFrameResolver {
    record Result(float frame, boolean fallback) { }
    private StageEndFrameResolver() { }
    static Result resolve(StageEndPolicy policy, float legacy, float camera, float motion, float audio) {
        if (policy == null) policy = StageEndPolicy.LEGACY;
        float selected = switch (policy) {
            case LEGACY -> legacy;
            case CAMERA -> camera;
            case MOTION -> motion;
            case AUDIO -> audio;
            case SHORTEST -> {
                float result=Float.POSITIVE_INFINITY;
                for (float value : new float[]{camera,motion,audio}) if (valid(value)) result=Math.min(result,value);
                yield result;
            }
        };
        return valid(selected) ? new Result(selected,false) : new Result(valid(legacy)?legacy:0,true);
    }
    private static boolean valid(float value) { return Float.isFinite(value) && value>=0; }
}
