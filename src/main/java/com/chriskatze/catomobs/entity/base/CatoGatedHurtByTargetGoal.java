package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

public class CatoGatedHurtByTargetGoal extends HurtByTargetGoal {

    private final CatoBaseMob mob;

    public CatoGatedHurtByTargetGoal(CatoBaseMob mob) {
        super(mob);
        this.mob = mob;
    }

    private boolean retaliationAllowedNow() {
        // FLEE OVERRIDES RETALIATION
        if (mob.isFleeing()) return false;

        CatoMobSpeciesInfo info = mob.getSpeciesInfo();

        // If species disables retaliation entirely, never start HurtBy retaliation.
        if (!info.retaliateWhenAngered()) return false;

        // Hostile mobs: allowed to retaliate (vanilla style).
        if (info.temperament() == CatoMobTemperament.HOSTILE) return true;

        // Neutral mobs: only retaliate while "angerTime" is active.
        return mob.angerTime > 0;
    }

    @Override
    public boolean canUse() {
        if (!retaliationAllowedNow()) return false;
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (!retaliationAllowedNow()) return false;
        return super.canContinueToUse();
    }
}
