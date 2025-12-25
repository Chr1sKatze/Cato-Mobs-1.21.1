package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * CatoRainShelterGoal
 *
 * Behavior:
 * - When it rains (and biome can have precipitation): occasionally decide to seek shelter (roofed position).
 * - Once roofed: prefer staying roofed until rain stops (prevents wandering out).
 * - Natural touch: sometimes "peek" into rain briefly, then return.
 * - While roofed: move around like normal wander, BUT only to roofed targets.
 *
 * Fixes:
 * - Don't shelter in dry biomes (savanna/desert) during global rain.
 * - If roof disappears (tree removed) and we fail to find new shelter, STOP the goal so wander can run.
 * - Roof-wander uses a small, clamped radius so it works under tiny shelters (trees).
 */
public class CatoRainShelterGoal extends Goal {

    private static final double ARRIVAL_DIST_SQR = 2.25D; // ~1.5 blocks

    private final CatoBaseMob mob;

    private long nextAttemptTick = 0L;

    private @Nullable BlockPos shelterPos = null;       // anchor roofed spot
    private @Nullable BlockPos peekTargetPos = null;    // temporary "peek into rain" destination

    // roofed-wander state
    private @Nullable BlockPos roofWanderTargetPos = null; // roofed “wander-like” target while sheltered
    private double roofWanderSpeed = 1.0D;                 // chosen walk/run speed for current target
    private int roofWanderCooldownTicks = 0;               // pacing between target picks

    private int lingerAfterRainTicks = 0;
    private int peekTicksRemaining = 0;

    private int repathCooldown = 0;

    // Peek scheduler (human-tunable)
    private long nextPeekAttemptTick = 0L;

    // ✅ New: if we can't find shelter after roof disappears, release control so wander can run
    private boolean forceStop = false;

    public CatoRainShelterGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------
    // Helpers to read SpeciesInfo (and clamp defensively)
    // ------------------------------------------------------------

    private CatoMobSpeciesInfo info() {
        return mob.getSpeciesInfo();
    }

    private boolean enabled() {
        return info().rainShelterEnabled();
    }

    private int attemptIntervalTicks() {
        return Math.max(1, info().rainShelterAttemptIntervalTicks());
    }

    private float attemptChance() {
        return Mth.clamp(info().rainShelterAttemptChance(), 0f, 1f);
    }

    private double searchRadiusBlocks() {
        return Math.max(4.0D, info().rainShelterSearchRadiusBlocks());
    }

    private int searchAttempts() {
        return Math.max(8, info().rainShelterSearchAttempts());
    }

    private int roofScanMaxBlocks() {
        return Math.max(1, info().rainShelterRoofScanMaxBlocks());
    }

    private double runToShelterSpeed() {
        return Math.max(0.05D, info().rainShelterRunToShelterSpeed());
    }

    private double rainWalkSpeed() {
        return Math.max(0.05D, info().rainShelterWalkSpeed());
    }

    private int lingerAfterRainCfgTicks() {
        return Math.max(0, info().rainShelterLingerAfterRainTicks());
    }

    // Peek is "avg interval" (human-tunable)
    private int peekAvgIntervalTicks() {
        return Math.max(0, info().rainShelterPeekAvgIntervalTicks());
    }

    private int peekMinTicks() {
        return Math.max(0, info().rainShelterPeekMinTicks());
    }

    private int peekMaxTicks() {
        return Math.max(peekMinTicks(), info().rainShelterPeekMaxTicks());
    }

    private double peekDistMin() {
        return Math.max(0.0D, info().rainShelterPeekDistanceMinBlocks());
    }

    private double peekDistMax() {
        return Math.max(peekDistMin(), info().rainShelterPeekDistanceMaxBlocks());
    }

    private int peekSearchAttempts() {
        return Math.max(0, info().rainShelterPeekSearchAttempts());
    }

    // Roof-wander uses the shuffle toggle + interval knobs
    private boolean roofWanderEnabled() {
        return enabled() && info().rainShelterShuffleEnabled();
    }

    private int roofWanderIntervalMinTicks() {
        return Math.max(1, info().rainShelterShuffleIntervalMinTicks());
    }

    private int roofWanderIntervalMaxTicks() {
        return Math.max(roofWanderIntervalMinTicks(), info().rainShelterShuffleIntervalMaxTicks());
    }

    private int roofWanderSearchAttempts() {
        return Math.max(0, info().rainShelterShuffleSearchAttempts());
    }

    // ------------------------------------------------------------
    // Goal gating
    // ------------------------------------------------------------

    private boolean shouldConsiderRainBehavior() {
        if (!enabled()) return false;
        if (mob.level().isClientSide) return false;

        if (mob.isSleeping()) return false;
        if (mob.isFleeing()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isAggressive()) return false;
        if (mob.angerTime > 0) return false;

        return true;
    }

    private boolean isRainingHere(Level level, BlockPos pos) {
        return level.isRaining() && level.isRainingAt(pos);
    }

    private boolean isRoofedHere(BlockPos standPos) {
        return mob.isRoofed(standPos, roofScanMaxBlocks());
    }

    /**
     * "Rain should matter here" gate:
     * - World is in rain state
     * - AND this biome can actually have precipitation (prevents savanna/desert sheltering)
     *
     * Note: we must NOT use isRainingAt(pos) here, because under a roof it becomes false.
     */
    private boolean isWetWeatherPossibleHere(Level level, BlockPos pos) {
        if (!level.isRaining()) return false;
        return level.getBiome(pos).value().hasPrecipitation();
    }

    // ------------------------------------------------------------
    // Vanilla goal API
    // ------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!shouldConsiderRainBehavior()) return false;

        Level level = mob.level();
        BlockPos here = mob.blockPosition();

        forceStop = false;

        if (!isWetWeatherPossibleHere(level, here)) return false;

        boolean roofed = isRoofedHere(here);

        if (!roofed) {
            long now = level.getGameTime();
            if (now < nextAttemptTick) return false;

            nextAttemptTick = now + attemptIntervalTicks();
            if (mob.getRandom().nextFloat() >= attemptChance()) return false;

            shelterPos = findNearbyRoofedStandPos(level);
            return shelterPos != null;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!shouldConsiderRainBehavior()) return false;

        if (forceStop) return false;

        Level level = mob.level();

        if (!isWetWeatherPossibleHere(level, mob.blockPosition())) {
            return lingerAfterRainTicks > 0 || mob.getNavigation().isInProgress();
        }

        return true;
    }

    @Override
    public void start() {
        repathCooldown = 0;

        peekTicksRemaining = 0;
        peekTargetPos = null;

        roofWanderTargetPos = null;
        roofWanderSpeed = rainWalkSpeed();
        roofWanderCooldownTicks = 0;

        lingerAfterRainTicks = 0;

        forceStop = false;

        scheduleNextPeekAttempt();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();

        shelterPos = null;
        peekTargetPos = null;
        peekTicksRemaining = 0;

        roofWanderTargetPos = null;
        roofWanderCooldownTicks = 0;

        lingerAfterRainTicks = 0;
        repathCooldown = 0;

        forceStop = false;

        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Level level = mob.level();
        BlockPos here = mob.blockPosition();

        // Rain not relevant here -> linger then release
        if (!isWetWeatherPossibleHere(level, here)) {
            if (lingerAfterRainTicks <= 0) lingerAfterRainTicks = lingerAfterRainCfgTicks();
            else lingerAfterRainTicks--;

            mob.getNavigation().stop();
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            return;
        }

        if (repathCooldown > 0) repathCooldown--;
        if (roofWanderCooldownTicks > 0) roofWanderCooldownTicks--;

        // Peek in progress
        if (peekTicksRemaining > 0) {
            peekTicksRemaining--;

            if (peekTargetPos == null) {
                peekTicksRemaining = 0;
            } else {
                if (repathCooldown <= 0) {
                    repathCooldown = 10;
                    moveTo(peekTargetPos, rainWalkSpeed());
                }

                mob.setMoveMode(mob.getNavigation().isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);

                if (peekTicksRemaining <= 0) {
                    peekTargetPos = null;
                    mob.getNavigation().stop();
                    scheduleNextPeekAttempt();
                }
                return;
            }
        }

        // If not roofed -> go find shelter and run there
        boolean roofedNow = isRoofedHere(here);

        if (!roofedNow) {
            // roof disappeared at our anchor? re-find
            if (shelterPos == null || !isRoofedHere(shelterPos)) {
                shelterPos = findNearbyRoofedStandPos(level);
            }

            if (shelterPos == null) {
                // ✅ Key fix:
                // We couldn't find any shelter. Don't "hold" this goal forever (blocking wander).
                // Stop controlling so lower-priority wander can run this tick onward.
                mob.getNavigation().stop();
                mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
                forceStop = true;
                return;
            }

            if (repathCooldown <= 0) {
                repathCooldown = 10;
                moveTo(shelterPos, runToShelterSpeed());
            }

            mob.setMoveMode(mob.getNavigation().isInProgress() ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);
            mob.getLookControl().setLookAt(
                    shelterPos.getX() + 0.5,
                    shelterPos.getY() + 0.5,
                    shelterPos.getZ() + 0.5
            );
            return;
        }

        // Roofed now -> roof-wander + optional peek
        if (shelterPos == null) shelterPos = here.immutable();

        // Keep moving to current roof-wander target
        if (roofWanderTargetPos != null) {
            double d = mob.distanceToSqr(
                    roofWanderTargetPos.getX() + 0.5,
                    roofWanderTargetPos.getY(),
                    roofWanderTargetPos.getZ() + 0.5
            );

            if (d <= ARRIVAL_DIST_SQR || !isRoofedHere(roofWanderTargetPos)) {
                roofWanderTargetPos = null;
                mob.getNavigation().stop();
            } else {
                if (repathCooldown <= 0) {
                    repathCooldown = 10;
                    moveTo(roofWanderTargetPos, roofWanderSpeed);
                }

                mob.setMoveMode(mob.getNavigation().isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);
                return;
            }
        }

        // Pick a new roofed target occasionally (only if roof-wander enabled)
        if (roofWanderEnabled() && roofWanderCooldownTicks <= 0) {
            roofWanderCooldownTicks = randomBetween(roofWanderIntervalMinTicks(), roofWanderIntervalMaxTicks());

            BlockPos t = findNearbyRoofedWanderPosSmallRadius(level, shelterPos);
            if (t != null && !t.equals(here)) {
                roofWanderTargetPos = t;
                roofWanderSpeed = chooseShelteredWanderSpeed(t);
                repathCooldown = 0;

                moveTo(roofWanderTargetPos, roofWanderSpeed);
                mob.setMoveMode(CatoBaseMob.MOVE_WALK);
                return;
            }
        }

        // Idle under roof
        mob.getNavigation().stop();
        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        tryStartPeek(level);
    }

    // ------------------------------------------------------------
    // Peek scheduling (avg interval)
    // ------------------------------------------------------------

    private void scheduleNextPeekAttempt() {
        int avg = peekAvgIntervalTicks();
        if (avg <= 0) {
            nextPeekAttemptTick = Long.MAX_VALUE;
            return;
        }

        int jitter = Math.max(1, avg / 4);
        int delta = avg + randomBetweenSigned(-jitter, jitter);
        delta = Math.max(20, delta);

        nextPeekAttemptTick = mob.level().getGameTime() + delta;
    }

    private void tryStartPeek(Level level) {
        int avg = peekAvgIntervalTicks();
        if (avg <= 0) return;

        long now = level.getGameTime();
        if (now < nextPeekAttemptTick) return;

        scheduleNextPeekAttempt();

        BlockPos peek = findNearbyNotRoofedStandPos(level, shelterPos);
        if (peek != null) {
            peekTargetPos = peek;
            peekTicksRemaining = randomBetween(peekMinTicks(), peekMaxTicks());
            repathCooldown = 0;
            moveTo(peekTargetPos, rainWalkSpeed());
        }
    }

    // ------------------------------------------------------------
    // Navigation helpers
    // ------------------------------------------------------------

    private void moveTo(BlockPos standPos, double speed) {
        mob.getNavigation().moveTo(
                standPos.getX() + 0.5D,
                standPos.getY(),
                standPos.getZ() + 0.5D,
                Math.max(0.05D, speed)
        );
    }

    private int randomBetween(int min, int max) {
        int a = Math.max(0, min);
        int b = Math.max(a, max);
        if (b == a) return a;
        return a + mob.getRandom().nextInt(b - a + 1);
    }

    private int randomBetweenSigned(int min, int max) {
        int a = Math.min(min, max);
        int b = Math.max(min, max);
        if (a == b) return a;
        return a + mob.getRandom().nextInt(b - a + 1);
    }

    // ------------------------------------------------------------
    // Shelter / roof-wander finders
    // ------------------------------------------------------------

    private @Nullable BlockPos findNearbyRoofedStandPos(Level level) {
        BlockPos center = (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null)
                ? mob.getHomePos()
                : mob.blockPosition();

        double radius = searchRadiusBlocks();
        int attempts = searchAttempts();
        int roofMax = roofScanMaxBlocks();

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int i = 0; i < attempts; i++) {
            double ang = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = mob.getRandom().nextDouble() * radius;

            int x = center.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = mob.blockPosition().getY() + 8;

            BlockPos probe = new BlockPos(x, y, z);
            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos stand = ground.above();

            if (!level.isEmptyBlock(stand)) continue;
            if (mob.roofDistance(stand, roofMax) == -1) continue;

            var path = mob.getNavigation().createPath(stand, 0);
            if (path == null || !path.canReach()) continue;

            double d = mob.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = stand.immutable();
            }
        }

        return best;
    }

    /**
     * ✅ Roof-wander finder tuned for SMALL shelters:
     * - Uses a clamped radius (0..min(wanderMax, 8))
     * - This massively improves behavior under trees / small overhangs.
     */
    private @Nullable BlockPos findNearbyRoofedWanderPosSmallRadius(Level level, BlockPos anchor) {
        int attempts = Math.max(8, roofWanderSearchAttempts());
        int roofMax = roofScanMaxBlocks();

        double max = Math.max(1.5D, info().wanderMaxRadius());
        max = Math.min(max, 8.0D); // <-- key clamp

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int i = 0; i < attempts; i++) {
            double ang = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = mob.getRandom().nextDouble() * max; // 0..max

            int x = anchor.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = anchor.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = mob.blockPosition().getY() + 6;

            BlockPos probe = new BlockPos(x, y, z);
            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos stand = ground.above();
            if (!level.isEmptyBlock(stand)) continue;

            if (mob.roofDistance(stand, roofMax) == -1) continue;

            var path = mob.getNavigation().createPath(stand, 0);
            if (path == null || !path.canReach()) continue;

            double d = mob.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            if (d < bestDist) {
                bestDist = d;
                best = stand.immutable();
            }
        }

        return best;
    }

    private double chooseShelteredWanderSpeed(BlockPos target) {
        double walk = Math.max(0.05D, info().wanderWalkSpeed());
        double run = Math.max(walk, info().wanderRunSpeed());
        float runChance = Mth.clamp(info().wanderRunChance(), 0f, 1f);

        double threshold = info().wanderRunDistanceThreshold();
        if (threshold > 0.0D) {
            double d = mob.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (d < (threshold * threshold)) {
                return walk;
            }
        }

        return (mob.getRandom().nextFloat() < runChance) ? run : walk;
    }

    private @Nullable BlockPos findNearbyNotRoofedStandPos(Level level, BlockPos from) {
        double min = peekDistMin();
        double max = peekDistMax();

        int attempts = Math.max(8, peekSearchAttempts());
        int roofMax = roofScanMaxBlocks();

        for (int i = 0; i < attempts; i++) {
            double ang = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = (max <= min) ? min : (min + mob.getRandom().nextDouble() * (max - min));

            int x = from.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = from.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = mob.blockPosition().getY() + 8;

            BlockPos probe = new BlockPos(x, y, z);
            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos stand = ground.above();
            if (!level.isEmptyBlock(stand)) continue;

            if (mob.roofDistance(stand, roofMax) != -1) continue;

            if (!isRainingHere(level, stand)) continue;

            var path = mob.getNavigation().createPath(stand, 0);
            if (path == null || !path.canReach()) continue;

            return stand.immutable();
        }

        return null;
    }

    private @Nullable BlockPos findGround(Level level, BlockPos start) {
        BlockPos pos = start;

        while (pos.getY() < level.getMaxBuildHeight() && !level.isEmptyBlock(pos)) {
            pos = pos.above();
        }

        while (pos.getY() > level.getMinBuildHeight() && level.isEmptyBlock(pos)) {
            pos = pos.below();
        }

        if (level.isEmptyBlock(pos)) return null;
        return pos;
    }
}
