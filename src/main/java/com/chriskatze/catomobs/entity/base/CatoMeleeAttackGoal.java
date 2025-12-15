package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.CatoMobTemperament;
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

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return false;

        CatoMobTemperament t = this.mob.getSpeciesInfo().temperament();
        if (t != CatoMobTemperament.HOSTILE && this.mob.angerTime <= 0) return false;

        return this.mob.isWithinRestriction(target.blockPosition());
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

        // Neutral retaliators should ONLY keep chasing while anger is active
        CatoMobTemperament t = this.mob.getSpeciesInfo().temperament();
        if (t != CatoMobTemperament.HOSTILE && this.mob.angerTime <= 0) {
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

    @Override
    public void stop() {
        LivingEntity target = this.mob.getTarget();

        if (target != null && !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            this.mob.setTarget(null);
        }

        this.mob.setAggressive(false);
        this.mob.getNavigation().stop();
        this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
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
        if (target == null) return;

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        double reachSqr = this.getAttackReachSqr(target);
        boolean inRange = distSqr <= reachSqr;

        // ✅ If we're in range, STOP moving and DON'T allow repath to re-start movement
        if (inRange) {
            this.mob.getNavigation().stop();
            this.ticksUntilNextPathRecalc = 4; // small delay so we don't instantly re-path next tick
        } else {
            // ✅ If navigation stalled but we're NOT in range, force an immediate repath
            if (this.mob.getNavigation().isDone()) {
                this.ticksUntilNextPathRecalc = 0;
            }

            // ----------------------------
            // Path recalculation (repath)
            // ----------------------------
            this.ticksUntilNextPathRecalc = Math.max(this.ticksUntilNextPathRecalc - 1, 0);

            if (this.ticksUntilNextPathRecalc == 0) {
                boolean canSee = this.mob.getSensing().hasLineOfSight(target);

                if (!canSee && !this.followEvenIfNotSeen) {
                    this.mob.getNavigation().stop();
                    this.ticksUntilNextPathRecalc = 4;
                } else {
                    // IMPORTANT: check return value (moveTo can fail sometimes)
                    boolean started = this.mob.getNavigation().moveTo(target, this.speedModifier);

                    if (!started) {
                        // Retry very soon if pathfinding failed this tick
                        this.ticksUntilNextPathRecalc = 1;
                    } else {
                        // Faster repath when far away helps follow a moving player better
                        this.ticksUntilNextPathRecalc = (distSqr > 16 * 16)
                                ? (2 + this.mob.getRandom().nextInt(3))   // 2–4 ticks when far
                                : (4 + this.mob.getRandom().nextInt(7));  // 4–10 ticks when close
                    }
                }
            }
        }

        // ----------------------------
        // Attack cooldown
        // ----------------------------
        if (this.attackCooldown > 0) this.attackCooldown--;

        if (inRange && this.attackCooldown <= 0) {
            if (this.mob.startTimedAttack(target)) {
                this.attackCooldown = this.baseAttackCooldownTicks;
            }
        }

        // ----------------------------
        // Animation / movement mode sync
        // ----------------------------
        boolean moving = !this.mob.getNavigation().isDone();
        this.mob.setMoveMode(moving ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);

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
