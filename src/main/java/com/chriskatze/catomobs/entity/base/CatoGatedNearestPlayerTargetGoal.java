package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

public class CatoGatedNearestPlayerTargetGoal extends NearestAttackableTargetGoal<Player> {

    private final CatoBaseMob mob;

    public CatoGatedNearestPlayerTargetGoal(CatoBaseMob mob) {
        super(mob, Player.class, true);
        this.mob = mob;
    }

    private boolean allowedNow() {
        // Only HOSTILE mobs may auto-acquire players, and never while fleeing.
        return mob.getSpeciesInfo().temperament() == CatoMobTemperament.HOSTILE
                && !mob.isFleeing();
    }

    @Override
    public boolean canUse() {
        if (!allowedNow()) return false;
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (!allowedNow()) return false;
        return super.canContinueToUse();
    }
}
