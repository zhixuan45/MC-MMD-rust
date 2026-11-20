package com.shiroha.mmdskin.stage.domain.model;

/** Optional prop assets; a PMX reference may include a sibling EntityPlayer folder. */
public record StagePropAssignment(String pmxFile, String motionFile) {
    public boolean isValid() {
        return pmxFile != null && !pmxFile.isEmpty()
                && pmxFile.equals(com.shiroha.mmdskin.config.ModelConfigData.normalizeHeldPmxReference(pmxFile))
                && motionFile != null && motionFile.length() <= 255
                && !com.shiroha.mmdskin.config.ModelConfigData.normalizeVmdFileName(motionFile).isEmpty();
    }
}
