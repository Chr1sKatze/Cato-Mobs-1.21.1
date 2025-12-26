package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.registry.CMBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * CatoRainShelterGoal
 *
 * Anti-jitter fixes:
 * - Post-peek cooldown (prevents rapid peek spam)
 * - "crossing grace" when moving from one roofed area to another roofed area,
 *   so mobs don't ping-pong when a short rain gap exists between two roofs.
 *
 * Visual shelter fixes:
 * - Candidate must be visually dry at FEET + HEAD + ABOVE_HEAD (3 vertical samples)
 * - Prefer deeper cover using a 3x3 neighborhood score at those 3 heights (max=27)
 *
 * Movement fix:
 * - Tightened arrival threshold so mobs don't stop ~1 block short of the shelter target.
 *
 * Deep-cover fix:
 * - Prefer / require a minimum "cover depth" (how many blocks from the nearest rainy edge).
 * - Two-pass selection: strict (requires MIN_COVER_DEPTH_BLOCKS) then fallback (relaxed).
 *
 * Anti-crowding fix (NEW):
 * - Occupancy-aware candidate selection: avoid selecting positions currently occupied by other CatoBaseMobs
 *   to prevent "fighting" for the best spot.
 *
 * Calm-under-cover fix (NEW):
 * - "Settle" timer: when already safely sheltered (dry + deep), the mob chills for a few seconds,
 *   reducing micro-repathing and constant shuffling in groups.
 *
 * NOTE: Per your request, we did NOT add "immediate shelter when raining at here" bypass in canUse().
 */
public class CatoRainShelterGoal extends Goal {

    // ✅ tighter arrival: prevents "stop 1 block short"
    private static final double ARRIVAL_DIST_SQR = 0.36D; // ~0.6 blocks

    // Hard cap for vertical ground scans (prevents worst-case spikes in weird terrain)
    private static final int FIND_GROUND_MAX_STEPS = 64;

    // 6–12 seconds cooldown after finishing a peek
    private static final int POST_PEEK_COOLDOWN_MIN_TICKS = 120; // 6s
    private static final int POST_PEEK_COOLDOWN_MAX_TICKS = 240; // 12s

    // Allow brief rain crossing when heading to another roofed target
    private static final int CROSS_TO_ROOF_GRACE_TICKS = 60; // 3s (tune: 20..60)

    // Deep cover scoring:
    // 3x3 neighborhood at 3 heights (feet/head/above_head) => max score = 27
    private static final int MIN_DRY_NEIGHBORHOOD_SCORE = 20; // tune 20..26

    // ✅ minimum "how deep inside the dry area" we want to stand
    private static final int MIN_COVER_DEPTH_BLOCKS = 2; // tune: 2, 3, or 4

    // ✅ how far we look outward to find the rainy edge
    private static final int MAX_EDGE_SCAN = 5; // 4..8 is typical

    // ✅ Anti-crowding: treat a stand position as occupied if another CatoBaseMob is basically on it
    private static final double OCCUPIED_RADIUS = 0.65D; // blocks around the stand position
    private static final int OCCUPIED_Y_INFLATE = 2;      // vertical tolerance

    private final CatoBaseMob mob;

    private long nextAttemptTick = 0L;

    private @Nullable BlockPos shelterPos = null;       // anchor roofed spot
    private @Nullable BlockPos peekTargetPos = null;    // temporary "peek into rain" destination
    private int postPeekCooldownTicks = 0;              // blocks repeated peeks

    // roofed-wander state
    private @Nullable BlockPos roofWanderTargetPos = null; // roofed “wander-like” target while sheltered
    private double roofWanderSpeed = 1.0D;                 // chosen walk/run speed for current target
    private int roofWanderCooldownTicks = 0;               // pacing between target picks

    // if we are transitioning between two roofed areas, allow brief unroofed travel
    private int crossToRoofGraceTicks = 0;

    private int lingerAfterRainTicks = 0;
    private int peekTicksRemaining = 0;

    private int repathCooldown = 0;

    // Peek scheduler (human-tunable)
    private long nextPeekAttemptTick = 0L;

    // If we can't find shelter after roof disappears, release control so wander can run
    private boolean forceStop = false;

    // ✅ Calm-under-cover: while settled, we refuse to shuffle/peek/repath
    private int settleTicks = 0;

    public CatoRainShelterGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------
    // Helpers to read SpeciesInfo (cached) + clamp defensively
    // ------------------------------------------------------------

    private CatoMobSpeciesInfo info() {
        return mob.infoServer();
    }

    private boolean enabled(CatoMobSpeciesInfo info) {
        return info.rainShelterEnabled();
    }

    private int attemptIntervalTicks(CatoMobSpeciesInfo info) {
        return Math.max(1, info.rainShelterAttemptIntervalTicks());
    }

    private float attemptChance(CatoMobSpeciesInfo info) {
        return Mth.clamp(info.rainShelterAttemptChance(), 0f, 1f);
    }

    private double searchRadiusBlocks(CatoMobSpeciesInfo info) {
        return Math.max(4.0D, info.rainShelterSearchRadiusBlocks());
    }

    private int searchAttempts(CatoMobSpeciesInfo info) {
        return Math.max(8, info.rainShelterSearchAttempts());
    }

    private int roofScanMaxBlocks(CatoMobSpeciesInfo info) {
        return Math.max(1, info.rainShelterRoofScanMaxBlocks());
    }

    private double runToShelterSpeed(CatoMobSpeciesInfo info) {
        return Math.max(0.05D, info.rainShelterRunToShelterSpeed());
    }

    private double rainWalkSpeed(CatoMobSpeciesInfo info) {
        return Math.max(0.05D, info.rainShelterWalkSpeed());
    }

    private int lingerAfterRainCfgTicks(CatoMobSpeciesInfo info) {
        return Math.max(0, info.rainShelterLingerAfterRainTicks());
    }

    private int peekAvgIntervalTicks(CatoMobSpeciesInfo info) {
        return Math.max(0, info.rainShelterPeekAvgIntervalTicks());
    }

    private int peekMinTicks(CatoMobSpeciesInfo info) {
        return Math.max(0, info.rainShelterPeekMinTicks());
    }

    private int peekMaxTicks(CatoMobSpeciesInfo info) {
        return Math.max(peekMinTicks(info), info.rainShelterPeekMaxTicks());
    }

    private double peekDistMin(CatoMobSpeciesInfo info) {
        return Math.max(0.0D, info.rainShelterPeekDistanceMinBlocks());
    }

    private double peekDistMax(CatoMobSpeciesInfo info) {
        return Math.max(peekDistMin(info), info.rainShelterPeekDistanceMaxBlocks());
    }

    private int peekSearchAttempts(CatoMobSpeciesInfo info) {
        return Math.max(0, info.rainShelterPeekSearchAttempts());
    }

    private boolean roofWanderEnabled(CatoMobSpeciesInfo info) {
        return enabled(info) && info.rainShelterShuffleEnabled();
    }

    private int roofWanderIntervalMinTicks(CatoMobSpeciesInfo info) {
        return Math.max(1, info.rainShelterShuffleIntervalMinTicks());
    }

    private int roofWanderIntervalMaxTicks(CatoMobSpeciesInfo info) {
        return Math.max(roofWanderIntervalMinTicks(info), info.rainShelterShuffleIntervalMaxTicks());
    }

    private int roofWanderSearchAttempts(CatoMobSpeciesInfo info) {
        return Math.max(0, info.rainShelterShuffleSearchAttempts());
    }

    // ------------------------------------------------------------
    // Surface preference scoring (water vs solid, soft vs hard)
    // ------------------------------------------------------------

    private double scoreSurfacePreference(CatoMobSpeciesInfo info, Level level, BlockPos stand) {
        var pref = info.surfacePreference();
        if (pref == null) return 0.0D;

        boolean inWater = !level.getFluidState(stand).isEmpty();
        BlockPos below = stand.below();
        var belowState = level.getBlockState(below);

        double score = 0.0D;

        score += inWater ? pref.preferWaterSurfaceWeight() : pref.preferSolidSurfaceWeight();

        if (!inWater) {
            if (belowState.is(CMBlockTags.SOFT_GROUND)) score += pref.preferSoftGroundWeight();
            if (belowState.is(CMBlockTags.HARD_GROUND)) score += pref.preferHardGroundWeight();
        }

        return score;
    }

    // ------------------------------------------------------------
    // Visual dryness + deep cover heuristic
    // ------------------------------------------------------------

    /** Require FEET + HEAD + ABOVE_HEAD to be dry. */
    private boolean isVisuallyDry(Level level, BlockPos stand) {
        return !level.isRainingAt(stand)
                && !level.isRainingAt(stand.above())
                && !level.isRainingAt(stand.above(2));
    }

    /**
     * 3x3 neighborhood dryness at FEET + HEAD + ABOVE_HEAD.
     * Max = 27.
     */
    private int dryNeighborhoodScore(Level level, BlockPos stand) {
        int score = 0;

        BlockPos h1 = stand.above();
        BlockPos h2 = stand.above(2);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p0 = stand.offset(dx, 0, dz);
                BlockPos p1 = h1.offset(dx, 0, dz);
                BlockPos p2 = h2.offset(dx, 0, dz);

                if (!level.isRainingAt(p0)) score++;
                if (!level.isRainingAt(p1)) score++;
                if (!level.isRainingAt(p2)) score++;
            }
        }

        return score; // 0..27
    }

    /**
     * How many blocks away is the nearest rainy edge?
     * We scan in 8 directions; the "depth" is the minimum dry run length before hitting rain.
     */
    private int coverDepth(Level level, BlockPos stand) {
        if (!isVisuallyDry(level, stand)) return 0;

        // 8 directions (N,S,E,W + diagonals)
        final int[][] dirs = new int[][]{
                { 1,  0},
                {-1,  0},
                { 0,  1},
                { 0, -1},
                { 1,  1},
                { 1, -1},
                {-1,  1},
                {-1, -1}
        };

        int bestMin = MAX_EDGE_SCAN;

        for (int[] d : dirs) {
            int dx = d[0];
            int dz = d[1];

            int dryRun = MAX_EDGE_SCAN;

            for (int step = 1; step <= MAX_EDGE_SCAN; step++) {
                BlockPos p = stand.offset(dx * step, 0, dz * step);
                if (!isVisuallyDry(level, p)) {
                    dryRun = step - 1;
                    break;
                }
            }

            bestMin = Math.min(bestMin, dryRun);
            if (bestMin <= 0) return 0; // early out
        }

        return bestMin;
    }

    // ------------------------------------------------------------
    // Anti-crowding
    // ------------------------------------------------------------

    private boolean isStandOccupied(Level level, BlockPos stand) {
        // Occupied if another CatoBaseMob is basically on this block.
        var box = new net.minecraft.world.phys.AABB(stand).inflate(OCCUPIED_RADIUS, OCCUPIED_Y_INFLATE, OCCUPIED_RADIUS);

        var others = level.getEntitiesOfClass(
                CatoBaseMob.class,
                box,
                e -> e != mob
                        && e.isAlive()
                        && e.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) < 1.0D
        );

        return !others.isEmpty();
    }

    // ------------------------------------------------------------
    // Goal gating
    // ------------------------------------------------------------

    private boolean shouldConsiderRainBehavior(CatoMobSpeciesInfo info) {
        if (!enabled(info)) return false;
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

    private boolean isRoofedHere(BlockPos standPos, int roofMax) {
        return mob.isRoofed(standPos, roofMax);
    }

    private boolean isWetWeatherPossibleHere(Level level, BlockPos pos) {
        if (!level.isRaining()) return false;
        return level.getBiome(pos).value().hasPrecipitation();
    }

    // ------------------------------------------------------------
    // Vanilla goal API
    // ------------------------------------------------------------

    @Override
    public boolean canUse() {
        final CatoMobSpeciesInfo info = info();
        if (!shouldConsiderRainBehavior(info)) return false;

        final Level level = mob.level();
        final BlockPos here = mob.posServer();

        forceStop = false;

        if (!isWetWeatherPossibleHere(level, here)) return false;

        final int roofMax = roofScanMaxBlocks(info);
        final boolean roofed = isRoofedHere(here, roofMax);

        if (!roofed) {
            final long now = mob.nowServer();
            if (now < nextAttemptTick) return false;

            nextAttemptTick = now + attemptIntervalTicks(info);
            if (mob.getRandom().nextFloat() >= attemptChance(info)) return false;

            shelterPos = findNearbyRoofedStandPos(info, level, roofMax);
            return shelterPos != null;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        final CatoMobSpeciesInfo info = info();
        if (!shouldConsiderRainBehavior(info)) return false;

        if (forceStop) return false;

        final Level level = mob.level();

        if (!isWetWeatherPossibleHere(level, mob.posServer())) {
            return lingerAfterRainTicks > 0 || mob.getNavigation().isInProgress();
        }

        return true;
    }

    @Override
    public void start() {
        repathCooldown = 0;

        peekTicksRemaining = 0;
        peekTargetPos = null;

        postPeekCooldownTicks = POST_PEEK_COOLDOWN_MIN_TICKS;

        roofWanderTargetPos = null;
        roofWanderSpeed = rainWalkSpeed(info());
        roofWanderCooldownTicks = 0;

        crossToRoofGraceTicks = 0;

        lingerAfterRainTicks = 0;

        forceStop = false;

        settleTicks = 0;

        scheduleNextPeekAttempt();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();

        shelterPos = null;
        peekTargetPos = null;
        peekTicksRemaining = 0;
        postPeekCooldownTicks = 0;

        roofWanderTargetPos = null;
        roofWanderCooldownTicks = 0;

        crossToRoofGraceTicks = 0;

        lingerAfterRainTicks = 0;
        repathCooldown = 0;

        forceStop = false;

        settleTicks = 0;

        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        final CatoMobSpeciesInfo info = info();
        final Level level = mob.level();
        final var nav = mob.getNavigation();
        final var rng = mob.getRandom();

        final BlockPos here = mob.posServer();
        final long now = mob.nowServer();
        final int roofMax = roofScanMaxBlocks(info);

        if (!isWetWeatherPossibleHere(level, here)) {
            if (lingerAfterRainTicks <= 0) lingerAfterRainTicks = lingerAfterRainCfgTicks(info);
            else lingerAfterRainTicks--;

            nav.stop();
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            return;
        }

        if (repathCooldown > 0) repathCooldown--;
        if (roofWanderCooldownTicks > 0) roofWanderCooldownTicks--;
        if (postPeekCooldownTicks > 0) postPeekCooldownTicks--;
        if (crossToRoofGraceTicks > 0) crossToRoofGraceTicks--;
        if (settleTicks > 0) settleTicks--;

        // ------------------------------------------------------------
        // Peek in progress
        // ------------------------------------------------------------
        if (peekTicksRemaining > 0) {
            peekTicksRemaining--;

            if (peekTargetPos == null) {
                peekTicksRemaining = 0;
            } else {
                if (repathCooldown <= 0) {
                    repathCooldown = 10;
                    moveTo(nav, peekTargetPos, rainWalkSpeed(info));
                }

                mob.setMoveMode(nav.isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);

                if (peekTicksRemaining <= 0) {
                    peekTargetPos = null;
                    nav.stop();

                    postPeekCooldownTicks = randomBetween(rng, POST_PEEK_COOLDOWN_MIN_TICKS, POST_PEEK_COOLDOWN_MAX_TICKS);

                    scheduleNextPeekAttempt();
                }
                return;
            }
        }

        // ------------------------------------------------------------
        // Roof check
        // ------------------------------------------------------------
        final boolean roofedNow = isRoofedHere(here, roofMax);

        if (!roofedNow) {
            // If we're crossing between roofed areas, allow brief unroofed travel.
            if (roofWanderTargetPos != null
                    && crossToRoofGraceTicks > 0
                    && isRoofedHere(roofWanderTargetPos, roofMax)) {

                if (repathCooldown <= 0) {
                    repathCooldown = 10;
                    moveTo(nav, roofWanderTargetPos, Math.max(0.05D, roofWanderSpeed));
                }

                mob.setMoveMode(nav.isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);
                return;
            }

            // Normal behavior: find shelter and run there
            if (shelterPos == null
                    || !isRoofedHere(shelterPos, roofMax)
                    || !isVisuallyDry(level, shelterPos)
                    || coverDepth(level, shelterPos) < MIN_COVER_DEPTH_BLOCKS) {

                shelterPos = findNearbyRoofedStandPos(info, level, roofMax);
            }

            if (shelterPos == null) {
                nav.stop();
                mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
                forceStop = true;
                return;
            }

            // Calm-under-cover doesn't apply while we are exposed
            settleTicks = 0;

            if (repathCooldown <= 0) {
                repathCooldown = 10;
                moveTo(nav, shelterPos, runToShelterSpeed(info));
            }

            mob.setMoveMode(nav.isInProgress() ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);
            mob.getLookControl().setLookAt(
                    shelterPos.getX() + 0.5,
                    shelterPos.getY() + 0.5,
                    shelterPos.getZ() + 0.5
            );
            return;
        }

        // ------------------------------------------------------------
        // Roofed now
        // ------------------------------------------------------------
        if (shelterPos == null) shelterPos = here.immutable();

        // ✅ If we're roofed but still not visually dry OR too close to edge, force a deeper shelter pick.
        if (!isVisuallyDry(level, here) || coverDepth(level, here) < MIN_COVER_DEPTH_BLOCKS) {
            BlockPos better = findNearbyRoofedStandPos(info, level, roofMax);
            if (better != null) {
                shelterPos = better;
                roofWanderTargetPos = null;
                roofWanderCooldownTicks = 0;
                crossToRoofGraceTicks = 0;

                settleTicks = 0;
                repathCooldown = 0;
                moveTo(nav, shelterPos, runToShelterSpeed(info));
                mob.setMoveMode(CatoBaseMob.MOVE_RUN);
                return;
            }
        }

        // ✅ Calm-under-cover: if we're properly sheltered (dry + deep), chill for a short time.
        // This prevents group "repath wars" and constant shuffling.
        if (isVisuallyDry(level, here) && coverDepth(level, here) >= MIN_COVER_DEPTH_BLOCKS) {
            if (settleTicks <= 0) {
                settleTicks = randomBetween(rng, 60, 140); // 3–7 seconds calm
            }

            if (settleTicks > 0) {
                // While settled: don't shuffle, don't peek, don't repath
                roofWanderTargetPos = null;
                crossToRoofGraceTicks = 0;

                nav.stop();
                mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
                return;
            }
        }

        // Keep moving to current roof-wander target
        if (roofWanderTargetPos != null) {
            double d = mob.distanceToSqr(
                    roofWanderTargetPos.getX() + 0.5,
                    roofWanderTargetPos.getY(),
                    roofWanderTargetPos.getZ() + 0.5
            );

            if (d <= ARRIVAL_DIST_SQR
                    || !isRoofedHere(roofWanderTargetPos, roofMax)
                    || !isVisuallyDry(level, roofWanderTargetPos)
                    || coverDepth(level, roofWanderTargetPos) < MIN_COVER_DEPTH_BLOCKS
                    || isStandOccupied(level, roofWanderTargetPos)) {

                roofWanderTargetPos = null;
                crossToRoofGraceTicks = 0;
                nav.stop();
            } else {
                if (repathCooldown <= 0) {
                    repathCooldown = 10;
                    moveTo(nav, roofWanderTargetPos, roofWanderSpeed);
                }

                mob.setMoveMode(nav.isInProgress() ? CatoBaseMob.MOVE_WALK : CatoBaseMob.MOVE_IDLE);
                return;
            }
        }

        // Pick a new roofed target occasionally
        if (roofWanderEnabled(info) && roofWanderCooldownTicks <= 0) {
            roofWanderCooldownTicks = randomBetween(rng, roofWanderIntervalMinTicks(info), roofWanderIntervalMaxTicks(info));

            BlockPos t = findNearbyRoofedWanderPosSmallRadius(info, level, shelterPos, roofMax);
            if (t != null && !t.equals(here)) {
                roofWanderTargetPos = t;
                roofWanderSpeed = chooseShelteredWanderSpeed(info, rng, t);
                repathCooldown = 0;

                crossToRoofGraceTicks = CROSS_TO_ROOF_GRACE_TICKS;

                moveTo(nav, roofWanderTargetPos, roofWanderSpeed);
                mob.setMoveMode(CatoBaseMob.MOVE_WALK);
                return;
            }
        }

        // Idle under roof
        nav.stop();
        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        tryStartPeek(info, level, now, roofMax);
    }

    // ------------------------------------------------------------
    // Peek scheduling
    // ------------------------------------------------------------

    private void scheduleNextPeekAttempt() {
        final CatoMobSpeciesInfo info = info();
        int avg = peekAvgIntervalTicks(info);
        if (avg <= 0) {
            nextPeekAttemptTick = Long.MAX_VALUE;
            return;
        }

        int jitter = Math.max(1, avg / 4);
        int delta = avg + randomBetweenSigned(mob.getRandom(), -jitter, jitter);
        delta = Math.max(20, delta);

        nextPeekAttemptTick = mob.nowServer() + delta;
    }

    private void tryStartPeek(CatoMobSpeciesInfo info, Level level, long now, int roofMax) {
        // While calm, no peeking
        if (settleTicks > 0) return;

        int avg = peekAvgIntervalTicks(info);
        if (avg <= 0) return;

        if (postPeekCooldownTicks > 0) return;
        if (now < nextPeekAttemptTick) return;

        scheduleNextPeekAttempt();

        BlockPos peek = findNearbyNotRoofedStandPos(info, level, shelterPos, roofMax);
        if (peek != null) {
            peekTargetPos = peek;
            peekTicksRemaining = randomBetween(mob.getRandom(), peekMinTicks(info), peekMaxTicks(info));
            repathCooldown = 0;
            moveTo(mob.getNavigation(), peekTargetPos, rainWalkSpeed(info));
        }
    }

    // ------------------------------------------------------------
    // Navigation helpers
    // ------------------------------------------------------------

    private void moveTo(net.minecraft.world.entity.ai.navigation.PathNavigation nav, BlockPos standPos, double speed) {
        nav.moveTo(
                standPos.getX() + 0.5D,
                standPos.getY(),
                standPos.getZ() + 0.5D,
                Math.max(0.05D, speed)
        );
    }

    private int randomBetween(net.minecraft.util.RandomSource rng, int min, int max) {
        int a = Math.max(0, min);
        int b = Math.max(a, max);
        if (b == a) return a;
        return a + rng.nextInt(b - a + 1);
    }

    private int randomBetweenSigned(net.minecraft.util.RandomSource rng, int min, int max) {
        int a = Math.min(min, max);
        int b = Math.max(min, max);
        if (a == b) return a;
        return a + rng.nextInt(b - a + 1);
    }

    // ------------------------------------------------------------
    // Shelter / roof-wander finders
    // ------------------------------------------------------------

    private @Nullable BlockPos findNearbyRoofedStandPos(CatoMobSpeciesInfo info, Level level, int roofMax) {
        BlockPos center = (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null)
                ? mob.getHomePos()
                : mob.posServer();

        double radius = searchRadiusBlocks(info);
        int attempts = searchAttempts(info);

        final var nav = mob.getNavigation();
        final var rng = mob.getRandom();

        // Two-pass: strict then fallback
        BlockPos bestStrict = null;
        double bestStrictScore = -Double.MAX_VALUE;

        BlockPos bestFallback = null;
        double bestFallbackScore = -Double.MAX_VALUE;

        // fallback relax: don’t require depth, relax neighborhood a bit
        final int relaxedNeighborhood = Math.max(0, MIN_DRY_NEIGHBORHOOD_SCORE - 6);

        for (int i = 0; i < attempts; i++) {
            double ang = rng.nextDouble() * (Math.PI * 2.0D);
            double dist = rng.nextDouble() * radius;

            int x = center.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = mob.posServer().getY() + 8;

            BlockPos probe = new BlockPos(x, y, z);
            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos stand = ground.above();
            if (!level.isEmptyBlock(stand)) continue;

            if (mob.roofDistance(stand, roofMax) == -1) continue;
            if (!isVisuallyDry(level, stand)) continue;

            int neigh = dryNeighborhoodScore(level, stand);
            int depth = coverDepth(level, stand);
            boolean occupied = isStandOccupied(level, stand);

            var path = nav.createPath(stand, 0);
            if (path == null || !path.canReach()) continue;

            double d2 = mob.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            double pref = scoreSurfacePreference(info, level, stand);

            // Score: depth is king, then neighborhood, then preference, then distance
            double score = (depth * 1000.0D) + (neigh * 10.0D) + (pref * 10.0D) - (d2 * 0.02D);

            // Strict candidate: must be deep + good neighborhood + NOT occupied
            if (!occupied && depth >= MIN_COVER_DEPTH_BLOCKS && neigh >= MIN_DRY_NEIGHBORHOOD_SCORE) {
                if (score > bestStrictScore) {
                    bestStrictScore = score;
                    bestStrict = stand.immutable();
                }
            }

            // Fallback candidate: allow occupied, but penalize hard so free spots win
            if (neigh >= relaxedNeighborhood) {
                double fallbackScore = score - (occupied ? 1500.0D : 0.0D);
                if (fallbackScore > bestFallbackScore) {
                    bestFallbackScore = fallbackScore;
                    bestFallback = stand.immutable();
                }
            }
        }

        return (bestStrict != null) ? bestStrict : bestFallback;
    }

    private @Nullable BlockPos findNearbyRoofedWanderPosSmallRadius(CatoMobSpeciesInfo info, Level level, BlockPos anchor, int roofMax) {
        int attempts = Math.max(8, roofWanderSearchAttempts(info));

        double max = Math.max(1.5D, info.wanderMaxRadius());
        max = Math.min(max, 8.0D);

        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;

        final var nav = mob.getNavigation();
        final var rng = mob.getRandom();

        for (int i = 0; i < attempts; i++) {
            double ang = rng.nextDouble() * (Math.PI * 2.0D);
            double dist = rng.nextDouble() * max;

            int x = anchor.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = anchor.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = mob.posServer().getY() + 6;

            BlockPos probe = new BlockPos(x, y, z);
            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos stand = ground.above();
            if (!level.isEmptyBlock(stand)) continue;

            if (mob.roofDistance(stand, roofMax) == -1) continue;
            if (!isVisuallyDry(level, stand)) continue;

            int neigh = dryNeighborhoodScore(level, stand);
            int depth = coverDepth(level, stand);
            if (depth < MIN_COVER_DEPTH_BLOCKS) continue;
            if (neigh < MIN_DRY_NEIGHBORHOOD_SCORE) continue;

            // ✅ Avoid picking a tile that is already occupied to reduce shoving
            if (isStandOccupied(level, stand)) continue;

            var path = nav.createPath(stand, 0);
            if (path == null || !path.canReach()) continue;

            double pref = scoreSurfacePreference(info, level, stand);
            double d2 = mob.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);

            double score = (depth * 1000.0D) + (neigh * 10.0D) + (pref * 10.0D) - (d2 * 0.02D);

            if (score > bestScore) {
                bestScore = score;
                best = stand.immutable();
            }
        }

        return best;
    }

    private double chooseShelteredWanderSpeed(CatoMobSpeciesInfo info, net.minecraft.util.RandomSource rng, BlockPos target) {
        double walk = Math.max(0.05D, info.wanderWalkSpeed());
        double run = Math.max(walk, info.wanderRunSpeed());
        float runChance = Mth.clamp(info.wanderRunChance(), 0f, 1f);

        double threshold = info.wanderRunDistanceThreshold();
        if (threshold > 0.0D) {
            double d = mob.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (d < (threshold * threshold)) {
                return walk;
            }
        }

        return (rng.nextFloat() < runChance) ? run : walk;
    }

    private @Nullable BlockPos findNearbyNotRoofedStandPos(CatoMobSpeciesInfo info, Level level, BlockPos from, int roofMax) {
        // While calm, no peeking
        if (settleTicks > 0) return null;

        double min = peekDistMin(info);
        double max = peekDistMax(info);

        int attempts = Math.max(8, peekSearchAttempts(info));
        final var nav = mob.getNavigation();
        final var rng = mob.getRandom();

        for (int i = 0; i < attempts; i++) {
            double ang = rng.nextDouble() * (Math.PI * 2.0D);
            double dist = (max <= min) ? min : (min + rng.nextDouble() * (max - min));

            int x = from.getX() + Mth.floor(Math.cos(ang) * dist);
            int z = from.getZ() + Mth.floor(Math.sin(ang) * dist);
            int y = mob.posServer().getY() + 8;

            BlockPos probe = new BlockPos(x, y, z);
            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos stand = ground.above();
            if (!level.isEmptyBlock(stand)) continue;

            if (mob.roofDistance(stand, roofMax) != -1) continue;

            // "peek" should actually be rainy somewhere on the body
            if (!isRainingHere(level, stand)
                    && !isRainingHere(level, stand.above())
                    && !isRainingHere(level, stand.above(2))) continue;

            var path = nav.createPath(stand, 0);
            if (path == null || !path.canReach()) continue;

            return stand.immutable();
        }

        return null;
    }

    private @Nullable BlockPos findGround(Level level, BlockPos start) {
        BlockPos pos = start;

        int steps = 0;
        while (steps++ < FIND_GROUND_MAX_STEPS
                && pos.getY() < level.getMaxBuildHeight()
                && !level.isEmptyBlock(pos)) {
            pos = pos.above();
        }

        steps = 0;
        while (steps++ < FIND_GROUND_MAX_STEPS
                && pos.getY() > level.getMinBuildHeight()
                && level.isEmptyBlock(pos)) {
            pos = pos.below();
        }

        if (level.isEmptyBlock(pos)) return null;
        return pos;
    }
}
