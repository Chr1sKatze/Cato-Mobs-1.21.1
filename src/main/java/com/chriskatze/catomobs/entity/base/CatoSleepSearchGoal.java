package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class CatoSleepSearchGoal extends Goal {

    private static final double ARRIVAL_DIST_SQR = 2.0D;

    // ✅ NEW: prevent "standing still with MOVE flag" when no target exists
    private static final int NO_TARGET_GIVE_UP_TICKS = 20;   // ~1s
    private static final int NO_TARGET_COOLDOWN_TICKS = 40;  // ~2s

    private final CatoBaseMob mob;

    private BlockPos targetPos = null;
    private int searchTimeoutTicks = 0;
    private int failedMoveToTicks = 0;

    // Optional: reduce CPU by not running the expensive finder every tick
    private int repickCooldownTicks = 0;

    // ✅ NEW: counts ticks with no usable target
    private int noTargetTicks = 0;

    // ------------------------------------------------------------
    // ✅ Robust stuck detection (prevents "green but standing")
    // ------------------------------------------------------------
    private Vec3 lastPos = null;
    private int notMovingTicks = 0;
    private double lastDistSqr = Double.NaN;
    private int noProgressTicks = 0;

    public CatoSleepSearchGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (!info.sleepEnabled()) return false;
        if (mob.isSleeping()) return false;
        if (mob.isSleepSearching()) return false;
        if (mob.isSleepSearchOnCooldown()) return false;

        // ✅ FLEE GATE: never start searching while fleeing
        if (mob.isFleeing()) return false;

        if (mob.getTarget() != null || mob.isAggressive()) return false;

        boolean isDay = mob.level().isDay();
        if (isDay && !info.sleepAtDay()) return false;
        if (!isDay && !info.sleepAtNight()) return false;

        if (!info.sleepRequiresRoof()) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        if (mob.isRoofed(mob.blockPosition(), roofMax)) return false;

        if (!mob.wantsToSleepNow()) return false;

        this.targetPos = SleepSpotFinder.findRoofedSleepSpot(mob, info);

        return this.targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isSleeping()) return false;

        // ✅ FLEE GATE: abort search immediately if fleeing starts mid-search
        if (mob.isFleeing()) return false;

        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (mob.getTarget() != null || mob.isAggressive()) return false;

        if (info.wakeOnAir() && !mob.onGround()) return false;
        if (info.wakeOnTouchingWater() && mob.isInWater() && !info.sleepAllowedOnWaterSurface()) return false;
        if (info.wakeOnUnderwater() && mob.isUnderWater()) return false;

        return searchTimeoutTicks > 0;
    }

    @Override
    public void start() {
        mob.setSleepSearching(true);

        searchTimeoutTicks = Math.max(1, mob.getSpeciesInfo().sleepSearchTimeoutTicks());
        failedMoveToTicks = 0;
        repickCooldownTicks = 0;
        noTargetTicks = 0;

        lastPos = mob.position();
        notMovingTicks = 0;
        lastDistSqr = Double.NaN;
        noProgressTicks = 0;

        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));

        if (this.targetPos != null) {
            double speed = Math.max(0.1D, mob.getSpeciesInfo().wanderWalkSpeed());
            BlockPos pos = this.targetPos;
            if (!moveToTarget(pos, speed)) {
                mob.strikeSleepSpot(pos);
                this.targetPos = null;
                this.noTargetTicks = NO_TARGET_GIVE_UP_TICKS;
            }
        }
    }

    @Override
    public void stop() {
        mob.setSleepSearching(false);
        targetPos = null;
        searchTimeoutTicks = 0;
        failedMoveToTicks = 0;
        repickCooldownTicks = 0;
        noTargetTicks = 0;

        lastPos = null;
        notMovingTicks = 0;
        lastDistSqr = Double.NaN;
        noProgressTicks = 0;

        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (--searchTimeoutTicks <= 0) {
            mob.clearSleepDesire();
            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
            return;
        }

        if (mob.getTarget() != null || mob.isAggressive()) {
            mob.clearSleepDesire();
            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
            return;
        }

        if (repickCooldownTicks > 0) repickCooldownTicks--;

        // 1) Acquire a target if we don't have one
        if (targetPos == null) {
            lastDistSqr = Double.NaN;
            noProgressTicks = 0;
            notMovingTicks = 0;
            lastPos = mob.position();

            if (repickCooldownTicks > 0) return;

            // ✅ Buddy-first acquisition (cheap and effective)
            if (info.sleepPreferSleepingBuddies()) {
                BlockPos buddySpot = SleepBuddyRelocator.findBuddyAdjacentSleepSpot(mob, info, mob.blockPosition());
                if (buddySpot != null) {
                    targetPos = buddySpot;
                }
            }

            // Otherwise fall back to full finder
            if (targetPos == null) {
                targetPos = SleepSpotFinder.findRoofedSleepSpot(mob, info);
            }

            repickCooldownTicks = 5;

            if (targetPos == null) {
                if (++noTargetTicks >= NO_TARGET_GIVE_UP_TICKS) {
                    mob.startSleepSearchCooldown(NO_TARGET_COOLDOWN_TICKS);
                    stop();
                }
                return;
            }

            noTargetTicks = 0;
            failedMoveToTicks = 0;

            double speed = Math.max(0.1D, info.wanderWalkSpeed());
            BlockPos pos = targetPos;
            if (!moveToTarget(pos, speed)) {
                mob.strikeSleepSpot(pos);
                targetPos = null;
                repickCooldownTicks = 0;
                noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
            }
            return;
        }

        BlockPos pos = targetPos;
        if (pos == null) return;

        // ------------------------------------------------------------
        // STUCK WATCHDOG
        // ------------------------------------------------------------
        {
            Vec3 now = mob.position();

            boolean navSaysMoving = mob.getNavigation().isInProgress();
            boolean actuallyMoved = (lastPos == null) || now.distanceToSqr(lastPos) > 0.0004D;

            if (navSaysMoving && !actuallyMoved) notMovingTicks++;
            else notMovingTicks = 0;

            lastPos = now;

            double distSqrToTarget = mob.distanceToSqr(Vec3.atBottomCenterOf(pos));
            if (!Double.isNaN(lastDistSqr)) {
                if (distSqrToTarget >= lastDistSqr - 0.05D) noProgressTicks++;
                else noProgressTicks = 0;
            }
            lastDistSqr = distSqrToTarget;

            if (notMovingTicks >= 30 || noProgressTicks >= 60) {
                mob.getNavigation().stop();
                mob.strikeSleepSpot(pos);

                targetPos = null;
                failedMoveToTicks = 0;
                repickCooldownTicks = 0;

                lastDistSqr = Double.NaN;
                noProgressTicks = 0;
                notMovingTicks = 0;
                lastPos = mob.position();

                noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 8);
                return;
            }
        }

        // 2) Keep navigation alive
        if (!mob.getNavigation().isInProgress() || mob.getNavigation().isDone()) {
            double speed = Math.max(0.1D, info.wanderWalkSpeed());
            if (!moveToTarget(pos, speed)) {
                if (++failedMoveToTicks >= 10) {
                    mob.strikeSleepSpot(pos);

                    targetPos = null;
                    failedMoveToTicks = 0;
                    repickCooldownTicks = 0;

                    lastDistSqr = Double.NaN;
                    noProgressTicks = 0;
                    notMovingTicks = 0;

                    noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
                }
                return;
            } else {
                failedMoveToTicks = 0;
            }
        }

        // 3) Arrival handling
        double distSqrToTarget = mob.distanceToSqr(Vec3.atBottomCenterOf(pos));
        if (distSqrToTarget > ARRIVAL_DIST_SQR) return;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(pos, roofMax);

        if (roofDy == -1) {
            mob.strikeSleepSpot(pos);
            targetPos = null;
            repickCooldownTicks = 0;

            lastDistSqr = Double.NaN;
            noProgressTicks = 0;
            notMovingTicks = 0;
            lastPos = mob.position();

            noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
            return;
        }

        // Buddy override at arrival (fine to keep)
        if (info.sleepBuddyCanOverrideMemory()) {
            BlockPos buddySpot = SleepBuddyRelocator.findBuddyAdjacentSleepSpot(mob, info, pos);

            if (buddySpot != null && !buddySpot.equals(pos)) {
                mob.strikeSleepSpot(pos);
                targetPos = buddySpot;
                failedMoveToTicks = 0;

                lastDistSqr = Double.NaN;
                noProgressTicks = 0;
                notMovingTicks = 0;
                lastPos = mob.position();

                double speed = Math.max(0.1D, info.wanderWalkSpeed());
                BlockPos pos2 = targetPos;
                if (!moveToTarget(pos2, speed)) {
                    mob.strikeSleepSpot(pos2);
                    targetPos = null;
                    repickCooldownTicks = 0;

                    noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
                }
                return;
            }
        }

        // Commit: sleep now
        mob.rememberSleepSpot(pos);
        mob.clearSleepDesire();
        mob.beginSleepingFromGoal();

        mob.getNavigation().stop();
        stop();
    }

    private boolean moveToTarget(BlockPos pos, double speed) {
        if (pos == null) return false;

        return mob.getNavigation().moveTo(
                pos.getX() + 0.5D,
                pos.getY(),
                pos.getZ() + 0.5D,
                speed
        );
    }
}
