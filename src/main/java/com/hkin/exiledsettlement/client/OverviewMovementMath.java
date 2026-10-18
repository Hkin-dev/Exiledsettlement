package com.hkin.exiledsettlement.client;

/** Pure movement math shared by the overview controller and server-side regression tests. */
public final class OverviewMovementMath {
    private OverviewMovementMath() {
    }

    public static Delta horizontal(float cameraYaw, double forwardAxis, double rightAxis, double speed) {
        double forward = forwardAxis * speed;
        double right = rightAxis * speed;
        double length = Math.sqrt(forward * forward + right * right);
        if (length > speed && length > 0.0D) {
            forward = forward / length * speed;
            right = right / length * speed;
        }
        double radians = Math.toRadians(cameraYaw);
        return new Delta(
                -Math.sin(radians) * forward - Math.cos(radians) * right,
                Math.cos(radians) * forward - Math.sin(radians) * right);
    }

    public record Delta(double x, double z) {
    }
}
