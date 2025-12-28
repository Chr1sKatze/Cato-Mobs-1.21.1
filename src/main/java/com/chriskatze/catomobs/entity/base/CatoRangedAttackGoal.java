package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoAttackId;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class CatoRangedAttackGoal extends Goal {

    private final CatoBaseMob mob;
    private LivingEntity target;

    // simple local cooldown so we don't spam startTimedAttack every tick
    private int localCooldownTicks = 0;

    public CatoRangedAttackGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.LOOK)); // don't claim MOVE; melee goal handles chase
    }

    @Override
    public boolean canUse() {
        if (mob.isSleeping()) return false;
        if (mob.isFleeing()) return false;

        // ✅ hard gate: only while angry (neutral mobs should stop when anger ends)
        if (mob.angerTime <= 0) return false;

        this.target = mob.getTarget();
        if (this.target == null || !this.target.isAlive()) return false;

        CatoMobSpeciesInfo info = mob.getSpeciesInfo();
        if (!info.rangedEnabled()) return false;

        double r = Math.max(0.0D, info.rangedTriggerRange());
        if (r > 0.0D && mob.distanceToSqr(target) > r * r) return false;

        // optional LOS gate (recommended for hitscan + realism)
        if (!mob.hasLineOfSight(target)) return false;

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse(); // same gates each tick
    }

    @Override
    public void start() {
        localCooldownTicks = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        // don't clear timed attack state here; base handles it
    }

    @Override
    public void tick() {
        if (target == null) return;

        // keep looking at target
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (localCooldownTicks > 0) {
            localCooldownTicks--;
            return;
        }

        // ✅ this triggers your shared timed-attack system
        boolean started = mob.startTimedAttack(target, CatoAttackId.RANGED_NORMAL);

        if (started) {
            // Use species ranged cooldown (NOT melee cooldown)
            int cd = Math.max(1, mob.getSpeciesInfo().rangedCooldownTicks());
            localCooldownTicks = cd;
        } else {
            // failed to start (already attacking etc.) -> small retry delay
            localCooldownTicks = 5;
        }
    }
}
