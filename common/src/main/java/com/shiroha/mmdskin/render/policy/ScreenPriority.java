package com.shiroha.mmdskin.render.policy;

/** Approximate projected sphere for budget ranking only. Never rejects a render or shadow. */
final class ScreenPriority {
    private ScreenPriority() { }

    static double score(double distanceSq, double x, double y, double depth, double radius,
                        double tanHalfFov, double aspect, boolean previous) {
        if (!Double.isFinite(distanceSq) || distanceSq < 0) return Double.MAX_VALUE;
        if (!Double.isFinite(x + y + depth + radius + tanHalfFov + aspect)
                || radius <= 0 || tanHalfFov <= 0 || aspect <= 0) return distanceSq;
        double score = distanceSq;
        if (depth < -radius) {
            score *= 4;
        } else {
            double z = Math.max(radius, depth);
            double halfY = z * tanHalfFov, halfX = halfY * aspect;
            boolean overlaps = Math.abs(x) <= halfX + radius && Math.abs(y) <= halfY + radius;
            double area = Math.min(1, radius * radius / (halfX * halfY));
            score *= (overlaps ? 1 : 2.5) / (1 + 2 * area);
        }
        return score * (previous ? .85 : 1);
    }
}
