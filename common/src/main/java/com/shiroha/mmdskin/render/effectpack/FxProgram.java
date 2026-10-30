package com.shiroha.mmdskin.render.effectpack;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** 一次模型绘制的程序组合；参数、贴图与材质选择不参与 GLSL 代码生成。 */
public record FxProgram(List<FxEffect> effects) {
    public static final FxProgram EMPTY = new FxProgram(List.of());

    public static FxProgram of(Collection<FxEffect> effects) {
        return new FxProgram(effects.stream().filter(java.util.Objects::nonNull)
                .distinct().sorted(Comparator.comparing(FxEffect::id)).toList());
    }
    public boolean enabled() { return !effects.isEmpty(); }
    public boolean hasRay() { return effects.stream().anyMatch(e -> e.kind() == FxEffect.Kind.RAY_MATERIAL); }
    public int selection(FxEffect effect) { return effect == null ? 0 : effects.indexOf(effect) + 1; }

    public String colorFunction() {
        if (!enabled()) return FxEffect.IDENTITY;
        StringBuilder source = new StringBuilder("uniform int FxSelection;\n");
        for (int i = 0; i < effects.size(); i++) {
            FxEffect effect = effects.get(i);
            if (effect.kind() != FxEffect.Kind.COLOR_FILTER) continue;
            source.append(effect.colorFunction().replace("mmdFxColor", "mmdFxColor_" + (i + 1))
                    .replace("mmdFxSaturate", "mmdFxSaturate_" + (i + 1))).append('\n');
        }
        source.append("vec4 mmdFxColor(vec4 c) {\nint selection = FxSelection;\n")
                .append("#ifdef MMD_BATCH_MATERIALS\nif (MmdBatchEnabled) selection = mmdFxSelection;\n#endif\n");
        for (int i = 0; i < effects.size(); i++) if (effects.get(i).supportsWorld())
            source.append("if (selection == ").append(i + 1).append(") return mmdFxColor_").append(i + 1).append("(c);\n");
        return source.append("return c;\n}\n").toString();
    }

    public String key() {
        return !enabled() ? "" : ":fx2:" + FxSource.sha256((hasRay() ? "ray\n" : "") + colorFunction()).substring(0, 16);
    }
}
