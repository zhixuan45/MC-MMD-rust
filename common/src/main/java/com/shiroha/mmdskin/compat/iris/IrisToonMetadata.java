package com.shiroha.mmdskin.compat.iris;

import com.shiroha.mmdskin.render.shader.ToonOutputProfile;
import net.minecraft.client.renderer.ShaderInstance;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/** 只读取 Iris 已解析的当前程序，不修改光影包。 */
final class IrisToonMetadata {
    record Result(String packName, String programName, ToonOutputProfile profile) {}

    private static final String IRIS = "net.irisshaders.iris.";
    // 热重载后允许旧 ShaderInstance 回收。
    private static final Map<ShaderInstance, Result> CACHE = new WeakHashMap<>();
    private static Object cachedPack;
    private static Object cachedDimension;
    private static Access access;
    private static boolean accessFailed;

    private IrisToonMetadata() {}

    static Result read(ShaderInstance shader) {
        if (!IrisCompat.isIrisShaderActive() || IrisCompat.isRenderingShadows()) {
            resetCache();
            return vanilla();
        }
        // 背包和纸娃娃的原版 shader 不写光影 G-buffer。
        if (shader == null || !shader.getClass().getName().startsWith(IRIS)) {
            return vanilla();
        }
        String packName = "当前光影";
        String shaderName = shader.getName();
        try {
            if (access == null && !accessFailed) access = new Access();
            if (access == null) return unsupported(packName, shaderName, "无法读取 Iris 程序接口");
            Object pack = ((Optional<?>) access.currentPack.invoke(null)).orElse(null);
            Object dimension = access.currentDimension.invoke(null);
            if (pack != cachedPack || !Objects.equals(dimension, cachedDimension)) {
                CACHE.clear();
                cachedPack = pack;
                cachedDimension = dimension;
            }
            Result cached = CACHE.get(shader);
            if (cached != null) return cached;
            packName = (String) access.packName.invoke(null);
            Result result = resolve(shader, pack, dimension, packName, shaderName);
            CACHE.put(shader, result);
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (access == null) accessFailed = true;
            Result result = unsupported(packName, shaderName, "无法读取 Iris 输出契约（" + e.getClass().getSimpleName() + "）");
            CACHE.put(shader, result);
            return result;
        }
    }

    private static Result resolve(ShaderInstance shader, Object pack, Object dimension,
                                  String packName, String shaderName) throws ReflectiveOperationException {
        if (pack == null) return unsupported(packName, shaderName, "Iris 未提供当前光影程序");
        if (!shader.getClass().getName().equals(IRIS + "pipeline.programs.ExtendedShader")) {
            return unsupported(packName, shaderName, "当前为 Iris 回退程序，颜色编码未确认");
        }
        if ((boolean) shader.getClass().getMethod("hasActiveImages").invoke(shader)
                || shader.getClass().getMethod("getGeometry").invoke(shader) != null
                || shader.getClass().getMethod("getTessControl").invoke(shader) != null
                || shader.getClass().getMethod("getTessEval").invoke(shader) != null) {
            return unsupported(packName, shaderName, "实体程序包含图像写入或额外几何阶段");
        }
        Object key = null;
        for (Object candidate : access.shaderKeys) {
            if (shaderName.equals(access.keyName.invoke(candidate))) {
                key = candidate;
                break;
            }
        }
        if (key == null) return unsupported(packName, shaderName, "无法确认当前实体程序的回退关系");
        Object programSet = access.programSet.invoke(pack, dimension);
        // 使用 Iris 自己的回退解析器，手部和半透明实体不会误套实体布局。
        Object resolver = access.resolverConstructor.newInstance(programSet);
        Object source = ((Optional<?>) access.resolve.invoke(resolver, access.keyProgram.invoke(key))).orElse(null);
        if (source == null) return unsupported(packName, shaderName, "没有有效的实体片段程序");
        String sourceName = (String) access.sourceName.invoke(source);
        String fragment = ((Optional<?>) access.fragment.invoke(source)).map(Object::toString).orElse("");
        Object directives = access.directives.invoke(source);
        if ((boolean) access.unknownBuffers.invoke(directives)) {
            return unsupported(packName, sourceName, "实体程序未声明输出布局");
        }
        int[] buffers = (int[]) access.drawBuffers.invoke(directives);
        return new Result(packName, sourceName, ToonOutputProfile.resolve(packName, sourceName, fragment, buffers));
    }

    private static Result vanilla() {
        return new Result("", "", ToonOutputProfile.vanilla());
    }

    private static Result unsupported(String pack, String program, String reason) {
        return new Result(pack, program, ToonOutputProfile.unsupported(reason));
    }

    private static void resetCache() {
        CACHE.clear();
        cachedPack = null;
        cachedDimension = null;
    }

    static void reset() {
        resetCache();
        access = null;
        accessFailed = false;
    }

    /** 反射句柄只解析一次，维度和包变化只刷新结果。 */
    private static final class Access {
        final Method currentPack, currentDimension, packName, programSet;
        final Object[] shaderKeys;
        final Method keyName, keyProgram, resolve, sourceName, fragment, directives, unknownBuffers, drawBuffers;
        final java.lang.reflect.Constructor<?> resolverConstructor;

        Access() throws ReflectiveOperationException {
            Class<?> iris = Class.forName(IRIS + "Iris");
            Class<?> pack = Class.forName(IRIS + "shaderpack.ShaderPack");
            Class<?> dimension = Class.forName(IRIS + "shaderpack.materialmap.NamespacedId");
            Class<?> keys = Class.forName(IRIS + "pipeline.programs.ShaderKey");
            Class<?> set = Class.forName(IRIS + "shaderpack.programs.ProgramSet");
            Class<?> resolver = Class.forName(IRIS + "shaderpack.programs.ProgramFallbackResolver");
            Class<?> id = Class.forName(IRIS + "shaderpack.loading.ProgramId");
            Class<?> source = Class.forName(IRIS + "shaderpack.programs.ProgramSource");
            Class<?> directive = Class.forName(IRIS + "shaderpack.properties.ProgramDirectives");
            currentPack = iris.getMethod("getCurrentPack");
            currentDimension = iris.getMethod("getCurrentDimension");
            packName = iris.getMethod("getCurrentPackName");
            programSet = pack.getMethod("getProgramSet", dimension);
            shaderKeys = keys.getEnumConstants();
            keyName = keys.getMethod("getName");
            keyProgram = keys.getMethod("getProgram");
            resolverConstructor = resolver.getConstructor(set);
            resolve = resolver.getMethod("resolve", id);
            sourceName = source.getMethod("getName");
            fragment = source.getMethod("getFragmentSource");
            directives = source.getMethod("getDirectives");
            unknownBuffers = directive.getMethod("hasUnknownDrawBuffers");
            drawBuffers = directive.getMethod("getDrawBuffers");
        }
    }
}
