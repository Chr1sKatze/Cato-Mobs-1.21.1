package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobTemperament;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

public class CatoGatedHurtByTargetGoal extends HurtByTargetGoal {

    private final CatoBaseMob mob;

    public CatoGatedHurtByTargetGoal(CatoBaseMob mob) {
        super(mob);
        this.mob = mob;
    }

    private boolean retaliationAllowedNow() {
        // Hostile mobs can always retaliate (vanilla style)
        if (mob.getSpeciesInfo().temperament() == CatoMobTemperament.HOSTILE) return true;

        // Neutral retaliators: only while anger timer is active
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
