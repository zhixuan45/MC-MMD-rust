package com.shiroha.mmdskin.render.effectpack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 导入后的效果语义；与 OpenGL、Minecraft 和原始 FX 执行环境解耦。 */
public record FxEffect(String id, String name, Path root, Kind kind,
                       Map<String, Channel> channels, float alpha, String colorFunction, List<String> notes) {
    public enum Kind { RAY_MATERIAL, COLOR_FILTER }

    public record Channel(int source, String file, float[] value, int uvFlip, float[] repeat,
                          int type, int component, int scaleMode) { }

    public boolean supportsWorld() { return kind == Kind.COLOR_FILTER; }
    public static final String IDENTITY = "vec4 mmdFxColor(vec4 c) { return c; }";
}
