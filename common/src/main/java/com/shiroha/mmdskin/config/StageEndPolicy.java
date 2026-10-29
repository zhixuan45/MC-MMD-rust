package com.shiroha.mmdskin.config;

public enum StageEndPolicy {
    LEGACY, CAMERA, MOTION, AUDIO, SHORTEST;

    public StageEndPolicy next() { return values()[(ordinal()+1)%values().length]; }
    public String translationKey() { return "gui.mmdskin.stage.end_policy." + name().toLowerCase(java.util.Locale.ROOT); }
}
