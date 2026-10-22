package com.shiroha.mmdskin.render.material;

import java.lang.reflect.InvocationTargetException;

/** Resolve the production reflection bridge against the installed Iris/Sodium binaries. */
public final class IrisTransparencyApiProbe {
    public static void main(String[] args) throws Exception {
        var loader = IrisTransparencyApiProbe.class.getClassLoader();
        var bridge = Class.forName("com.shiroha.mmdskin.compat.iris.IrisWorldTransparencyTarget$Access", false, loader);
        var constructor = bridge.getDeclaredConstructor();
        constructor.setAccessible(true);
        try {
            constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Installed Iris pipeline API lookup failed", e.getCause());
        }
        var pipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline", false, loader);
        var keys = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey", false, loader);
        var map = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderMap", false, loader);
        pipeline.getMethod("getShaderMap");
        pipeline.getMethod("bindDefault");
        map.getMethod("getShader", keys);
        keys.getField("ENTITIES_TRANSLUCENT");
        System.out.println("PASS IrisTransparencyApiProbe: installed Iris manager/pipeline/flip getters, stage color binder and entity ShaderMap API");
    }
}
