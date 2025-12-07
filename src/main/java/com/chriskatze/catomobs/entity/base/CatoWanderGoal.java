package com.chriskatze.catomobs.entity.base;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * CatoWanderGoal
 *
 * Purpose:
 * A custom "random stroll" (wander) goal that supports:
 * - walk vs run selection (with chance and/or distance threshold)
 * - staying within a "home radius" if the mob species enables it
 * - syncing an authoritative MOVE_MODE (IDLE/WALK/RUN) to the client for animations
 * - calling generic hooks on the mob (onWanderStart/Stop) for extra cosmetics/logic
 *
 * Why not vanilla RandomStrollGoal:
 * Vanilla goals don't know about your custom MOVE_MODE syncing and your per-species tuning
 * (run chance, min/max radii, forced run beyond a distance, home radius behavior).
 *
 * High-level flow:
 * 1) canUse():
 *    - Only starts if mob is idle (no current navigation) and passes a random tick roll.
 *    - Chooses a random target position via pickPosition().
 *    - pickPosition() also decides whether this wander will be walking or running.
 * 2) start():
 *    - Sets MOVE_MODE to WALK or RUN (authoritative state used by animations).
 *    - Calls mob.onWanderStart(running).
 *    - Starts navigation to the chosen wantedX/Y/Z.
 * 3) canContinueToUse():
 *    - Continues while navigation reports "in progress".
 * 4) tick():
 *    - Keeps MOVE_MODE in sync with actual navigation status.
 *      (Useful because pathing can finish early or get interrupted.)
 * 5) stop():
 *    - Resets MOVE_MODE to IDLE and calls mob.onWanderStop().
 */
public class CatoWanderGoal extends Goal {

    /** The owning mob (gives access to navigation, home radius, hooks, synced move mode, etc.) */
    private final CatoBaseMob mob;

    // ------------------------------------------------------------
    // Wander tuning (usually comes from species info)
    // ------------------------------------------------------------

    /** Navigation speed used for "walk" wandering. */
    private final double walkSpeed;

    /** Navigation speed used for "run" wandering. */
    private final double runSpeed;

    /** Chance (0..1) that a wander becomes a run (if not forced by distance). */
    private final float runChance;

    /** Minimum random wander distance from the chosen center. */
    private final double minRadius;

    /** Maximum random wander distance from the chosen center. */
    private final double maxRadius;

    /**
     * If > 0: any chosen wander target at/above this distance forces running.
     * If <= 0: disabled, only runChance decides.
     */
    private final double runDistanceThreshold;

    // ------------------------------------------------------------
    // Chosen target for this wander instance
    // ------------------------------------------------------------

    /** Destination coordinates we picked in canUse() / pickPosition(). */
    private double wantedX;
    private double wantedY;
    private double wantedZ;

    /** Whether THIS wander instance is considered "running". */
    private boolean running;

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

        // This goal moves the mob, so it claims MOVE.
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /**
     * Determines whether we should START wandering right now.
     *
     * Vanilla-like behavior:
     * - requires no current path (navigation done)
     * - uses a random roll to not wander every tick
     * - picks a random nearby destination
     */
    @Override
    public boolean canUse() {
        // Don't wander while being ridden or riding something.
        if (mob.isPassenger() || mob.isVehicle()) {
            return false;
        }

        // Only start wandering if we are currently not pathing anywhere.
        if (!mob.getNavigation().isDone()) {
            return false;
        }

        // Low random chance per tick (similar to RandomStrollGoal):
        // 1/120 chance each tick -> on average once every ~6 seconds.
        if (mob.getRandom().nextInt(120) != 0) {
            return false;
        }

        // Choose a destination; this will also decide walk vs run.
        Vec3 target = this.pickPosition();
        if (target == null) {
            return false;
        }

        // Store destination for start().
        this.wantedX = target.x;
        this.wantedY = target.y;
        this.wantedZ = target.z;

        return true;
    }

    /**
     * Keep wandering while navigation is actively pathing.
     * This matches what we also use in tick() for consistent MOVE_MODE syncing.
     */
    @Override
    public boolean canContinueToUse() {
        return mob.getNavigation().isInProgress();
    }

    /**
     * Called once when the goal starts.
     * Sets authoritative MOVE_MODE, calls hook, and begins navigation.
     */
    @Override
    public void start() {
        // Choose the correct navigation speed based on whether this wander instance is running.
        double speed = running ? runSpeed : walkSpeed;

        // ------------------------------------------------------------
        // AUTHORITATIVE move-mode sync (client animations use this)
        // ------------------------------------------------------------
        mob.setMoveMode(running
                ? CatoBaseMob.MOVE_RUN
                : CatoBaseMob.MOVE_WALK
        );

        // Hook for subclasses / per-mob cosmetics (e.g. particles, sound, toggles).
        mob.onWanderStart(running);

        // Start walking/running to the chosen target.
        mob.getNavigation().moveTo(this.wantedX, this.wantedY, this.wantedZ, speed);
    }

    /**
     * Called once when the goal ends (path finished, interrupted, replaced by higher priority goal, etc.).
     * Resets authoritative MOVE_MODE and calls the stop hook.
     */
    @Override
    public void stop() {
        // Reset authoritative movement state so client animations go back to idle.
        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        // Hook for cleanup (stop particles, reset state, etc.)
        mob.onWanderStop();
    }

    /**
     * We want tick() every tick so MOVE_MODE stays correct even when pathing "micro-stops".
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Runs every tick while the goal is active.
     * Keeps MOVE_MODE in sync with actual navigation status.
     *
     * Why we do this:
     * Navigation can sometimes temporarily flip state during repaths/stutters.
     * This makes the client-side animation state more stable and correct.
     */
    @Override
    public void tick() {
        // Use the same condition as canContinueToUse().
        boolean moving = mob.getNavigation().isInProgress();

        // If nav isn't moving anymore, ensure we go idle.
        if (!moving) {
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            return;
        }

        // Still moving -> keep whichever mode this wander chose.
        mob.setMoveMode(running ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_WALK);
    }

    // -----------------------------
    // Position picking with home radius
    // -----------------------------

    /**
     * Picks a random valid destination around a "center".
     *
     * Center logic:
     * - If the mob has "stayWithinHomeRadius" enabled and a homePos is set, we wander around homePos.
     * - Otherwise, we wander around the mob's current position.
     *
     * Validation:
     * - must respect home radius (if enabled)
     * - must find ground and have space above it
     *
     * Side effect:
     * - calls decideRunOrWalk(dist) to decide whether THIS wander instance will run.
     */
    private Vec3 pickPosition() {
        Level level = mob.level();

        // Choose the center point for wandering.
        BlockPos center;
        if (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null) {
            center = mob.getHomePos();
        } else {
            center = mob.blockPosition();
        }

        // Precompute home radius constraint if enabled.
        double homeRadius = mob.shouldStayWithinHomeRadius() ? mob.getHomeRadius() : Double.MAX_VALUE;
        double homeRadiusSqr = homeRadius * homeRadius;

        // Try several random samples to find something valid.
        for (int attempt = 0; attempt < 10; attempt++) {
            // Random angle + distance in [minRadius..maxRadius]
            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = this.minRadius + mob.getRandom().nextDouble() * (this.maxRadius - this.minRadius);

            double dx = Math.cos(angle) * dist;
            double dz = Math.sin(angle) * dist;

            int x = center.getX() + Mth.floor(dx);
            int z = center.getZ() + Mth.floor(dz);
            int y = center.getY();

            BlockPos pos = new BlockPos(x, y, z);

            // Enforce home circle if enabled (keeps mobs from drifting too far away).
            if (mob.shouldStayWithinHomeRadius() && center.distSqr(pos) > homeRadiusSqr) {
                continue;
            }

            // Find a solid ground block at/under this position.
            BlockPos ground = findGround(level, pos);
            if (ground == null) {
                continue;
            }

            // We need air above ground to stand in.
            if (!level.isEmptyBlock(ground.above())) {
                continue;
            }

            // Decide walk vs run based on how far away this target is.
            decideRunOrWalk(dist);

            // Return a Vec3 at the center/bottom of the ground block (nice for navigation targets).
            return Vec3.atBottomCenterOf(ground);
        }

        // No valid position found this attempt.
        return null;
    }

    /**
     * Decide whether this wander should run or walk.
     *
     * Rules:
     * - If runSpeed is invalid (<= 0), we can never run.
     * - If runDistanceThreshold is enabled and distance >= threshold => force run.
     * - Otherwise, roll runChance to decide.
     */
    private void decideRunOrWalk(double distance) {
        boolean canRun = (runSpeed > 0.0D);
        if (!canRun) {
            this.running = false;
            return;
        }

        // Force-run when the chosen destination is far enough (optional feature).
        boolean forceRun = (runDistanceThreshold > 0.0D && distance >= runDistanceThreshold);

        if (forceRun) {
            this.running = true;
        } else if (runChance > 0.0F && mob.getRandom().nextFloat() < this.runChance) {
            // Probabilistic run (e.g. 10% of wanders are runs)
            this.running = true;
        } else {
            this.running = false;
        }
    }

    /**
     * Very simple "find ground" helper:
     * Starting from the candidate position, walk downward until we hit a non-empty block
     * (or world bottom). This is a lightweight alternative to more complex pathfinding checks.
     */
    private BlockPos findGround(Level level, BlockPos start) {
        BlockPos pos = start;

        // Drop until we reach solid or world bottom.
        // The "pos and pos.below" empty check avoids stopping inside floating air pockets.
        while (pos.getY() > level.getMinBuildHeight()
                && level.isEmptyBlock(pos)
                && level.isEmptyBlock(pos.below())) {
            pos = pos.below();
        }

        // If we still ended in air, there's no valid ground here.
        if (level.isEmptyBlock(pos)) {
            return null;
        }

        return pos;
    }
}
