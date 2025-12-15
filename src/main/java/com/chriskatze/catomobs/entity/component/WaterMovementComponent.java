package com.chriskatze.catomobs.entity.component;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * WaterMovementComponent
 *
 * Small helper that applies:
 * 1) horizontal swim input scaling
 * 2) optional vertical bobbing damping when idle
 *
 * Designed to be called from Mob#travel().
 */
public final class WaterMovementComponent {

    public record Config(
            boolean dampingEnabled,
            double verticalDamping,
            double verticalSpeedClamp,
            double dampingApplyThreshold
    ) {}

    private final Config cfg;

    public WaterMovementComponent(Config cfg) {
        this.cfg = cfg;
    }

    /**
     * Scales X/Z input only (keeps Y input as-is).
     */
    public Vec3 scaleHorizontalInput(Vec3 input, double swimSpeedMultiplier) {
        if (swimSpeedMultiplier <= 0.0D || swimSpeedMultiplier == 1.0D) return input;
        if (input.x == 0.0D && input.z == 0.0D) return input;
        return new Vec3(input.x * swimSpeedMultiplier, input.y, input.z * swimSpeedMultiplier);
    }

    /**
     * Dampens Y velocity to reduce bobbing when idle.
     * Call this only when you *want* damping (e.g. nav done).
     */
    public Vec3 dampVerticalIfIdle(Vec3 motion) {
        if (!cfg.dampingEnabled) return motion;

        double vy = motion.y;
        if (Math.abs(vy) >= cfg.dampingApplyThreshold) return motion;

        double y = Mth.clamp(vy * cfg.verticalDamping, -cfg.verticalSpeedClamp, cfg.verticalSpeedClamp);
        return new Vec3(motion.x, y, motion.z);
    }
}
