package com.chriskatze.catomobs.entity.base;

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

    // cached per-goal-run
    private double triggerRangeSqr = 4.0D; // default 2 blocks

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

        // cache trigger range for this run
        final CatoMobSpeciesInfo info = this.mob.infoServer();
        double trigger = info.attackTriggerRange();
        if (trigger <= 0.0D) trigger = 2.0D;
        this.triggerRangeSqr = trigger * trigger;

        this.mob.setAggressive(true);
        this.ticksUntilNextPathRecalc = 0;
        this.attackCooldown = 0;
    }

    @Override
    public void stop() {
        final LivingEntity target = this.mob.getTarget();

        if (target != null && !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            this.mob.setTarget(null);
        }

        this.mob.setAggressive(false);
        this.mob.getNavigation().stop();
        this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
        this.mob.onChaseStop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        final LivingEntity target = this.mob.getTarget();
        if (target == null) return;

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
        final boolean inTriggerRange = distSqr <= this.triggerRangeSqr;

        // ------------------------------------------------------------
        // Movement gating during attack animation (delay + stop window)
        // ------------------------------------------------------------
        if (this.mob.isAttacking() && !this.mob.canMoveDuringCurrentAttackAnimTick()) {
            nav.stop();
            this.ticksUntilNextPathRecalc = 4;

            if (this.attackCooldown > 0) this.attackCooldown--;

            this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            this.mob.onChaseTick(target, false);
            return;
        }

        // ------------------------------------------------------------
        // Normal chase/pathing
        // ------------------------------------------------------------
        if (inTriggerRange) {
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

        // Attack cooldown
        if (this.attackCooldown > 0) this.attackCooldown--;

        if (inTriggerRange && this.attackCooldown <= 0) {
            if (this.mob.startTimedAttack(target)) {
                this.attackCooldown = this.baseAttackCooldownTicks;
            }
        }

        // MOVE_MODE sync
        final boolean moving = nav.isInProgress();
        this.mob.setMoveMode(moving ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);

        this.mob.onChaseTick(target, moving);
    }
}
