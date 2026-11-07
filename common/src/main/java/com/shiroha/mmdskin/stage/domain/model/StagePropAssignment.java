package com.shiroha.mmdskin.stage.domain.model;

/** Optional prop assets; a PMX reference may include a sibling EntityPlayer folder. */
public record StagePropAssignment(String pmxFile, String motionFile) {
    public boolean isValid() {
        return pmxFile != null && !pmxFile.isEmpty()
                && pmxFile.equals(com.shiroha.mmdskin.config.ModelConfigData.normalizeHeldPmxReference(pmxFile))
                && safe(motionFile,".vmd");
    }
    private static boolean safe(String name,String extension) {
        return name != null && !name.isBlank() && name.equals(name.trim()) && name.length()<=255
                && !name.contains("/") && !name.contains("\\") && !name.contains(":")
                && name.chars().noneMatch(Character::isISOControl)
                && name.toLowerCase(java.util.Locale.ROOT).endsWith(extension);
    }
}
