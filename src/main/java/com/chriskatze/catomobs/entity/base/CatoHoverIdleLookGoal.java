package com.chriskatze.catomobs.entity.base;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Hover idle looking:
 * - only runs when mob is idle (no target, not navigating)
 * - changes HEAD yaw slowly every few seconds
 * - does NOT rotate the body (prevents "spinning")
 */
public class CatoHoverIdleLookGoal extends Goal {

    private final Mob mob;
    private int cooldownTicks = 0;

    // tuneables (safe defaults)
    private final int minDelay;
    private final int maxDelay;
    private final float maxYawDelta;

    public CatoHoverIdleLookGoal(Mob mob, int minDelay, int maxDelay, float maxYawDelta) {
        this.mob = mob;
        this.minDelay = Math.max(1, minDelay);
        this.maxDelay = Math.max(this.minDelay, maxDelay);
        this.maxYawDelta = Math.max(0.0f, maxYawDelta);
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    public CatoHoverIdleLookGoal(Mob mob) {
        this(mob, 40, 100, 30.0f); // every 2–5s, ±30°
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null) return false;
        if (mob.getNavigation().isInProgress()) return false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (cooldownTicks-- > 0) return;

        // next change in 2–5 seconds
        cooldownTicks = minDelay + mob.getRandom().nextInt(maxDelay - minDelay + 1);

        // pick a small head yaw change
        float delta = (mob.getRandom().nextFloat() * 2.0f - 1.0f) * maxYawDelta;
        float newHeadYaw = mob.getYHeadRot() + delta;

        // ✅ Head only. Do NOT touch mob.setYRot(...)
        mob.setYHeadRot(newHeadYaw);
    }
}