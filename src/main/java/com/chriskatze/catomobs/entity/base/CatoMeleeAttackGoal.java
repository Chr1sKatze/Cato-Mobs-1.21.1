package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntitySelector;

import java.util.EnumSet;

public class CatoMeleeAttackGoal extends Goal {

    private final CatoBaseMob mob;
    private final double speedModifier;
    private final boolean followEvenIfNotSeen;
    private final int baseAttackCooldownTicks;

    private int attackCooldown;
    private int ticksUntilNextPathRecalc;

    public CatoMeleeAttackGoal(CatoBaseMob mob, double speedModifier, boolean followEvenIfNotSeen, int attackCooldownTicks) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.followEvenIfNotSeen = followEvenIfNotSeen;
        this.baseAttackCooldownTicks = attackCooldownTicks;
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

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        CatoMobTemperament t = this.mob.getSpeciesInfo().temperament();
        if (t != CatoMobTemperament.HOSTILE && this.mob.angerTime <= 0) return false;

        if (!this.mob.isWithinRestriction(target.blockPosition())) return false;

        if (target instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return false;
        }

        return true;
    }

    @Override
    public void start() {
        LivingEntity target = this.mob.getTarget();
        if (target != null) {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
            this.mob.onChaseStart(target);
        }

        this.mob.setAggressive(true);
        this.ticksUntilNextPathRecalc = 0;
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

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;

        CatoMobSpeciesInfo info = this.mob.getSpeciesInfo();

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());

        double trigger = info.attackTriggerRange();
        if (trigger <= 0.0D) trigger = 2.0D;
        double triggerSqr = trigger * trigger;

        boolean inTriggerRange = distSqr <= triggerSqr;

        // ------------------------------------------------------------
        // NEW: root during attack animation (optional)
        // ------------------------------------------------------------
        if (!info.moveDuringAttackAnimation() && this.mob.isAttacking()) {
            this.mob.getNavigation().stop();
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
            this.mob.getNavigation().stop();
            this.ticksUntilNextPathRecalc = 4;
        } else {
            if (this.mob.getNavigation().isDone()) {
                this.ticksUntilNextPathRecalc = 0;
            }

            this.ticksUntilNextPathRecalc = Math.max(this.ticksUntilNextPathRecalc - 1, 0);

            if (this.ticksUntilNextPathRecalc == 0) {
                boolean canSee = this.mob.getSensing().hasLineOfSight(target);

                if (!canSee && !this.followEvenIfNotSeen) {
                    this.mob.getNavigation().stop();
                    this.ticksUntilNextPathRecalc = 4;
                } else {
                    boolean started = this.mob.getNavigation().moveTo(target, this.speedModifier);

                    if (!started) {
                        this.ticksUntilNextPathRecalc = 1;
                    } else {
                        this.ticksUntilNextPathRecalc = (distSqr > 16 * 16)
                                ? (2 + this.mob.getRandom().nextInt(3))
                                : (4 + this.mob.getRandom().nextInt(7));
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
        boolean moving = this.mob.getNavigation().isInProgress();
        this.mob.setMoveMode(moving ? CatoBaseMob.MOVE_RUN : CatoBaseMob.MOVE_IDLE);

        this.mob.onChaseTick(target, moving);
    }

    protected double getAttackReachSqr(LivingEntity target) {
        CatoMobSpeciesInfo info = this.mob.getSpeciesInfo();

        double hitRange = info.attackHitRange();
        if (hitRange <= 0.0D) hitRange = 2.0D;

        return hitRange * hitRange;
    }
}
