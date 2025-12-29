package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoAttackId;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntitySelector;

import java.util.EnumSet;

public class CatoRangedAttackGoal extends Goal {

    private final CatoBaseMob mob;
    private LivingEntity target;

    private int localCooldownTicks = 0;
    private int ticksUntilNextPathRecalc = 0;

    // Add a counter for normal ranged hits
    private int rangedHitsSinceLastSpecial = 0;

    public CatoRangedAttackGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean allowedCombatNow() {
        if (mob.isSleeping()) return false;
        if (mob.isFleeing()) return false;

        final CatoMobSpeciesInfo info = mob.infoServer();
        if (info.temperament() == CatoMobTemperament.NEUTRAL) {
            return mob.angerTime > 0;
        }
        return true;
    }

    private boolean combatStyleAllowsRanged(CatoMobSpeciesInfo info, LivingEntity target) {
        if (info.onlyUseMelee()) return false;
        if (info.onlyUseRanged()) return true;
        if (info.rangedUnlessClose()) {
            return mob.shouldUseRangedAgainst(target);
        }
        return true;
    }

    // Helper method to check if the target is a creative or spectator player
    private static boolean isInvalidPlayerTarget(LivingEntity target) {
        if (!(target instanceof Player p)) return false; // Check if the target is a Player
        return EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target); // Check if the Player is creative or spectator
    }

    @Override
    public boolean canUse() {
        if (!allowedCombatNow()) return false;

        this.target = mob.getTarget();
        if (this.target == null || !this.target.isAlive()) return false;

        final CatoMobSpeciesInfo info = mob.infoServer();
        if (!info.rangedEnabled()) return false;

        return combatStyleAllowsRanged(info, target);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        localCooldownTicks = 0;
        ticksUntilNextPathRecalc = 0;
        rangedHitsSinceLastSpecial = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        this.mob.getNavigation().stop();
        this.mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;

        final CatoMobSpeciesInfo info = mob.infoServer();

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        final var nav = mob.getNavigation();
        final double distSqr = mob.distanceToSqr(target);
        final double normalTriggerRange = info.rangedTriggerRange();
        final boolean inNormalTriggerRange = normalTriggerRange <= 0.0D || distSqr <= normalTriggerRange * normalTriggerRange;

        // Throttle path recalcs
        ticksUntilNextPathRecalc = Math.max(0, ticksUntilNextPathRecalc - 1);

        if (!inNormalTriggerRange) {
            if (ticksUntilNextPathRecalc == 0) {
                boolean started = nav.moveTo(target, info.chaseSpeedModifier());
                ticksUntilNextPathRecalc = started ? 4 : 1;
            }

            mob.setMoveMode(CatoBaseMob.MOVE_RUN);
            if (localCooldownTicks > 0) localCooldownTicks--;
            return;
        }

        nav.stop();
        mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

        if (localCooldownTicks > 0) {
            localCooldownTicks--;
            return;
        }

        if (!mob.hasLineOfSight(target)) {
            localCooldownTicks = 5;
            return;
        }

        // Special Ranged Attack Logic
        final float specialChance = info.rangedSpecialUseChance();
        final boolean inSpecialTriggerRange = distSqr <= info.rangedSpecialTriggerRange() * info.rangedSpecialTriggerRange();

        // Check if we should use the special ranged attack
        boolean doSpecial = false;

        // We only trigger a special ranged attack if the attack counter has reached the threshold
        // and we are within the special range
        if (rangedHitsSinceLastSpecial >= info.rangedSpecialAfterNormalHits() && inSpecialTriggerRange) {
            // Chance roll for persistence
            if (mob.getRandom().nextFloat() < specialChance) {
                doSpecial = true;
            }
        }

        if (doSpecial) {
            // Perform the special ranged attack
            boolean started = mob.startTimedAttack(target, CatoAttackId.RANGED_SPECIAL);

            if (started) {
                int cd = Math.max(1, info.rangedSpecialCooldownTicks());
                localCooldownTicks = cd;
                rangedHitsSinceLastSpecial = 0;  // Reset after special attack
            } else {
                localCooldownTicks = 5;
            }
        } else {
            // Regular ranged attack
            boolean started = mob.startTimedAttack(target, CatoAttackId.RANGED_NORMAL);

            if (started) {
                int cd = Math.max(1, info.rangedCooldownTicks());
                localCooldownTicks = cd;
                rangedHitsSinceLastSpecial++; // Increment for every normal ranged hit
            } else {
                localCooldownTicks = 5;
            }
        }
    }
}
