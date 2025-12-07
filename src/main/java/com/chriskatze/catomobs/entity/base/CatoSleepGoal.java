package com.chriskatze.catomobs.entity.base;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * CatoSleepGoal
 *
 * Purpose:
 * This is a HIGH-priority "lock" goal that runs while the mob is sleeping.
 *
 * Why it exists:
 * - Even if your mob is "sleeping" logically, other goals (wander/look/jump/etc.)
 *   may still try to run and can cause:
 *   - drifting / sliding
 *   - head turning while asleep
 *   - tiny navigation impulses that break the sleep pose
 *
 * What it does:
 * - Claims MOVE / LOOK / JUMP flags so other goals using those flags are blocked.
 * - Hard-stops navigation every tick.
 * - Zeros horizontal velocity so the mob doesn't slide.
 * - Freezes rotations (yaw/bodyYaw/headYaw/pitch) to last tick values.
 * - Forces LookControl to "look at itself" so it doesn't keep adjusting head rotation.
 *
 * How it is activated:
 * - CatoBaseMob sets DATA_SLEEPING via setSleeping(true).
 * - This goal's canUse/canContinueToUse returns true as long as mob.isSleeping() is true.
 */
public class CatoSleepGoal extends Goal {

    /** The owning mob we are locking down while sleeping. */
    private final CatoBaseMob mob;

    public CatoSleepGoal(CatoBaseMob mob) {
        this.mob = mob;

        // Claim these flags so other goals that want MOVE/LOOK/JUMP can't run at the same time.
        // (This is the "AI lock" part.)
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    /**
     * Start this goal if (and only if) the mob is currently sleeping.
     */
    @Override
    public boolean canUse() {
        return mob.isSleeping();
    }

    /**
     * Keep running as long as the mob stays sleeping.
     * Once CatoBaseMob wakes it up, this becomes false and the goal ends.
     */
    @Override
    public boolean canContinueToUse() {
        return mob.isSleeping();
    }

    /**
     * Called once when the sleep lock begins.
     * We immediately stop pathing and disable sprint/jump to prevent any leftover motion.
     */
    @Override
    public void start() {
        mob.getNavigation().stop();
        mob.setSprinting(false);
        mob.setJumping(false);
    }

    /**
     * Called every tick while sleeping.
     * This is where we "hard lock" the entity so it visually stays asleep.
     */
    @Override
    public void tick() {
        // 1) Stop any movement/pathing requests (wander/chase/etc.)
        mob.getNavigation().stop();

        // 2) Make sure sprinting/jumping aren't active
        mob.setSprinting(false);
        mob.setJumping(false);

        // 3) Kill horizontal motion so it doesn't slide around in its sleep pose.
        // Keep Y velocity so gravity/buoyancy still work if something weird happens (water, falling, etc.)
        mob.setDeltaMovement(0.0D, mob.getDeltaMovement().y, 0.0D);

        // 4) Freeze rotations by snapping them back to last tick values.
        // This prevents head/body turning while asleep, even if LookControl tries to update.
        mob.setYRot(mob.yRotO);          // entity yaw
        mob.setYBodyRot(mob.yBodyRotO);  // body yaw
        mob.setYHeadRot(mob.yHeadRotO);  // head yaw

        // Also freeze pitch so it can't "nod" while sleeping.
        mob.setXRot(mob.xRotO);

        // 5) Neutralize LookControl:
        // Make it "look at itself" so it won't try to rotate the head toward targets/players.
        mob.getLookControl().setLookAt(mob.getX(), mob.getEyeY(), mob.getZ());
    }
}
