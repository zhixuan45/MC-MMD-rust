package com.shiroha.mmdskin.compat.maid.render;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 只在能确认最终进入原版 Maid renderer 分支时提前求值。 */
public final class MaidRenderOwnership {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final String RENDERER =
            "com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer";
    private static final String MAID_API =
            "com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid";
    private static final MaidApi ACCESS = loadApi();
    private static final Map<Class<?>, Method> INFO_GETTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GECKO_CHECKS = new ConcurrentHashMap<>();

    private MaidRenderOwnership() {}

    public static boolean isVanillaRendererPath(LivingEntity entity, Object renderer) {
        if (entity == null || !(entity instanceof Mob mob) || renderer == null
                || !hasRendererType(renderer.getClass())) return reject("renderer", renderer);
        try {
            if (ACCESS == null) return reject("maid-api", entity);
            Object maid = ACCESS.convert().invoke(null, mob);
            if (maid == null) return reject("maid-api", entity);
            if (Boolean.TRUE.equals(ACCESS.isYsmModel().invoke(maid))) return false;

            Method getInfo = INFO_GETTERS.computeIfAbsent(renderer.getClass(), type -> findMethod(type, "getMainInfo"));
            Object info = getInfo.invoke(renderer);
            if (info == null) return reject("model-info", renderer);
            Method geckoCheck = GECKO_CHECKS.computeIfAbsent(info.getClass(), type -> findMethod(type, "isGeckoModel"));
            Object geckoState = geckoCheck.invoke(info);
            if (!(geckoState instanceof Boolean gecko)) return reject("gecko-state", info);
            return !gecko;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return reject("api-shape", renderer);
        }
    }

    private static boolean hasRendererType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (RENDERER.equals(current.getName())) return true;
        }
        return false;
    }

    private static MaidApi loadApi() {
        try {
            Class<?> type = Class.forName(MAID_API);
            return new MaidApi(type.getMethod("convert", Mob.class), type.getMethod("isYsmModel"));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        try { return type.getMethod(name); }
        catch (ReflectiveOperationException ignored) { throw new IllegalStateException(name, ignored); }
    }

    private record MaidApi(Method convert, Method isYsmModel) {}

    private static boolean reject(String reason, Object subject) {
        String type = subject == null ? "null" : subject.getClass().getName();
        String key = reason + ':' + type;
        if (REPORTED.add(key)) LOGGER.warn("[MMD] 女仆渲染归属无法确认，本次保持原路径：{} ({})", reason, type);
        return false;
    }
}
