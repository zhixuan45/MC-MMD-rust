package com.shiroha.mmdskin.render.shader;

/** Persisted policy, separate from runtime capabilities. A preference never bypasses validation. */
public enum ToonRenderStrategy {
    AUTO, TOON_PRIORITY, SHADER_INTEGRATED;

    public boolean attemptsIndependent(boolean verifiedIntegrated) {
        return this == TOON_PRIORITY || this == AUTO && !verifiedIntegrated;
    }
}
