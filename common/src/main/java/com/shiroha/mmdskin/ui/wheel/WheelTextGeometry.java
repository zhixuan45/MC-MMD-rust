package com.shiroha.mmdskin.ui.wheel;

/** 轮盘文字的纯几何边界判断，坐标均为屏幕像素。 */
final class WheelTextGeometry {
    record Rect(float left, float top, float right, float bottom) {
        Rect expand(float amount) {
            return new Rect(left - amount, top - amount, right + amount, bottom + amount);
        }

        boolean isInsideScreen(float screenWidth, float screenHeight) {
            return left >= 0 && top >= 0 && right <= screenWidth && bottom <= screenHeight;
        }
    }

    private WheelTextGeometry() {}

    static boolean fitsSector(Rect rect, float screenWidth, float screenHeight,
                              float centerX, float centerY, float innerRadius, float outerRadius,
                              float startDegrees, float sweepDegrees, int slotCount) {
        if (slotCount <= 0 || rect.left() > rect.right() || rect.top() > rect.bottom()
                || innerRadius < 0 || outerRadius <= innerRadius || sweepDegrees <= 0) return false;
        if (!rect.isInsideScreen(screenWidth, screenHeight)) return false;

        float safeInner = innerRadius + 2.0f;
        float safeOuter = outerRadius - 2.0f;
        if (safeOuter < safeInner) return false;

        float closestX = clamp(centerX, rect.left(), rect.right());
        float closestY = clamp(centerY, rect.top(), rect.bottom());
        if (Math.hypot(closestX - centerX, closestY - centerY) < safeInner) return false;

        for (float x : new float[]{rect.left(), rect.right()}) {
            for (float y : new float[]{rect.top(), rect.bottom()}) {
                double dx = x - centerX;
                double dy = y - centerY;
                if (Math.hypot(dx, dy) > safeOuter) return false;
                if (slotCount > 1 && !angleWithinSector(dx, dy, startDegrees, sweepDegrees)) return false;
            }
        }
        return true;
    }

    private static boolean angleWithinSector(double dx, double dy, float startDegrees, float sweepDegrees) {
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        double offset = (angle - startDegrees + 720.0) % 360.0;
        return offset <= sweepDegrees + 0.01;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
