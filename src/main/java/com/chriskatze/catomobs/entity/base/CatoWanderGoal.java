package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobMovementType;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.registry.CMBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

import java.util.EnumSet;

/**
 * CatoWanderGoal
 */
public class CatoWanderGoal extends Goal {

    private final CatoBaseMob mob;

    private final double walkSpeed;
    private final double runSpeed;
    private final float runChance;
    private final double minRadius;
    private final double maxRadius;
    private final double runDistanceThreshold;

    private double wantedX;
    private double wantedY;
    private double wantedZ;

    private boolean running;

    private int notMovingTicks = 0;

    // ✅ NEW: attempt pacing (like sleep), stored per-goal per-mob
    private long nextWanderAttemptTick = 0L;

    // Reused mutable to avoid BlockPos allocations in pickPosition/findGround
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    public CatoWanderGoal(
            CatoBaseMob mob,
            double walkSpeed,
            double runSpeed,
            float runChance,
            double minRadius,
            double maxRadius,
            double runDistanceThreshold
    ) {
        this.mob = mob;
        this.walkSpeed = walkSpeed;
        this.runSpeed = runSpeed;
        this.runChance = runChance;
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.runDistanceThreshold = runDistanceThreshold;

        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.isSleeping()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isAggressive()) return false;
        if (mob.angerTime > 0) return false;

        if (mob.isPassenger() || mob.isVehicle()) return false;
        if (!mob.getNavigation().isDone()) return false;

        final CatoMobSpeciesInfo info = mob.infoServer();
        final long now = mob.nowServer();

        // ✅ Interval gate (pacing)
        if (now < nextWanderAttemptTick) return false;

        final int interval = Math.max(1, info.wanderAttemptIntervalTicks());
        final float chance = clamp01(info.wanderAttemptChance());

        // Schedule next attempt immediately (even if we fail chance/pick)
        // Spread attempts across ticks so big herds don't spike on the same tick.
        nextWanderAttemptTick = now + interval + (mob.getId() & 7);

        // Chance roll only once per interval (not every tick)
        if (chance <= 0.0F) return false;
        if (chance < 1.0F && mob.getRandom().nextFloat() >= chance) return false;

        // Allocation-free picker; sets wantedX/Y/Z + running
        return this.pickPositionAndStore();
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isSleeping()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isAggressive()) return false;
        if (mob.angerTime > 0) return false;

        return mob.getNavigation().isInProgress();
    }

    @Override
    public void start() {
        double speed = running ? runSpeed : walkSpeed;

        mob.setMoveMode(running ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_WALK);
        mob.onWanderStart(running);

        mob.getNavigation().moveTo(this.wantedX, this.wantedY, this.wantedZ, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();

        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
        mob.onWanderStop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        boolean moving = mob.getNavigation().isInProgress();

        if (!moving) {
            notMovingTicks++;
            if (notMovingTicks >= 2) {
                mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            }
            return;
        }

        notMovingTicks = 0;
        mob.setMoveMode(running ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_WALK);
    }

    private boolean pickPositionAndStore() {
        final Level level = mob.level();
        final CatoMobSpeciesInfo info = mob.infoServer(); // uses your tick cache when available
        final CatoMobMovementType moveType = info.movementType();
        final var pref = info.surfacePreference();

        final BlockPos centerPos = (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null)
                ? mob.getHomePos()
                : mob.posServer(); // cached

        final int centerX = centerPos.getX();
        final int centerZ = centerPos.getZ();

        final boolean clampToHome = mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null;
        final double homeRadius = clampToHome ? mob.getHomeRadius() : Double.MAX_VALUE;
        final double homeRadiusSqr = homeRadius * homeRadius;

        // Match your original intent: use current block position Y, search a bit above
        final int baseY = mob.posServer().getY();

        int bestX = 0, bestY = 0, bestZ = 0;
        double bestScore = -Double.MAX_VALUE;
        double bestDist = 0.0D;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = this.minRadius + mob.getRandom().nextDouble() * (this.maxRadius - this.minRadius);

            double dx = Math.cos(angle) * dist;
            double dz = Math.sin(angle) * dist;

            int x = centerX + Mth.floor(dx);
            int z = centerZ + Mth.floor(dz);
            int y = baseY + 8;

            if (clampToHome) {
                int dxHome = x - centerX;
                int dzHome = z - centerZ;
                if ((double) (dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) continue;
            }

            int groundY = findGroundY(level, x, y, z);
            if (groundY == Integer.MIN_VALUE) continue;

            scratch.set(x, groundY, z);
            if (moveType == CatoMobMovementType.LAND) {
                if (!level.getFluidState(scratch).isEmpty()) continue;
            }

            int standY = groundY + 1;

            scratch.set(x, standY, z);
            if (!level.isEmptyBlock(scratch)) continue;

            if (moveType == CatoMobMovementType.LAND) {
                if (!level.getFluidState(scratch).isEmpty()) continue;
            }

            double prefScore = 0.0D;
            if (pref != null) {
                // Note: with empty-block requirement, this is typically "solid" anyway
                boolean inWater = !level.getFluidState(scratch).isEmpty();
                prefScore += inWater ? pref.preferWaterSurfaceWeight() : pref.preferSolidSurfaceWeight();

                if (!inWater) {
                    scratch.set(x, groundY, z);
                    var belowState = level.getBlockState(scratch);
                    if (belowState.is(CMBlockTags.SOFT_GROUND)) prefScore += pref.preferSoftGroundWeight();
                    if (belowState.is(CMBlockTags.HARD_GROUND)) prefScore += pref.preferHardGroundWeight();
                }
            }

            double d2 = mob.distanceToSqr(x + 0.5D, standY, z + 0.5D);
            double score = (prefScore * 10.0D) - (d2 * 0.01D);

            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestY = standY;
                bestZ = z;
                bestDist = dist;
            }
        }

        if (bestScore == -Double.MAX_VALUE) return false;

        decideRunOrWalk(bestDist);

        this.wantedX = bestX + 0.5D;
        this.wantedY = bestY;
        this.wantedZ = bestZ + 0.5D;

        return true;
    }

    private void decideRunOrWalk(double distance) {
        boolean canRun = (runSpeed > 0.0D);
        if (!canRun) {
            this.running = false;
            return;
        }

        boolean forceRun = (runDistanceThreshold > 0.0D && distance >= runDistanceThreshold);

        if (forceRun) {
            this.running = true;
        } else if (runChance > 0.0F && mob.getRandom().nextFloat() < this.runChance) {
            this.running = true;
        } else {
            this.running = false;
        }
    }

    private int findGroundY(Level level, int x, int startY, int z) {
        int y = Mth.clamp(startY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);

        scratch.set(x, y, z);
        while (y < level.getMaxBuildHeight() - 1 && !level.isEmptyBlock(scratch)) {
            y++;
            scratch.setY(y);
        }

        while (y > level.getMinBuildHeight() && level.isEmptyBlock(scratch)) {
            y--;
            scratch.setY(y);
        }

        if (level.isEmptyBlock(scratch)) return Integer.MIN_VALUE;
        return y;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
