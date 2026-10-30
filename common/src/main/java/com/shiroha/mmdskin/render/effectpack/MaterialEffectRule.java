package com.shiroha.mmdskin.render.effectpack;

/** index 与名称同时匹配；空 effect 代表明确关闭，缺少规则代表继承整模默认。 */
public record MaterialEffectRule(int index, String name, String effect) {
    public boolean matches(int materialIndex, String materialName) {
        return index == materialIndex && java.util.Objects.equals(name, materialName == null ? "" : materialName);
    }
}
