package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoAttackId;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class CatoRangedAttackGoal extends Goal {

    private final CatoBaseMob mob;
    private LivingEntity target;

    // simple local cooldown so we don't spam startTimedAttack every tick
    private int localCooldownTicks = 0;

    // path recalculation throttle (prevents moveTo spam)
    private int ticksUntilNextPathRecalc = 0;

    public CatoRangedAttackGoal(CatoBaseMob mob) {
        this.mob = mob;
        // ✅ claim MOVE so ranged can chase into range
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean allowedCombatNow() {
        if (mob.isSleeping()) return false;
        if (mob.isFleeing()) return false;

        final CatoMobSpeciesInfo info = mob.infoServer();
        final CatoMobTemperament temp = info.temperament();

        // ✅ match melee logic: hostile attacks freely, neutral needs angerTime
        if (temp == CatoMobTemperament.NEUTRAL) {
            if (!info.retaliateWhenAngered()) return false;
            return mob.angerTime > 0;
        }

        // HOSTILE: no angerTime requirement
        return true;
    }

    private boolean combatStyleAllowsRanged(CatoMobSpeciesInfo info, LivingEntity target) {
        // onlyUseMelee => never ranged
        if (info.onlyUseMelee()) return false;

        // onlyUseRanged => always ranged (if rangedEnabled)
        if (info.onlyUseRanged()) return true;

        // rangedUnlessClose => ranged only when we "should use ranged"
        if (info.rangedUnlessClose()) {
            return mob.shouldUseRangedAgainst(target);
        }

        // default: if ranged is enabled, it's allowed
        return true;
    }

    @Override
    public boolean canUse() {
        if (!allowedCombatNow()) return false;

        this.target = mob.getTarget();
        if (this.target == null || !this.target.isAlive()) return false;

        final CatoMobSpeciesInfo info = mob.infoServer();
        if (!info.rangedEnabled()) return false;

        // ✅ combat-style gate (this is what lets melee take over when close)
        if (!combatStyleAllowsRanged(info, target)) return false;

        // ✅ IMPORTANT: do NOT range-gate here.
        // We want the goal to RUN even when out of range so it can CHASE into range.
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // re-evaluate constantly so we can switch to melee when close (rangedUnlessClose)
        return canUse();
    }

    @Override
    public void start() {
        localCooldownTicks = 0;
        ticksUntilNextPathRecalc = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        this.mob.getNavigation().stop();
        this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
        // don't clear timed attack state here; base handles it
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;

        final CatoMobSpeciesInfo info = mob.infoServer();

        // keep looking at target
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        final var nav = mob.getNavigation();

        // ---------------------------
        // Chase into firing distance
        // ---------------------------
        double r = Math.max(0.0D, info.rangedTriggerRange());
        double distSqr = mob.distanceToSqr(target);
        boolean inRange = (r <= 0.0D) || (distSqr <= r * r);

        // throttle path recalcs
        ticksUntilNextPathRecalc = Math.max(0, ticksUntilNextPathRecalc - 1);

        if (!inRange) {
            // Too far -> chase the target so we can shoot again.
            if (ticksUntilNextPathRecalc == 0) {
                boolean started = nav.moveTo(target, info.chaseSpeedModifier());
                ticksUntilNextPathRecalc = started ? 4 : 1;
            }

            mob.setMoveMode(CatoBaseMob.MOVE_RUN);

            // while we are chasing, don't try to fire
            if (localCooldownTicks > 0) localCooldownTicks--;
            return;
        }

        // In range -> stop and shoot (clean ranged feel)
        nav.stop();
        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        // ---------------------------
        // Fire logic
        // ---------------------------
        if (localCooldownTicks > 0) {
            localCooldownTicks--;
            return;
        }

        // optional LOS gate (recommended): only fire when you can see
        if (!mob.hasLineOfSight(target)) {
            localCooldownTicks = 5; // small retry delay
            return;
        }

        boolean started = mob.startTimedAttack(target, CatoAttackId.RANGED_NORMAL);

        if (started) {
            int cd = Math.max(1, info.rangedCooldownTicks());
            localCooldownTicks = cd;
        } else {
            // failed to start (already attacking etc.) -> small retry delay
            localCooldownTicks = 5;
        }
    }
}
