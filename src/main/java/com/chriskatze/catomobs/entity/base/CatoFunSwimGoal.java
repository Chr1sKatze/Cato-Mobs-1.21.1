package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobMovementType;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

public class CatoFunSwimGoal extends Goal {

    private final CatoBaseMob mob;

    // -1 means: not scheduled yet (prevents immediate start on spawn)
    private long nextCheckTick = -1L;

    private @Nullable BlockPos waterTarget = null;
    private @Nullable BlockPos exitTarget = null;

    private int swimTicksLeft = 0;
    private int repathCooldown = 0;

    // Temporary pathfinding malus override (so LAND mobs can still path into water for fun swim)
    private boolean malusOverridden = false;
    private float prevWaterMalus = 0.0F;
    private float prevWaterBorderMalus = 0.0F;

    public CatoFunSwimGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide) return false;

        final Level level = mob.level();
        final CatoMobSpeciesInfo info = mob.infoServer();

        if (!info.funSwimEnabled()) return false;
        if (info.movementType() != CatoMobMovementType.LAND) return false;

        // Don’t start during other “important” states
        if (mob.isSleeping()) return false;
        if (mob.isFleeing()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isAggressive()) return false;
        if (mob.angerTime > 0) return false;

        // Don’t interrupt an existing path
        if (!mob.getNavigation().isDone()) return false;

        if (info.funSwimAvoidNight() && !level.isDay()) return false;

        if (info.funSwimOnlyIfSunny()) {
            if (!level.isDay()) return false;
            if (level.isRaining() || level.isThundering()) return false;
            if (!level.canSeeSky(mob.posServer())) return false; // ✅ cached pos
        }

        final long now = mob.nowServer(); // ✅ cached tick time
        final int interval = Math.max(20, info.funSwimCheckIntervalTicks());

        // First-ever schedule: wait a full interval after spawn before even rolling chance.
        if (nextCheckTick < 0L) {
            nextCheckTick = now + interval;
            return false;
        }

        if (now < nextCheckTick) return false;
        nextCheckTick = now + interval;

        final float chance = Mth.clamp(info.funSwimChance(), 0f, 1f);
        if (mob.getRandom().nextFloat() >= chance) return false;

        final int radius = Math.max(4, (int) Math.ceil(info.funSwimSearchRadius()));
        final int attempts = Math.max(8, info.funSwimSearchAttempts());

        final BlockPos center = mob.posServer(); // ✅ cached pos
        waterTarget = findNearbyWaterStandPos(level, center, radius, attempts);
        return waterTarget != null;
    }

    @Override
    public void start() {
        final CatoMobSpeciesInfo info = mob.infoServer();

        overrideWaterMalus();

        swimTicksLeft = Math.max(20, info.funSwimDurationTicks());
        exitTarget = null;
        repathCooldown = 0;

        if (waterTarget != null) {
            moveTo(waterTarget, 1.0D);
            mob.setMoveMode(CatoBaseMob.MOVE_WALK);
        }
    }

    @Override
    public boolean canContinueToUse() {
        final CatoMobSpeciesInfo info = mob.infoServer();

        if (!info.funSwimEnabled()) return false;

        if (mob.isSleeping()) return false;
        if (mob.isFleeing()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isAggressive()) return false;
        if (mob.angerTime > 0) return false;

        if (swimTicksLeft > 0) return true;
        return exitTarget != null && mob.getNavigation().isInProgress();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }


    @Override
    public void tick() {
        if (repathCooldown > 0) repathCooldown--;

        if (swimTicksLeft > 0) {
            swimTicksLeft--;

            if (!mob.isInWater()) {
                if (waterTarget != null && repathCooldown <= 0) {
                    repathCooldown = 10;
                    moveTo(waterTarget, 1.0D);
                }
                mob.setMoveMode(mob.getNavigation().isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);
                return;
            }

            if (waterTarget != null && repathCooldown <= 0) {
                double d2 = mob.distanceToSqr(
                        waterTarget.getX() + 0.5,
                        waterTarget.getY(),
                        waterTarget.getZ() + 0.5
                );

                if (d2 > 36.0D) {
                    repathCooldown = 10;
                    moveTo(waterTarget, 1.0D);
                }
            }

            mob.setMoveMode(mob.getNavigation().isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);
            return;
        }

        if (exitTarget == null) {
            final CatoMobSpeciesInfo info = mob.infoServer();
            final int radius = Math.max(8, (int) Math.ceil(info.funSwimSearchRadius()));

            exitTarget = WaterExitFinder.findNearestDryStandableBounded(mob, Math.min(radius, 16));

            if (exitTarget != null) {
                moveTo(exitTarget, 1.1D);
                repathCooldown = 10;
            } else {
                mob.getNavigation().stop();
                mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
                return;
            }
        }

        if (!mob.isInWater() && mob.getNavigation().isDone()) {
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            return;
        }

        if (repathCooldown <= 0 && exitTarget != null) {
            repathCooldown = 10;
            moveTo(exitTarget, 1.1D);
        }

        mob.setMoveMode(mob.getNavigation().isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);
    }

    @Override
    public void stop() {
        if (!mob.level().isClientSide && mob.isInWater()) {
            mob.requestExitWater();
        }

        mob.getNavigation().stop();
        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        restoreWaterMalus();

        waterTarget = null;
        exitTarget = null;
        swimTicksLeft = 0;
        repathCooldown = 0;

        final CatoMobSpeciesInfo info = mob.infoServer();
        nextCheckTick = mob.nowServer() + Math.max(20, info.funSwimCheckIntervalTicks());
    }

    private void moveTo(BlockPos standPos, double speed) {
        mob.getNavigation().moveTo(
                standPos.getX() + 0.5D,
                standPos.getY(),
                standPos.getZ() + 0.5D,
                Math.max(0.05D, speed)
        );
    }

    private void overrideWaterMalus() {
        if (malusOverridden) return;

        prevWaterMalus = mob.getPathfindingMalus(PathType.WATER);
        prevWaterBorderMalus = mob.getPathfindingMalus(PathType.WATER_BORDER);

        mob.setPathfindingMalus(PathType.WATER, 0.0F);
        mob.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);

        malusOverridden = true;
    }

    private void restoreWaterMalus() {
        if (!malusOverridden) return;

        mob.setPathfindingMalus(PathType.WATER, prevWaterMalus);
        mob.setPathfindingMalus(PathType.WATER_BORDER, prevWaterBorderMalus);

        malusOverridden = false;
    }

    private @Nullable BlockPos findNearbyWaterStandPos(Level level, BlockPos center, int searchRadius, int searchAttempts) {
        for (int i = 0; i < searchAttempts; i++) {
            double ang = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = mob.getRandom().nextDouble() * searchRadius;

            int x = center.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = center.getY() + 4;

            BlockPos probe = new BlockPos(x, y, z);

            // scan downward more to catch rivers below/next to slopes
            for (int dy = 0; dy < 24; dy++) {
                BlockPos p = probe.below(dy);
                var fluid = level.getFluidState(p);
                if (fluid.is(Fluids.WATER)) {
                    // Prefer a "wade" position: water with air above
                    if (level.isEmptyBlock(p.above())) {
                        return p.immutable();
                    }
                }
            }
        }
        return null;
    }
}
