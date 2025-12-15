package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * CatoSleepSearchGoal
 *
 * Purpose:
 * If a species requires a roof to sleep (sleepRequiresRoof = true), this goal
 * actively searches for a nearby "roofed" spot (a position where the sky is NOT visible),
 * walks the mob there, and then starts sleeping once it arrives.
 *
 * Why it exists:
 * The base sleep logic in CatoBaseMob refuses to start sleeping under open sky
 * when roof is required. Without this goal, mobs would *never* sleep in open areas.
 *
 * High-level flow:
 * 1) canUse():
 *    - Checks if sleeping is enabled and conditions are calm/idle.
 *    - Checks roof requirement and that we are currently under open sky.
 *    - Performs the random "sleep start chance" roll.
 *    - Picks a valid roofed targetPos (critical step).
 * 2) start():
 *    - Marks mob as "sleep searching" (so base sleep logic doesn't roll again).
 *    - Starts pathing to targetPos.
 * 3) tick():
 *    - Counts down a timeout.
 *    - Re-paths if navigation becomes done (stuck / micro-stop).
 *    - When close enough, verifies the spot is still roofed and calls beginSleepingFromGoal().
 * 4) stop():
 *    - Clears searching state, clears targetPos, stops navigation.
 *
 * Notes:
 * - This goal only claims MOVE. It does not lock head rotation—that's handled by CatoSleepGoal once sleeping.
 * - Cooldown is applied on failure so the mob doesn't try to search every tick in an open field.
 */
public class CatoSleepSearchGoal extends Goal {

    /** The owning mob (CatoBaseMob provides sleep flags, species info, home radius helpers, etc.). */
    private final CatoBaseMob mob;

    /** The chosen destination where the mob should go to sleep (must be roofed). */
    private BlockPos targetPos = null;

    /** Safety timeout so the mob doesn't keep searching forever if it can't reach the spot. */
    private int searchTimeoutTicks = 0;

    // ------------------------------------------------------------
    // Local tuning knobs (these are goal-internal, not per-species)
    // ------------------------------------------------------------
    public CatoSleepSearchGoal(CatoBaseMob mob) {
        this.mob = mob;

        // This goal performs pathing, so it uses MOVE.
        // Claiming MOVE prevents some other movement goals from running at the same time.
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /**
     * Determines whether we should START searching for a sleep spot right now.
     *
     * Important detail:
     * This method also *selects the targetPos*.
     * If we can't find a valid targetPos, we MUST return false.
     */
    @Override
    public boolean canUse() {
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        // 1) Species must support sleeping at all
        if (!info.sleepEnabled()) return false;

        // 2) Don't start if already sleeping or already searching
        if (mob.isSleeping()) return false;
        if (mob.isSleepSearching()) return false;

        // 3) After a failed attempt, we wait a cooldown to avoid spam-searching
        if (mob.isSleepSearchOnCooldown()) return false;

        // 4) Must be calm/idle
        if (mob.getTarget() != null || mob.isAggressive()) return false;

        // 5) Only during allowed time window
        boolean isDay = mob.level().isDay();
        if (isDay && !info.sleepAtDay()) return false;
        if (!isDay && !info.sleepAtNight()) return false;

        // 6) Only makes sense if roof is required AND we currently see the sky
        if (!info.sleepRequiresRoof()) return false;
        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());

        // Only search if we do NOT have a roof within the allowed height
        if (mob.isRoofed(mob.blockPosition(), roofMax)) return false;

        // Must actually want to sleep (set by base desire window)
        if (!mob.wantsToSleepNow()) return false;

        // 8) Pick a roofed spot (this will try remembered spots first, then random search)
        this.targetPos = findRoofedSleepSpot(info);

        if (this.targetPos == null) {
            // ✅ NEW: give up this “desire episode” so we don’t keep re-triggering forever
            mob.clearSleepDesire();

            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            return false;
        }

        return true;
    }


    /**
     * Determines whether we should KEEP running this goal.
     * Once it returns false, stop() will be called.
     */
    @Override
    public boolean canContinueToUse() {
        // If we somehow lost the target, stop.
        if (targetPos == null) return false;

        // If the mob started sleeping through some other means, stop searching.
        if (mob.isSleeping()) return false;

        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        // Cancel search if combat begins.
        if (mob.getTarget() != null || mob.isAggressive()) return false;

        // Cancel if conditions would instantly wake the mob (even though it's not asleep yet).
        // This avoids walking into water/air and then trying to sleep immediately.
        if (info.wakeOnAir() && !mob.onGround()) return false;
        if (info.wakeOnTouchingWater() && mob.isInWater() && !info.sleepAllowedOnWaterSurface()) return false;
        if (info.wakeOnUnderwater() && mob.isUnderWater()) return false;

        // Timeout safety: if time ran out, stop.
        return searchTimeoutTicks > 0;
    }

    /**
     * Called once when the goal starts.
     * Sets "sleep searching" state and begins navigation toward the chosen targetPos.
     */
    @Override
    public void start() {
        if (this.targetPos == null) {
            mob.setSleepSearching(false);
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
            return;
        }

        mob.setSleepSearching(true);
        searchTimeoutTicks = Math.max(1, mob.getSpeciesInfo().sleepSearchTimeoutTicks());

        // TAKE CONTROL IMMEDIATELY
        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));

        double speed = Math.max(0.1D, mob.getSpeciesInfo().wanderWalkSpeed());

        mob.getNavigation().moveTo(
                targetPos.getX() + 0.5D,
                targetPos.getY(),
                targetPos.getZ() + 0.5D,
                speed
        );
    }

    /**
     * Called once when the goal ends (success, cancel, or timeout).
     * Clears state and stops navigation.
     */
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

    /**
     * Runs every tick while searching:
     * - decrements timeout
     * - repaths if needed
     * - checks arrival and starts sleeping if the target is still valid
     */
    @Override
    public void tick() {
        Level level = mob.level();
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        // Extra safety: if we lost our target somehow, bail and cooldown.
        if (targetPos == null) {
            mob.setSleepSearching(false);

            // ✅ NEW: we failed the search episode
            mob.clearSleepDesire();

            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
            return;
        }

        // Decrement time remaining.
        searchTimeoutTicks--;

        // Timeout => fail + cooldown, then stop searching.
        if (searchTimeoutTicks <= 0) {
            // ✅ NEW: we failed the search episode
            mob.clearSleepDesire();

            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
            return;
        }

        // Navigation can sometimes become "done" briefly during micro-stops or repaths.
        // If it's done and we haven't arrived, issue the move command again.
        if (mob.getNavigation().isDone()) {
            double speed = Math.max(0.1D, info.wanderWalkSpeed());
            mob.getNavigation().moveTo(
                    targetPos.getX() + 0.5D,
                    targetPos.getY(),
                    targetPos.getZ() + 0.5D,
                    speed
            );
        }

        // If we're close enough to the target block, treat it as reached.
        double distSqr = mob.distanceToSqr(Vec3.atBottomCenterOf(targetPos));
        if (distSqr <= 2.0D) { // ~1.4 blocks

            // roof validation = "any block above within roofMax"
            int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
            int roofDy = mob.roofDistance(targetPos, roofMax);

            if (roofDy != -1) {

                // Buddy override: optionally relocate to a better spot next to a sleeping buddy
                if (info.sleepBuddyCanOverrideMemory()) {
                    BlockPos buddySpot = findBuddyAdjacentSleepSpot(level, targetPos, info);

                    if (buddySpot != null && !buddySpot.equals(targetPos)) {

                        mob.strikeSleepSpot(targetPos);

                        targetPos = buddySpot;

                        double speed = Math.max(0.1D, info.wanderWalkSpeed());
                        mob.getNavigation().moveTo(
                                targetPos.getX() + 0.5D,
                                targetPos.getY(),
                                targetPos.getZ() + 0.5D,
                                speed
                        );

                        return;
                    }
                }

                // Commit: remember + sleep
                mob.rememberSleepSpot(targetPos);
                mob.clearSleepDesire();          // already in your code (keep this)
                mob.beginSleepingFromGoal();
                mob.getNavigation().stop();
                stop();
                return;
            }

            // No roof within roofMax blocks => fail + cooldown
            //  we failed the search episode
            mob.clearSleepDesire();

            mob.startSleepSearchCooldown(info.sleepSearchCooldownTicks());
            stop();
        }
    }

    // --------------------------------------------------------------
    // Candidate spot search
    // --------------------------------------------------------------

    /**
     * Finds a nearby position where:
     * - there's solid ground
     * - there's air above to stand in
     * - the standing position does NOT see the sky (roofed)
     * - (optionally) stays inside home radius if enabled
     *
     * Returns:
     * - standPos (block above ground) if found
     * - null if no valid spot found within MAX_PICK_ATTEMPTS
     */
    private BlockPos findRoofedSleepSpot(CatoMobSpeciesInfo info) {

        Level level = mob.level();

        BlockPos center = (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null)
                ? mob.getHomePos()
                : mob.blockPosition();

        double mul = Math.max(0.1D, info.sleepSearchRadiusMultiplier());

        double maxRadius = Math.max(info.wanderMaxRadius(), 4.0D) * mul;
        double minRadius = Math.max(0.0D, Math.min(info.wanderMinRadius() * mul, maxRadius));

        double homeRadiusSqr = mob.shouldStayWithinHomeRadius()
                ? mob.getHomeRadius() * mob.getHomeRadius()
                : 0.0D;

        int maxAttempts = Math.max(1, info.sleepSearchMaxAttempts());
        int pathAttempts = 0;
        int maxPathAttempts = Math.max(1, info.sleepSearchMaxPathAttempts());

        // Headroom requirements
        final int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());

        // --------------------------------------------------------------
        // 0) Try remembered sleep spots first (FIFO order)
        // Strike on failure; delete only after repeated failures.
        // --------------------------------------------------------------
        if (isCurrentlySleepTime(info)) {
            for (CatoBaseMob.SleepSpotMemory mem : mob.getRememberedSleepSpots()) {
                BlockPos standPos = mem.pos;

                if (!isRememberedSpotValid(info, standPos)) {
                    mob.strikeSleepSpot(standPos);
                    continue;
                }

                // ⭐ SUCCESS: reuse known-good spot
                return standPos;
            }
        }

        BlockPos bestPos = null;
        int bestScore = Integer.MAX_VALUE;
        double bestDistSqr = Double.MAX_VALUE;

        // Cache / dedupe within THIS search run (prevents repeated expensive path checks)
        java.util.Set<Long> testedPos = new java.util.HashSet<>();
        java.util.Set<Long> failedPathPos = new java.util.HashSet<>();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

            // optional min-distance bias
            if (dist < info.sleepSearchMinDistance()) {
                continue;
            }

            int x = center.getX() + Mth.floor(Math.cos(angle) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(angle) * dist);
            int y = mob.blockPosition().getY() + 8;

            // Respect home radius if enabled.
            if (info.sleepSearchRespectHomeRadius() && mob.shouldStayWithinHomeRadius()) {
                int dxHome = x - center.getX();
                int dzHome = z - center.getZ();
                if ((double)(dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) {
                    continue;
                }
            }

            BlockPos pos = new BlockPos(x, y, z);

            BlockPos ground = findGround(level, pos);
            if (ground == null) continue;

            BlockPos standPos = ground.above();

            // Dedupe: don't evaluate the same standPos multiple times in one run
            long key = standPos.asLong();
            if (!testedPos.add(key)) continue;          // already tested
            if (failedPathPos.contains(key)) continue;  // already known unreachable

            // Must have enough vertical space to stand/sleep here (minHeadroom blocks of air)
            boolean hasRoom = true;
            for (int dy = 0; dy < minHeadroom; dy++) {
                if (!level.isEmptyBlock(standPos.above(dy))) {
                    hasRoom = false;
                    break;
                }
            }
            if (!hasRoom) continue;

            // ✅ NEW roof logic: "any block above within roofMax" (ignores time-of-day skylight)
            int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
            int roofDy = mob.roofDistance(standPos, roofMax);
            if (roofDy == -1) continue;

            // Roof must be ABOVE our required headroom
            if (roofDy < minHeadroom) continue;

            // Must be pathable/reachable
            var path = mob.getNavigation().createPath(standPos, 0);
            pathAttempts++;

            if (path == null || !path.canReach()) {
                failedPathPos.add(key);

                if (pathAttempts >= maxPathAttempts) {
                    break; // stop searching this run
                }
                continue;
            }

            // Bias toward closer spots when score is equal
            double distSqrToMob = mob.distanceToSqr(
                    standPos.getX() + 0.5D,
                    standPos.getY(),
                    standPos.getZ() + 0.5D
            );

            // ✅ Score: prefer roof as low as possible but still valid
            int score = Math.max(0, roofDy - minHeadroom);

            // social sleeping bonus
            int buddies = countSleepingBuddiesNear(level, standPos, info);
            score -= buddies * Math.max(0, info.sleepBuddyScoreBonusPerBuddy());

            if (score < bestScore || (score == bestScore && distSqrToMob < bestDistSqr)) {
                bestScore = score;
                bestDistSqr = distSqrToMob;
                bestPos = standPos;
            }
        }

        return bestPos;
    }

    private BlockPos findGround(Level level, BlockPos start) {
        BlockPos pos = start;

        // If we start inside terrain, climb up to air first
        while (pos.getY() < level.getMaxBuildHeight() && !level.isEmptyBlock(pos)) {
            pos = pos.above();
        }

        // Then drop until we hit solid
        while (pos.getY() > level.getMinBuildHeight() && level.isEmptyBlock(pos)) {
            pos = pos.below();
        }

        if (level.isEmptyBlock(pos)) {
            return null;
        }

        return pos;
    }

    private boolean isCurrentlySleepTime(CatoMobSpeciesInfo info) {
        boolean isDay = mob.level().isDay();
        return (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());
    }

    private boolean isRememberedSpotValid(CatoMobSpeciesInfo info, BlockPos standPos) {
        Level level = mob.level();
        if (standPos == null) return false;

        // Respect home radius if you want sleep search to do so
        if (info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && mob.getHomePos() != null) {

            BlockPos home = mob.getHomePos();
            double r = mob.getHomeRadius();
            double dx = (standPos.getX() + 0.5D) - (home.getX() + 0.5D);
            double dz = (standPos.getZ() + 0.5D) - (home.getZ() + 0.5D);
            if ((dx * dx + dz * dz) > (r * r)) return false;
        }

        // Must have enough vertical space (minHeadroom blocks of air)
        int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());
        for (int dy = 0; dy < minHeadroom; dy++) {
            if (!level.isEmptyBlock(standPos.above(dy))) return false;
        }

        // ✅ NEW roof rule: any block counts as "roof" if it's within roofMax blocks above standPos
        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        if (roofDy == -1) return false;

        // Roof must be ABOVE our required headroom
        if (roofDy < minHeadroom) return false;

        // Must still be reachable
        var path = mob.getNavigation().createPath(standPos, 0);
        return path != null && path.canReach();
    }

    private int countSleepingBuddiesNear(Level level, BlockPos standPos, CatoMobSpeciesInfo info) {
        if (!info.sleepPreferSleepingBuddies()) return 0;
        if (info.sleepBuddyTypes() == null || info.sleepBuddyTypes().isEmpty()) return 0;

        double r = Math.max(0.0D, info.sleepBuddySearchRadius());
        if (r <= 0.0D) return 0;

        var box = new net.minecraft.world.phys.AABB(
                standPos.getX() - r, standPos.getY() - 2, standPos.getZ() - r,
                standPos.getX() + 1 + r, standPos.getY() + 2, standPos.getZ() + 1 + r
        );

        int max = Math.max(0, info.sleepBuddyMaxCount());
        if (max <= 0) return 0;

        int count = 0;

        for (var e : level.getEntities(mob, box, ent -> ent instanceof CatoBaseMob)) {
            CatoBaseMob other = (CatoBaseMob) e;

            if (other == mob) continue;
            if (!other.isSleeping()) continue;

            // only count types you configured
            if (!info.sleepBuddyTypes().contains(other.getType())) continue;

            count++;
            if (count >= max) break;
        }

        return count;
    }

    @Nullable
    private BlockPos findBuddyAdjacentSleepSpot(Level level, BlockPos fromPos, CatoMobSpeciesInfo info) {

        // 1) Find nearest sleeping buddy within radius
        int r = (int) Math.ceil(Math.max(0.0D, info.sleepBuddySearchRadius()));
        if (r <= 0) return null;

        var box = new net.minecraft.world.phys.AABB(fromPos).inflate(r, 4, r);

        var buddies = level.getEntitiesOfClass(
                CatoBaseMob.class,
                box,
                e -> e != mob
                        && e.isAlive()
                        && e.isSleeping()
                        && (info.sleepBuddyTypes() == null
                        || info.sleepBuddyTypes().isEmpty()
                        || info.sleepBuddyTypes().contains(e.getType()))
        );

        if (buddies.isEmpty()) return null;

        CatoBaseMob nearest = null;
        double best = Double.MAX_VALUE;
        for (CatoBaseMob b : buddies) {
            double d = b.distanceToSqr(mob);
            if (d < best) {
                best = d;
                nearest = b;
            }
        }
        if (nearest == null) return null;

        // 2) Try to find a valid standPos near the buddy
        int rr = Math.max(1, info.sleepBuddyRelocateRadiusBlocks());

        BlockPos buddyBase = nearest.blockPosition();

        BlockPos bestPos = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (int dx = -rr; dx <= rr; dx++) {
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx == 0 && dz == 0) continue;

                BlockPos candidate = buddyBase.offset(dx, 0, dz);

                // snap to ground like your normal search does
                BlockPos ground = findGround(level, candidate.above(8));
                if (ground == null) continue;

                BlockPos standPos = ground.above();

                // Must be valid under the same rules as remembered spots
                if (!isRememberedSpotValid(info, standPos)) continue;

                // Prefer the closest valid one to the buddy (snuggly)
                double d2 = standPos.distSqr(buddyBase);
                if (d2 < bestDistSqr) {
                    bestDistSqr = d2;
                    bestPos = standPos;
                }
            }
        }

        return bestPos;
    }
}
