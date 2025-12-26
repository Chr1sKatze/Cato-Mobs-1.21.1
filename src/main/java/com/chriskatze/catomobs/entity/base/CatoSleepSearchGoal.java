package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class CatoSleepSearchGoal extends Goal {

    private static final double ARRIVAL_DIST_SQR = 2.0D;

    // prevent "standing still with MOVE flag" when no target exists
    private static final int NO_TARGET_GIVE_UP_TICKS = 20;   // ~1s
    private static final int NO_TARGET_COOLDOWN_TICKS = 40;  // ~2s

    // Perf: run stuck watchdog every N ticks (2 = ~10x/sec)
    private static final int STUCK_CHECK_INTERVAL = 2;

    private final CatoBaseMob mob;

    private BlockPos targetPos = null;

    // cached target center (no Vec3 allocations)
    private double targetX, targetY, targetZ;

    private int searchTimeoutTicks = 0;
    private int failedMoveToTicks = 0;

    // reduce CPU by not running the expensive finder every tick
    private int repickCooldownTicks = 0;

    // counts ticks with no usable target
    private int noTargetTicks = 0;

    // Robust stuck detection
    private Vec3 lastPos = null;
    private int notMovingTicks = 0;
    private double lastDistSqr = Double.NaN;
    private int noProgressTicks = 0;

    // perf ticker
    private int tickCounter = 0;

    public CatoSleepSearchGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        final CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (!info.sleepEnabled()) return false;
        if (mob.isSleeping()) return false;
        if (mob.isSleepSearching()) return false;
        if (mob.isSleepSearchOnCooldown()) return false;

        // FLEE GATE
        if (mob.isFleeing()) return false;

        if (mob.getTarget() != null || mob.isAggressive()) return false;

        boolean isDay = mob.level().isDay();
        if (isDay && !info.sleepAtDay()) return false;
        if (!isDay && !info.sleepAtNight()) return false;

        if (!info.sleepRequiresRoof()) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        if (mob.isRoofed(mob.blockPosition(), roofMax)) return false;

        if (!mob.wantsToSleepNow()) return false;

        BlockPos found = SleepSpotFinder.findRoofedSleepSpot(mob, info);
        if (found == null) return false;

        setTarget(found);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isSleeping()) return false;

        // abort search immediately if fleeing starts mid-search
        if (mob.isFleeing()) return false;

        final CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        if (mob.getTarget() != null || mob.isAggressive()) return false;

        if (info.wakeOnAir() && !mob.onGround()) return false;
        if (info.wakeOnTouchingWater() && mob.isInWater() && !info.sleepAllowedOnWaterSurface()) return false;
        if (info.wakeOnUnderwater() && mob.isUnderWater()) return false;

        return searchTimeoutTicks > 0;
    }

    @Override
    public void start() {
        mob.setSleepSearching(true);

        final CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        searchTimeoutTicks = Math.max(1, info.sleepSearchTimeoutTicks());
        failedMoveToTicks = 0;
        repickCooldownTicks = 0;
        noTargetTicks = 0;

        lastPos = mob.position();
        notMovingTicks = 0;
        lastDistSqr = Double.NaN;
        noProgressTicks = 0;

        tickCounter = 0;

        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));

        if (this.targetPos != null) {
            double speed = Math.max(0.1D, info.wanderWalkSpeed());
            BlockPos pos = this.targetPos;

            if (!moveToTarget(pos, speed)) {
                mob.strikeSleepSpot(pos);
                clearTarget();
                this.noTargetTicks = NO_TARGET_GIVE_UP_TICKS;
            }
        }
    }

    @Override
    public void stop() {
        mob.setSleepSearching(false);
        clearTarget();

        searchTimeoutTicks = 0;
        failedMoveToTicks = 0;
        repickCooldownTicks = 0;
        noTargetTicks = 0;

        lastPos = null;
        notMovingTicks = 0;
        lastDistSqr = Double.NaN;
        noProgressTicks = 0;

        tickCounter = 0;

        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // ✅ Use cached per-tick info when available (your CatoBaseMob cache)
        final CatoMobSpeciesInfo info = mob.infoServer();

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

        tickCounter++;

        // 1) Acquire a target if we don't have one
        if (targetPos == null) {
            resetProgressTrackers();

            if (repickCooldownTicks > 0) return;

            // Buddy-first acquisition (cheap and effective)
            if (info.sleepPreferSleepingBuddies()) {
                BlockPos buddySpot = SleepBuddyRelocator.findBuddyAdjacentSleepSpot(mob, info, mob.posServer());
                if (buddySpot != null) {
                    setTarget(buddySpot);
                }
            }

            // Otherwise fall back to full finder
            if (targetPos == null) {
                BlockPos found = SleepSpotFinder.findRoofedSleepSpot(mob, info);
                if (found != null) setTarget(found);
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
                clearTarget();
                repickCooldownTicks = 0;
                noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
            }
            return;
        }

        final BlockPos pos = targetPos;
        if (pos == null) return;

        // ------------------------------------------------------------
        // STUCK WATCHDOG (throttled)
        // ------------------------------------------------------------
        if ((tickCounter % STUCK_CHECK_INTERVAL) == 0) {
            Vec3 now = mob.position();

            boolean navSaysMoving = mob.getNavigation().isInProgress();
            boolean actuallyMoved = (lastPos == null) || now.distanceToSqr(lastPos) > 0.0004D;

            if (navSaysMoving && !actuallyMoved) notMovingTicks++;
            else notMovingTicks = 0;

            lastPos = now;

            double distSqrToTarget = mob.distanceToSqr(targetX, targetY, targetZ);
            if (!Double.isNaN(lastDistSqr)) {
                if (distSqrToTarget >= lastDistSqr - 0.05D) noProgressTicks++;
                else noProgressTicks = 0;
            }
            lastDistSqr = distSqrToTarget;

            if (notMovingTicks >= 30 || noProgressTicks >= 60) {
                mob.getNavigation().stop();
                mob.strikeSleepSpot(pos);

                clearTarget();
                failedMoveToTicks = 0;
                repickCooldownTicks = 0;

                resetProgressTrackers();
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

                    clearTarget();
                    failedMoveToTicks = 0;
                    repickCooldownTicks = 0;

                    resetProgressTrackers();
                    noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
                }
                return;
            } else {
                failedMoveToTicks = 0;
            }
        }

        // 3) Arrival handling (no Vec3 alloc)
        double distSqrToTarget = mob.distanceToSqr(targetX, targetY, targetZ);
        if (distSqrToTarget > ARRIVAL_DIST_SQR) return;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(pos, roofMax);

        if (roofDy == -1) {
            mob.strikeSleepSpot(pos);
            clearTarget();
            repickCooldownTicks = 0;

            resetProgressTrackers();
            noTargetTicks = Math.min(NO_TARGET_GIVE_UP_TICKS, noTargetTicks + 5);
            return;
        }

        // Buddy override at arrival
        if (info.sleepBuddyCanOverrideMemory()) {
            BlockPos buddySpot = SleepBuddyRelocator.findBuddyAdjacentSleepSpot(mob, info, pos);

            if (buddySpot != null && !buddySpot.equals(pos)) {
                mob.strikeSleepSpot(pos);
                setTarget(buddySpot);
                failedMoveToTicks = 0;

                resetProgressTrackers();

                double speed = Math.max(0.1D, info.wanderWalkSpeed());
                BlockPos pos2 = targetPos;

                if (!moveToTarget(pos2, speed)) {
                    mob.strikeSleepSpot(pos2);
                    clearTarget();
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

    private void resetProgressTrackers() {
        lastDistSqr = Double.NaN;
        noProgressTicks = 0;
        notMovingTicks = 0;
        lastPos = mob.position();
    }

    private void setTarget(BlockPos pos) {
        this.targetPos = pos;
        if (pos != null) {
            this.targetX = pos.getX() + 0.5D;
            this.targetY = pos.getY();
            this.targetZ = pos.getZ() + 0.5D;
        }
    }

    private void clearTarget() {
        this.targetPos = null;
        this.targetX = this.targetY = this.targetZ = 0.0D;
    }

    private boolean moveToTarget(BlockPos pos, double speed) {
        if (pos == null) return false;

        // Use cached target center if this is the current target (no recompute)
        final double x, y, z;
        if (pos.equals(this.targetPos)) {
            x = targetX; y = targetY; z = targetZ;
        } else {
            x = pos.getX() + 0.5D;
            y = pos.getY();
            z = pos.getZ() + 0.5D;
        }

        return mob.getNavigation().moveTo(x, y, z, speed);
    }
}
