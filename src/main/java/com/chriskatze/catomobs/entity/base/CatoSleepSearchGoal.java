package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * CatoSleepSearchGoal
 *
 * Purpose:
 * If a species requires a roof to sleep (sleepRequiresRoof = true), this goal
 * searches for a nearby "roofed" spot, walks the mob there, then triggers sleep.
 *
 * Key concept:
 * - CatoBaseMob decides "I want to sleep" by opening a short desire window.
 * - If roof is required and we are currently unroofed, this goal consumes that desire
 *   by finding a suitable spot and moving there.
 *
 * Important:
 * - This goal does NOT do random rolling. It only runs if the base class already wants sleep.
 * - On failure (no spot / timeout / spot becomes invalid), we clear the desire episode and apply cooldown.
 */
public class CatoSleepSearchGoal extends Goal {

    /** Distance^2 threshold for "close enough to target block". (~1.4 blocks) */
    private static final double ARRIVAL_DIST_SQR = 2.0D;

    /** Owning mob. Provides sleep flags, species info, roof checks, memory, etc. */
    private final CatoBaseMob mob;

    /** Destination stand position (must be roofed). Set during canUse(). */
    private BlockPos targetPos = null;

    /** Safety timeout: when it reaches 0 the search is considered failed. */
    private int searchTimeoutTicks = 0;

    public CatoSleepSearchGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (!info.sleepEnabled()) return false;

        if (mob.isSleeping()) return false;
        if (mob.isSleepSearching()) return false;

        if (mob.isSleepSearchOnCooldown()) return false;

        if (mob.getTarget() != null || mob.isAggressive()) return false;

        boolean isDay = mob.level().isDay();
        if (isDay && !info.sleepAtDay()) return false;
        if (!isDay && !info.sleepAtNight()) return false;

        if (!info.sleepRequiresRoof()) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        if (mob.isRoofed(mob.blockPosition(), roofMax)) return false;

        if (!mob.wantsToSleepNow()) return false;

        // Delegate selection to helper (includes:
        // - remembered spots
        // - random search
        // - buddy scoring
        // - "not occupied by sleeping mob" validation
        this.targetPos = SleepSpotFinder.findRoofedSleepSpot(mob, info);

        if (this.targetPos == null) {
            mob.clearSleepDesire();
            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            return false;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetPos == null) return false;
        if (mob.isSleeping()) return false;

        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (mob.getTarget() != null || mob.isAggressive()) return false;

        if (info.wakeOnAir() && !mob.onGround()) return false;
        if (info.wakeOnTouchingWater() && mob.isInWater() && !info.sleepAllowedOnWaterSurface()) return false;
        if (info.wakeOnUnderwater() && mob.isUnderWater()) return false;

        return searchTimeoutTicks > 0;
    }

    @Override
    public void start() {
        if (this.targetPos == null) {
            mob.setSleepSearching(false);
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
            return;
        }

        mob.setSleepSearching(true);
        searchTimeoutTicks = Math.max(1, mob.getSpeciesInfo().sleepSearchTimeoutTicks());

        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));

        double speed = Math.max(0.1D, mob.getSpeciesInfo().wanderWalkSpeed());
        moveToTarget(speed);
    }

    @Override
    public void stop() {
        mob.setSleepSearching(false);
        targetPos = null;
        searchTimeoutTicks = 0;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Level level = mob.level();
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        // A) Hard failure if target got lost
        if (targetPos == null) {
            mob.setSleepSearching(false);
            mob.clearSleepDesire();
            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
            return;
        }

        // B) Timeout handling
        searchTimeoutTicks--;
        if (searchTimeoutTicks <= 0) {
            mob.clearSleepDesire();
            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
            return;
        }

        // C) Keep navigation alive
        if (mob.getNavigation().isDone()) {
            double speed = Math.max(0.1D, info.wanderWalkSpeed());
            moveToTarget(speed);
        }

        // D) Arrival handling
        double distSqr = mob.distanceToSqr(Vec3.atBottomCenterOf(targetPos));
        if (distSqr > ARRIVAL_DIST_SQR) return;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(targetPos, roofMax);

        if (roofDy != -1) {

            // Optional: buddy override relocation (snuggle)
            if (info.sleepBuddyCanOverrideMemory()) {
                BlockPos buddySpot = SleepBuddyRelocator.findBuddyAdjacentSleepSpot(mob, info, targetPos);

                if (buddySpot != null && !buddySpot.equals(targetPos)) {
                    mob.strikeSleepSpot(targetPos);
                    targetPos = buddySpot;

                    double speed = Math.max(0.1D, info.wanderWalkSpeed());
                    moveToTarget(speed);
                    return;
                }
            }

            // Commit: remember + consume desire + sleep
            mob.rememberSleepSpot(targetPos);
            mob.clearSleepDesire();
            mob.beginSleepingFromGoal();

            mob.getNavigation().stop();
            stop();
            return;
        }

        // Roof invalid => fail episode + cooldown
        mob.clearSleepDesire();
        mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
        stop();
    }

    private void moveToTarget(double speed) {
        mob.getNavigation().moveTo(
                targetPos.getX() + 0.5D,
                targetPos.getY(),
                targetPos.getZ() + 0.5D,
                speed
        );
    }
}
