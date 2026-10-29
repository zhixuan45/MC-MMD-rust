package com.shiroha.mmdskin.stage.domain.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StageDescriptor {
    private String packName;
    private List<String> motionFiles = new ArrayList<>();
    private String cameraFile;
    private String audioFile;
    private Float endFrame;
    private StagePropAssignment propAssignment;

    public StageDescriptor() {
    }

    public StageDescriptor(String packName, List<String> motionFiles, String cameraFile, String audioFile) {
        this.packName = packName;
        this.motionFiles = motionFiles != null ? new ArrayList<>(motionFiles) : new ArrayList<>();
        this.cameraFile = cameraFile;
        this.audioFile = audioFile;
    }

    public String getPackName() {
        return packName;
    }

    public void setPackName(String packName) {
        this.packName = packName;
    }

    public List<String> getMotionFiles() {
        return Collections.unmodifiableList(motionFiles);
    }

    public void setMotionFiles(List<String> motionFiles) {
        this.motionFiles = motionFiles != null ? new ArrayList<>(motionFiles) : new ArrayList<>();
    }

    public String getCameraFile() {
        return cameraFile;
    }

    public void setCameraFile(String cameraFile) {
        this.cameraFile = cameraFile;
    }

    public String getAudioFile() {
        return audioFile;
    }

    public void setAudioFile(String audioFile) {
        this.audioFile = audioFile;
    }

    public boolean hasMotion() {
        return !motionFiles.isEmpty();
    }

    public Float getEndFrame() { return endFrame; }
    public void setEndFrame(Float frame) { this.endFrame=frame; }
    public StagePropAssignment getPropAssignment() { return propAssignment; }
    public void setPropAssignment(StagePropAssignment assignment) { this.propAssignment=assignment; }

    public StageDescriptor copy() {
        StageDescriptor copy=new StageDescriptor(packName, motionFiles, cameraFile, audioFile);
        copy.endFrame=endFrame;
        copy.propAssignment=propAssignment;
        return copy;
    }

    public boolean isValid() {
        if (propAssignment!=null && !propAssignment.isValid()) return false;
        if (endFrame != null && (!Float.isFinite(endFrame) || endFrame<0)) return false;
        if (!isSafeName(packName) || motionFiles.isEmpty()) {
            return false;
        }
        for (String motionFile : motionFiles) {
            if (!isSafeName(motionFile)) {
                return false;
            }
        }
        return isOptionalSafeName(cameraFile) && isOptionalSafeName(audioFile);
    }

    public String resolveAudioPath(File stageDir) {
        if (stageDir == null || !isSafeName(audioFile)) {
            return null;
        }
        return new File(stageDir, audioFile).getAbsolutePath();
    }

    private static boolean isOptionalSafeName(String name) {
        return name == null || name.isEmpty() || isSafeName(name);
    }

    private static boolean isSafeName(String name) {
        return name != null && !name.isEmpty()
                && !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\");
    }
}
