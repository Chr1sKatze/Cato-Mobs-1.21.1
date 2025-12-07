package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntitySelector;

import java.util.EnumSet;

/**
 * Custom melee attack goal for CatoBaseMob.
 *
 * What this goal does:
 * - Chases a target using navigation/pathfinding.
 * - Keeps looking at the target while active.
 * - Starts a "timed attack" when in range:
 *     -> It does NOT deal damage immediately.
 *     -> It calls mob.startTimedAttack(target) which:
 *         - triggers the attack animation (server-side flag)
 *         - schedules the hit moment (attackTicksUntilHit)
 *         - and the actual damage is applied later in CatoBaseMob.aiStep()
 *
 * Why it's custom:
 * - We want consistent attack range based on species info (attackHitRange)
 * - We want animation hooks (onChaseStart / onChaseTick / onChaseStop)
 * - We want an authoritative MOVE_MODE to drive client animations reliably
 */
public class CatoMeleeAttackGoal extends Goal {

    /** The owning mob (your shared base class). */
    private final CatoBaseMob mob;

    /** Navigation speed modifier while chasing (higher = faster). */
    private final double speedModifier;

    /**
     * If false:
     * - mob stops moving if it can't see the target (line-of-sight required)
     * If true:
     * - mob keeps following even when it temporarily loses sight
     */
    private final boolean followEvenIfNotSeen;

    /**
     * Base cooldown between starting timed attacks (ticks).
     * This is separate from animation timing:
     * - cooldown = "how often am I allowed to start an attack"
     * - attackAnimTicksRemaining / attackTicksUntilHit = "how the attack plays out"
     */
    private final int baseAttackCooldownTicks;

    /** Countdown until we are allowed to start another timed attack. */
    private int attackCooldown;

    /**
     * Throttle for canUse().
     * Vanilla MeleeAttackGoal doesn't recompute expensive checks every tick.
     */
    private long lastCanUseCheck;

    /**
     * Timer for how often we recompute the path to the target.
     * Repathing every tick is expensive and can cause jitter.
     */
    private int ticksUntilNextPathRecalc;

    public CatoMeleeAttackGoal(CatoBaseMob mob, double speedModifier, boolean followEvenIfNotSeen, int attackCooldownTicks) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.followEvenIfNotSeen = followEvenIfNotSeen;
        this.baseAttackCooldownTicks = attackCooldownTicks;

        // This goal affects movement + where the mob is looking.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /**
     * Can this goal start right now?
     * This is called often by the AI system, so we throttle to once per 20 ticks.
     */
    @Override
    public boolean canUse() {
        long gameTime = this.mob.level().getGameTime();

        // Vanilla-style throttle: only evaluate once per second.
        if (gameTime - this.lastCanUseCheck < 20L) {
            return false;
        }
        this.lastCanUseCheck = gameTime;

        // Must have a valid living target
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        // If we can path to the target, start chasing
        var navigation = this.mob.getNavigation();
        var path = navigation.createPath(target, 0);
        if (path != null) {
            return true;
        }

        // If we can't path, we can still start if we are already within attack reach.
        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSqr = this.getAttackReachSqr(target);
        return distSqr <= reachSqr;
    }

    /**
     * Should the goal keep running?
     * Important detail:
     * - We do NOT require navigation to still be active, because once in range
     *   the mob might stand still and repeatedly attack.
     */
    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.mob.getTarget();

        // Stop if target is gone
        if (target == null || !target.isAlive()) {
            return false;
        }

        // Respect "restriction area" rules (vanilla feature for mobs with a home restriction)
        if (!this.mob.isWithinRestriction(target.blockPosition())) {
            return false;
        }

        // Don't attack creative/spectator players
        if (target instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) {
                return false;
            }
        }

        // We intentionally allow continuing even if path is "done" (standing in range).
        return true;
    }

    /**
     * Called once when the goal starts.
     * - Begin pathing to the target
     * - Mark the mob aggressive
     * - Reset timers/cooldowns
     */
    @Override
    public void start() {
        LivingEntity target = this.mob.getTarget();
        if (target != null) {
            this.mob.getNavigation().moveTo(target, this.speedModifier);

            // Hook for subclasses (e.g., set combat stance, start run anim, etc.)
            this.mob.onChaseStart(target);
        }

        this.mob.setAggressive(true);

        // Force immediate repath behavior next tick
        this.ticksUntilNextPathRecalc = 0;

        // Often you want "attack immediately if in range"
        this.attackCooldown = 0;
    }

    /**
     * Called once when the goal stops.
     * - Optionally clears target (vanilla behavior: only clears if creative/spectator)
     * - Stops pathing
     * - Resets movement mode to IDLE (for client animation)
     * - Calls the chase stop hook
     */
    @Override
    public void stop() {
        LivingEntity target = this.mob.getTarget();

        // Match vanilla:
        // If the target is creative/spectator, NO_CREATIVE_OR_SPECTATOR will fail, so we clear it.
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            this.mob.setTarget(null);
        }

        this.mob.setAggressive(false);
        this.mob.getNavigation().stop();

        // Authoritative movement mode for animations
        this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        // Hook for subclasses (clear run anim, clear combat stance, etc.)
        this.mob.onChaseStop();
    }

    /**
     * We want tick() to run every tick to keep movement mode + looking responsive.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Runs every tick while the goal is active.
     * Responsibilities:
     * 1) Keep looking at the target
     * 2) Recalculate path every few ticks
     * 3) Manage attack cooldown and start timed attacks when in range
     * 4) Update MOVE_MODE so client animation matches what's happening
     * 5) Call chase tick hook for mob-specific logic
     */
    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            return;
        }

        // Always face the target (max yaw/pitch here are just look control limits)
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSqr = this.getAttackReachSqr(target);

        // ----------------------------
        // Path recalculation (repath)
        // ----------------------------
        this.ticksUntilNextPathRecalc = Math.max(this.ticksUntilNextPathRecalc - 1, 0);

        if (this.ticksUntilNextPathRecalc == 0) {
            boolean canSee = this.mob.getSensing().hasLineOfSight(target);

            // If we require vision and can't see, stop chasing for now
            if (!canSee && !this.followEvenIfNotSeen) {
                this.mob.getNavigation().stop();
            } else {
                // Otherwise (or if followEvenIfNotSeen), keep moving toward target
                this.mob.getNavigation().moveTo(target, this.speedModifier);
            }

            // Repath interval: 4–10 ticks (vanilla-like)
            this.ticksUntilNextPathRecalc = 4 + this.mob.getRandom().nextInt(7);
        }

        // ----------------------------
        // Attack cooldown
        // ----------------------------
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }

        // Start a new timed attack only if:
        // - target is within reach
        // - cooldown is ready
        if (distSqr <= reachSqr && this.attackCooldown <= 0) {
            // This schedules damage to happen later in CatoBaseMob.aiStep()
            if (this.mob.startTimedAttack(target)) {
                this.attackCooldown = this.baseAttackCooldownTicks;
            }
        }

        // ----------------------------
        // Animation / movement mode sync
        // ----------------------------
        boolean moving = !this.mob.getNavigation().isDone();

        // If navigation is actively moving, use RUN mode.
        // If in range and standing still, use IDLE (or you could choose WALK if desired).
        this.mob.setMoveMode(moving ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);

        // Hook for mob-specific logic (e.g., "isMoving" affects run cycle)
        this.mob.onChaseTick(target, moving);
    }

    /**
     * Attack reach calculation.
     *
     * IMPORTANT:
     * - Vanilla uses bounding box reach calculations.
     * - You use species-configured attackHitRange to keep:
     *     - "when we decide to attack"
     *     - "when damage is applied"
     *   consistent with each other.
     *
     * Return value is squared distance to avoid sqrt cost in comparisons.
     */
    protected double getAttackReachSqr(LivingEntity target) {
        CatoMobSpeciesInfo info = this.mob.getSpeciesInfo(); // same package access in your codebase

        double hitRange = info.attackHitRange();
        if (hitRange <= 0.0D) {
            hitRange = 2.0D; // fallback so broken configs don't disable attacks
        }

        return hitRange * hitRange;
    }
}
