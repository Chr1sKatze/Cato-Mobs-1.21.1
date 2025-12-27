package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoAttackId;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

public class CatoMeleeAttackGoal extends Goal {

    private final CatoBaseMob mob;
    private final double speedModifier;
    private final boolean followEvenIfNotSeen;
    private final int baseAttackCooldownTicks;

    private int attackCooldown;
    private int ticksUntilNextPathRecalc;

    // ✅ NEW: "special after X normal hits"
    private int normalHitsSinceLastSpecial = 0;
    private LivingEntity lastTarget = null;

    public CatoMeleeAttackGoal(CatoBaseMob mob, double speedModifier, boolean followEvenIfNotSeen, int attackCooldownTicks) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.followEvenIfNotSeen = followEvenIfNotSeen;
        this.baseAttackCooldownTicks = attackCooldownTicks;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean canAttackNow() {
        // FLEE OVERRIDES COMBAT
        if (this.mob.isFleeing()) return false;

        final CatoMobSpeciesInfo info = this.mob.infoServer();
        final CatoMobTemperament t = info.temperament();

        // Hostile: allowed to attack whenever it has a valid target
        if (t == CatoMobTemperament.HOSTILE) return true;

        // Neutral: only attack if species allows retaliation AND anger is active
        if (!info.retaliateWhenAngered()) return false;
        return this.mob.angerTime > 0;
    }

    private static boolean isInvalidPlayerTarget(LivingEntity target) {
        if (!(target instanceof Player p)) return false;
        return p.isCreative() || p.isSpectator();
    }

    @Override
    public boolean canUse() {
        final LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (isInvalidPlayerTarget(target)) return false;

        if (!canAttackNow()) return false;

        final var targetPos = target.blockPosition();
        return this.mob.isWithinRestriction(targetPos);
    }

    @Override
    public boolean canContinueToUse() {
        final LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (isInvalidPlayerTarget(target)) return false;

        if (!canAttackNow()) return false;

        final var targetPos = target.blockPosition();
        return this.mob.isWithinRestriction(targetPos);
    }

    @Override
    public void start() {
        final LivingEntity target = this.mob.getTarget();
        if (target != null) {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
            this.mob.onChaseStart(target);
        }

        this.mob.setAggressive(true);
        this.ticksUntilNextPathRecalc = 0;
        this.attackCooldown = 0;

        // ✅ NEW
        this.normalHitsSinceLastSpecial = 0;
        this.lastTarget = target;
    }

    @Override
    public void stop() {
        final LivingEntity target = this.mob.getTarget();

        // clear ONLY if the target is invalid (creative/spectator)
        if (target != null && !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            // target is creative/spectator -> clear
            this.mob.setTarget(null);
        }

        this.mob.setAggressive(false);
        this.mob.getNavigation().stop();
        this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
        this.mob.onChaseStop();

        // ✅ NEW
        this.normalHitsSinceLastSpecial = 0;
        this.lastTarget = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        final LivingEntity target = this.mob.getTarget();
        if (target == null) return;

        // ✅ NEW: reset the hit counter when the target changes
        if (target != this.lastTarget) {
            this.lastTarget = target;
            this.normalHitsSinceLastSpecial = 0;
        }

        // state can change while goal is running
        if (isInvalidPlayerTarget(target) || !canAttackNow()) {
            this.mob.getNavigation().stop();
            this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            this.mob.onChaseTick(target, false);
            return;
        }

        // local aliases (micro-optimizations / readability)
        final var nav = this.mob.getNavigation();
        final var rng = this.mob.getRandom();
        final var sensing = this.mob.getSensing();

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        final double distSqr = this.mob.distanceToSqr(target);

        // IMPORTANT: compute trigger ranges from species info
        final CatoMobSpeciesInfo info = this.mob.infoServer();

        final double normalTrigger = info.attackTriggerRange();
        final double specialTrigger = info.meleeSpecialTriggerRange();

        final boolean inNormalTriggerRange =
                normalTrigger <= 0.0D || distSqr <= normalTrigger * normalTrigger;

        final boolean inSpecialTriggerRange =
                specialTrigger <= 0.0D || distSqr <= specialTrigger * specialTrigger;

        // ------------------------------------------------------------
        // Movement gating during attack animation (delay + stop window)
        // ------------------------------------------------------------
        final boolean attacking = this.mob.isAttacking();
        final boolean canMoveThisTick = !attacking || this.mob.canMoveDuringCurrentAttackAnimTick();

        // If attacking but movement NOT allowed -> stop and do nothing else
        if (attacking && !canMoveThisTick) {
            nav.stop();
            this.ticksUntilNextPathRecalc = 4;

            if (this.attackCooldown > 0) this.attackCooldown--;

            this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            this.mob.onChaseTick(target, false);
            return;
        }

        // ------------------------------------------------------------
        // Chase / pathing
        // ------------------------------------------------------------
        if (attacking && canMoveThisTick) {
            // While attacking AND allowed to move, keep chasing during the animation.
            if (nav.isDone()) {
                this.ticksUntilNextPathRecalc = 0;
            }

            this.ticksUntilNextPathRecalc = Math.max(this.ticksUntilNextPathRecalc - 1, 0);

            if (this.ticksUntilNextPathRecalc == 0) {
                final boolean canSee = sensing.hasLineOfSight(target);

                if (!canSee && !this.followEvenIfNotSeen) {
                    nav.stop();
                    this.ticksUntilNextPathRecalc = 4;
                } else {
                    final boolean started = nav.moveTo(target, this.speedModifier);
                    this.ticksUntilNextPathRecalc = started ? 4 : 1;
                }
            }
        } else {
            // Use NORMAL trigger range to decide when to stop chasing
            if (inNormalTriggerRange) {
                nav.stop();
                this.ticksUntilNextPathRecalc = 4;
            } else {
                if (nav.isDone()) {
                    this.ticksUntilNextPathRecalc = 0;
                }

                this.ticksUntilNextPathRecalc = Math.max(this.ticksUntilNextPathRecalc - 1, 0);

                if (this.ticksUntilNextPathRecalc == 0) {
                    final boolean canSee = sensing.hasLineOfSight(target);

                    if (!canSee && !this.followEvenIfNotSeen) {
                        nav.stop();
                        this.ticksUntilNextPathRecalc = 4;
                    } else {
                        final boolean started = nav.moveTo(target, this.speedModifier);

                        if (!started) {
                            this.ticksUntilNextPathRecalc = 1;
                        } else {
                            this.ticksUntilNextPathRecalc = (distSqr > 256.0D) // 16*16
                                    ? (2 + rng.nextInt(3))
                                    : (4 + rng.nextInt(7));
                        }
                    }
                }
            }
        }

        // Attack cooldown
        if (this.attackCooldown > 0) this.attackCooldown--;

        // ------------------------------------------------------------
        // ✅ Attack decision + trigger gating + "eligible hit slots"
        // ------------------------------------------------------------
        if (this.attackCooldown <= 0) {
            final boolean specialEnabled = info.meleeSpecialEnabled();
            final int afterHits = info.meleeSpecialAfterNormalHits(); // 0 = pure chance mode
            final float chance = info.meleeSpecialUseChance();

            // Eligible slot?
            final boolean eligibleSlot = specialEnabled && (
                    afterHits <= 0 || this.normalHitsSinceLastSpecial >= afterHits
            );

            // Only roll chance if eligible AND in special trigger range
            final boolean doSpecial =
                    eligibleSlot
                            && inSpecialTriggerRange
                            && chance > 0.0f
                            && rng.nextFloat() < chance;

            // Use chosen trigger range
            final boolean inChosenTrigger = doSpecial ? inSpecialTriggerRange : inNormalTriggerRange;

            if (inChosenTrigger) {
                final CatoAttackId id = doSpecial ? CatoAttackId.MELEE_SPECIAL : CatoAttackId.MELEE_NORMAL;

                if (this.mob.startTimedAttack(target, id)) {
                    // cooldown based on attack type
                    this.attackCooldown = doSpecial
                            ? info.meleeSpecialCooldownTicks()
                            : this.baseAttackCooldownTicks;

                    // ✅ Update the counter to match the semantics:
                    if (afterHits <= 0) {
                        // pure chance mode: no counter needed
                        this.normalHitsSinceLastSpecial = 0;
                    } else {
                        if (doSpecial) {
                            // special fired -> reset cycle
                            this.normalHitsSinceLastSpecial = 0;
                        } else {
                            // normal attack happened
                            if (eligibleSlot) {
                                // This was an eligible slot but chance failed.
                                // Restart the cycle counting this normal as the first one.
                                // BUT: only restart if we were actually in special trigger range.
                                // If we weren't in range, keep the slot "ready" so it can trigger once in range.
                                if (inSpecialTriggerRange) {
                                    this.normalHitsSinceLastSpecial = 1;
                                } else {
                                    // Hold at threshold so the next time we're in range, it's still an eligible slot.
                                    this.normalHitsSinceLastSpecial = afterHits;
                                }
                            } else {
                                // Not yet eligible -> keep counting up (cap at afterHits to avoid overflow)
                                this.normalHitsSinceLastSpecial = Math.min(afterHits, this.normalHitsSinceLastSpecial + 1);
                            }
                        }
                    }
                }
            }
        }

        // MOVE_MODE sync
        final boolean moving = nav.isInProgress();
        this.mob.setMoveMode(moving ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);

        this.mob.onChaseTick(target, moving);
    }
}