package com.shiroha.mmdskin.player.runtime;

/** 无 Minecraft 依赖的当前帧眼高快照。 */
final class FrontViewCameraSnapshot {
    private Object player;
    private Object cameraEntity;
    private Double pivotHeight;

    void capture(Object player, Object cameraEntity, Double pivotHeight) {
        clear();
        if (player == null || cameraEntity != player || pivotHeight == null || !Double.isFinite(pivotHeight)) return;
        this.player = player;
        this.cameraEntity = cameraEntity;
        this.pivotHeight = pivotHeight;
    }

    Double get(Object entity, Object currentPlayer, Object currentCameraEntity) {
        if (pivotHeight == null || entity != player || currentPlayer != player
                || currentCameraEntity != cameraEntity || cameraEntity != player) return null;
        return pivotHeight;
    }

    void clear() {
        player = null;
        cameraEntity = null;
        pivotHeight = null;
    }

    static Double worldHeight(double entityY, float[] eye, float modelScale) {
        if (!Double.isFinite(entityY) || eye == null || eye.length < 3
                || !Float.isFinite(modelScale) || modelScale <= 0.0f) return null;
        boolean nonZero = false;
        for (float coordinate : eye) {
            if (!Float.isFinite(coordinate)) return null;
            nonZero |= coordinate != 0.0f;
        }
        if (!nonZero) return null;
        double height = entityY + eye[1] * modelScale;
        return Double.isFinite(height) ? height : null;
    }
}
