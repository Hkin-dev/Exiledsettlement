package com.hkin.exiledsettlement.client;

/** Converts raw GLFW cursor coordinates into a camera-pan intent. */
public final class RtsEdgePan {
    private RtsEdgePan() {
    }

    public static Direction compute(
            double mouseX, double mouseY, int windowWidth, int windowHeight,
            double edgeMargin, boolean windowActive
    ) {
        if (!windowActive || windowWidth <= 0 || windowHeight <= 0
                || mouseX < 0.0D || mouseY < 0.0D
                || mouseX > windowWidth || mouseY > windowHeight) {
            return Direction.NONE;
        }
        double right = mouseX <= edgeMargin ? -1.0D
                : mouseX >= windowWidth - edgeMargin ? 1.0D : 0.0D;
        double forward = mouseY <= edgeMargin ? 1.0D
                : mouseY >= windowHeight - edgeMargin ? -1.0D : 0.0D;
        return new Direction(right, forward);
    }

    public record Direction(double right, double forward) {
        public static final Direction NONE = new Direction(0.0D, 0.0D);
    }
}
