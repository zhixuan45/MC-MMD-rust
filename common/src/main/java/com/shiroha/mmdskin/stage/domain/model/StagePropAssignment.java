package com.shiroha.mmdskin.stage.domain.model;

/** Optional owner-specific prop assets, relative to the owner's resolved character folder. */
public record StagePropAssignment(String pmxFile, String motionFile) {
    public boolean isValid() { return safe(pmxFile,".pmx") && safe(motionFile,".vmd"); }
    private static boolean safe(String name,String extension) {
        return name != null && !name.isBlank() && name.equals(name.trim()) && name.length()<=255
                && !name.contains("/") && !name.contains("\\") && !name.contains(":")
                && name.chars().noneMatch(Character::isISOControl)
                && name.toLowerCase(java.util.Locale.ROOT).endsWith(extension);
    }
}
