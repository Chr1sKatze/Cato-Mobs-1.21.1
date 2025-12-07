package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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

    /** How many random candidate positions we try before giving up this search attempt. */
    private static final int MAX_PICK_ATTEMPTS = 20;

    /** How long we allow the mob to walk to the target before failing (10 seconds). */
    private static final int SEARCH_TIMEOUT_TICKS = 20 * 10; // 10s to reach the spot

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

        // 4) Must be calm/idle: no combat target, not aggressive, and no current navigation
        if (mob.getTarget() != null || mob.isAggressive()) return false;
        if (!mob.getNavigation().isDone()) return false;

        // 5) Only search during allowed time window (day/night) for this species
        boolean isDay = mob.level().isDay();
        if (isDay && !info.sleepAtDay()) return false;
        if (!isDay && !info.sleepAtNight()) return false;

        // 6) This goal ONLY makes sense if roof is required AND we currently have no roof.
        // If roof isn't required, base sleep logic can handle sleeping anywhere.
        // If we already have a roof (can't see sky), we also don't need to search.
        if (!info.sleepRequiresRoof()) return false;
        if (!mob.level().canSeeSky(mob.blockPosition())) return false;

        // 7) Avoid searching if the mob is already physically sliding/moving a bit (knockback, water drift, etc.)
        if (mob.getDeltaMovement().horizontalDistanceSqr() > 0.001D) return false;

        // 8) Chance roll: we don't want search to start instantly every time conditions match.
        // This uses the same "sleep start chance" value as normal sleeping.
        if (mob.getRandom().nextFloat() >= info.sleepStartChancePerTick()) return false;

        // 9) CRITICAL: pick a concrete roofed spot now.
        // If no spot exists nearby, we apply a cooldown and abort.
        this.targetPos = findRoofedSleepSpot(info);

        if (this.targetPos == null) {
            // Prevent retrying every tick when the mob is in a huge open area.
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
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
        // Defensive: should never happen because canUse() only returns true when targetPos != null.
        if (this.targetPos == null) {
            mob.setSleepSearching(false);
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
            return;
        }

        // Mark searching so base sleep logic won't also roll sleep chance while we're walking somewhere.
        mob.setSleepSearching(true);

        // Start timeout countdown for reaching the spot.
        searchTimeoutTicks = SEARCH_TIMEOUT_TICKS;

        // Use wanderWalkSpeed as travel speed to keep behavior consistent.
        double speed = Math.max(0.1D, mob.getSpeciesInfo().wanderWalkSpeed());

        // Path to center of block so the mob doesn't aim at a corner.
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
        // Extra safety: if we lost our target somehow, bail and cooldown.
        if (targetPos == null) {
            mob.setSleepSearching(false);
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
            stop();
            return;
        }

        // Decrement time remaining.
        searchTimeoutTicks--;

        // Timeout => fail + cooldown, then stop searching.
        if (searchTimeoutTicks <= 0) {
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
            stop();
            return;
        }

        // Navigation can sometimes become "done" briefly during micro-stops or repaths.
        // If it's done and we haven't arrived, issue the move command again.
        if (mob.getNavigation().isDone()) {
            double speed = Math.max(0.1D, mob.getSpeciesInfo().wanderWalkSpeed());
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

            // Verify that the final position is still roofed (no sky visible).
            // Note: canSeeSky(pos) == true means "open sky", so we want the opposite.
            if (!mob.level().canSeeSky(targetPos)) {
                // Tell the base mob to begin sleeping immediately (server-side state).
                mob.beginSleepingFromGoal();

                // Stop walking to avoid overshooting.
                mob.getNavigation().stop();

                // End goal cleanly (also clears "sleep searching").
                stop();
                return;
            }

            // We arrived but somehow it became unroofed (tree got cut, block update, etc.)
            // Treat as failure and apply cooldown.
            mob.startSleepSearchCooldown(mob.getSpeciesInfo().sleepSearchCooldownTicks());
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

        // If this species stays near "home", search around that; otherwise search around current position.
        BlockPos center = (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null)
                ? mob.getHomePos()
                : mob.blockPosition();

        // Use wander radii as the search radius baseline (keeps behavior consistent across systems).
        double maxRadius = Math.max(info.wanderMaxRadius(), 4.0D);
        double minRadius = Math.max(0.0D, Math.min(info.wanderMinRadius(), maxRadius));

        // Home radius enforcement (only applies if stayWithinHomeRadius is enabled).
        double homeRadius = mob.shouldStayWithinHomeRadius() ? mob.getHomeRadius() : Double.MAX_VALUE;
        double homeRadiusSqr = homeRadius * homeRadius;

        // Randomly sample candidate points in a ring around center.
        for (int attempt = 0; attempt < MAX_PICK_ATTEMPTS; attempt++) {
            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

            int x = center.getX() + Mth.floor(Math.cos(angle) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(angle) * dist);
            int y = center.getY();

            BlockPos pos = new BlockPos(x, y, z);

            // Respect home radius if enabled.
            if (mob.shouldStayWithinHomeRadius() && center.distSqr(pos) > homeRadiusSqr) {
                continue;
            }

            // Find a solid ground block at/under this position.
            BlockPos ground = findGround(level, pos);
            if (ground == null) continue;

            // The mob stands on the block above ground.
            BlockPos standPos = ground.above();

            // Must have space to stand.
            if (!level.isEmptyBlock(standPos)) continue;

            // Must be roofed: if the stand position can see the sky, it's not valid.
            if (level.canSeeSky(standPos)) continue;

            // Valid target.
            return standPos;
        }

        // No valid spot found.
        return null;
    }

    /**
     * Finds the first non-air block by walking downward from a starting position.
     * Returns the "ground" block (solid) or null if we never find one.
     */
    private BlockPos findGround(Level level, BlockPos start) {
        BlockPos pos = start;

        // Move downward while we're in air and also have air below (prevents stopping in mid-air pockets).
        while (pos.getY() > level.getMinBuildHeight()
                && level.isEmptyBlock(pos)
                && level.isEmptyBlock(pos.below())) {
            pos = pos.below();
        }

        // If we ended up still in air, there was no valid ground.
        if (level.isEmptyBlock(pos)) {
            return null;
        }

        return pos;
    }
}
