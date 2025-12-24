package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class CatoFleeGoal extends Goal {

    private final CatoBaseMob mob;
    private int recalcCooldown = 0;

    public CatoFleeGoal(CatoBaseMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.isFleeing();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.isFleeing();
    }

    @Override
    public void start() {
        recalcCooldown = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        CatoMobSpeciesInfo info = mob.getSpeciesInfo();
        LivingEntity threat = mob.getFleeThreat();

        if (threat == null || !threat.isAlive()) {
            threat = mob.getTarget();
        }
        if (threat == null) {
            mob.getNavigation().stop();
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            return;
        }

        // ------------------------------------------------------------
        // Stop fleeing once we are far enough away (SAFETY RADIUS)
        // ------------------------------------------------------------
        double desired = Math.max(1.0D, info.fleeDesiredDistance());
        double desiredSqr = desired * desired;

        if (mob.distanceToSqr(threat) >= desiredSqr) {
            mob.getNavigation().stop();
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

            // Optional: stay alert
            mob.getLookControl().setLookAt(threat, 30.0F, 30.0F);
            return;
        }

        // Recalculate only every few ticks
        if (recalcCooldown-- > 0) {
            mob.setMoveMode(
                    mob.getNavigation().isInProgress()
                            ? CatoBaseMob.MOVE_RUN
                            : CatoBaseMob.MOVE_IDLE
            );
            return;
        }
        recalcCooldown = 5;

        // ------------------------------------------------------------
        // Pick a reachable position AWAY from the threat
        // ------------------------------------------------------------
        int horiz = (int) Math.max(8, Math.ceil(desired));
        int vert = 7;

        Vec3 pos = net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(
                mob,
                horiz,
                vert,
                threat.position()
        );

        if (pos == null) {
            // Fallback: step away manually
            Vec3 away = mob.position().subtract(threat.position());
            if (away.lengthSqr() < 0.0001) away = new Vec3(1, 0, 0);
            away = away.normalize();

            double step = Math.max(6.0D, desired * 0.75D);
            pos = mob.position().add(away.scale(step));
        }

        // ------------------------------------------------------------
        // Respect home radius if enabled
        // ------------------------------------------------------------
        if (mob.shouldStayWithinHomeRadius()) {
            var home = mob.getHomePos();
            if (home != null) {
                Vec3 homeVec = Vec3.atCenterOf(home);
                double max = Math.max(1.0D, mob.getHomeRadius());

                Vec3 delta = pos.subtract(homeVec);
                if (delta.lengthSqr() > max * max && delta.lengthSqr() > 0.0001) {
                    delta = delta.normalize();
                    pos = homeVec.add(delta.scale(max - 1.0D));
                }
            }
        }

        // ------------------------------------------------------------
        // Move + run animation intent
        // ------------------------------------------------------------
        mob.getNavigation().moveTo(pos.x, pos.y, pos.z, info.fleeSpeedModifier());
        mob.setMoveMode(CatoBaseMob.MOVE_RUN);
        mob.getLookControl().setLookAt(pos.x, pos.y + 1.0, pos.z);
    }

}
