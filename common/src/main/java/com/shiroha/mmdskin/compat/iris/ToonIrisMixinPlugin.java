package com.shiroha.mmdskin.compat.iris;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/** Optional hooks fail closed if an Iris fork changes one of the observed methods. */
public final class ToonIrisMixinPlugin implements IMixinConfigPlugin {
    // Minecraft 1.21.1 ShaderInstance.apply: Mojmap/dev and Fabric intermediary.
    private static final List<String> SHADER_ENTRIES = List.of("apply", "method_34586");
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (!optionalToonHook(mixin)) return true;
        try {
            var node = MixinService.getService().getBytecodeProvider().getClassNode(target, false);
            boolean compatible = target.endsWith("IrisRenderingPipeline")
                    ? hasVoidEntries(node, List.of("beginLevelRendering", "finalizeLevelRendering"))
                    : target.endsWith("SodiumShader") ? hasVoidEntries(node, List.of("setupState"))
                    : hasShaderEntry(node);
            if (compatible) return true;
            org.apache.logging.log4j.LogManager.getLogger().warn("[MMD independent Toon] optional Iris hook signature changed: {}", target);
        } catch (Exception | LinkageError e) {
            org.apache.logging.log4j.LogManager.getLogger().warn("[MMD independent Toon] optional Iris hook unavailable: {}, {}",
                    target, e.getClass().getSimpleName());
        }
        return false;
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        if (!optionalToonHook(mixin)) return;
        boolean valid = target.endsWith("IrisRenderingPipeline")
                ? calls(node, "beginLevelRendering", "mmdskin$toonBegin")
                    && calls(node, "finalizeLevelRendering", "mmdskin$toonBeforeFinal")
                    && calls(node, "finalizeLevelRendering", "mmdskin$toonAfterFinal")
                : target.endsWith("SodiumShader") ? calls(node, "setupState", "mmdskin$toonObserve")
                : SHADER_ENTRIES.stream().anyMatch(entry -> calls(node, entry, "mmdskin$toonObserve"));
        if (valid) ToonIrisHooks.verified(target.substring(target.lastIndexOf('.') + 1));
    }

    private static boolean optionalToonHook(String mixin) {
        return mixin.endsWith("IrisToonLifecycleMixin") || mixin.endsWith("IrisToonShaderObserverMixin")
                || mixin.endsWith("IrisToonTerrainObserverMixin");
    }

    static boolean hasVoidEntries(ClassNode node, List<String> names) {
        for (String name : names) {
            var matching = node.methods.stream().filter(m -> m.name.equals(name)).toList();
            if (matching.isEmpty() || matching.stream().anyMatch(m -> !m.desc.equals("()V"))) return false;
        }
        return true;
    }

    static boolean hasShaderEntry(ClassNode node) {
        var matching = node.methods.stream().filter(m -> SHADER_ENTRIES.contains(m.name)).toList();
        return matching.size() == 1 && matching.getFirst().desc.equals("()V");
    }

    static boolean calls(ClassNode node, String method, String handler) {
        return node.methods.stream().filter(m -> m.name.equals(method) && m.desc.equals("()V"))
                .anyMatch(m -> {
                    for (var instruction : m.instructions)
                        if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                                && call.name.contains(handler)) return true;
                    return false;
                });
    }
}
