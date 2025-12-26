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
        // ✅ use cached species info when available
        final CatoMobSpeciesInfo info = mob.infoServer();

        LivingEntity threat = mob.getFleeThreat();
        if (threat == null || !threat.isAlive()) threat = mob.getTarget();

        if (threat == null) {
            mob.getNavigation().stop();
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);
            return;
        }

        final Vec3 mobPos = mob.position();
        final Vec3 threatPos = threat.position();

        // Stop fleeing once we are far enough away (SAFETY RADIUS)
        final double desired = Math.max(1.0D, info.fleeDesiredDistance());
        final double desiredSqr = desired * desired;

        if (mob.distanceToSqr(threat) >= desiredSqr) {
            mob.getNavigation().stop();
            mob.setMoveMode(CatoBaseMob.MOVE_IDLE);

            // Optional: stay alert
            mob.getLookControl().setLookAt(threat, 30.0F, 30.0F);
            return;
        }

        // Recalculate only every few ticks
        if (recalcCooldown-- > 0) {
            mob.setMoveMode(mob.getNavigation().isInProgress()
                    ? CatoBaseMob.MOVE_RUN
                    : CatoBaseMob.MOVE_IDLE
            );
            return;
        }
        recalcCooldown = 5;

        // Pick a reachable position AWAY from the threat
        final int horiz = (int) Math.max(8, Math.ceil(desired));
        final int vert = 7;

        Vec3 pos = net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(
                mob,
                horiz,
                vert,
                threatPos
        );

        if (pos == null) {
            // Fallback: step away manually
            Vec3 away = mobPos.subtract(threatPos);
            if (away.lengthSqr() < 0.0001) away = new Vec3(1, 0, 0);
            away = away.normalize();

            final double step = Math.max(6.0D, desired * 0.75D);
            pos = mobPos.add(away.scale(step));
        }

        // Respect home radius if enabled
        if (mob.shouldStayWithinHomeRadius()) {
            final var home = mob.getHomePos();
            if (home != null) {
                final Vec3 homeVec = Vec3.atCenterOf(home);
                final double max = Math.max(1.0D, mob.getHomeRadius());
                final double maxSqr = max * max;

                Vec3 delta = pos.subtract(homeVec);
                final double deltaSqr = delta.lengthSqr();

                if (deltaSqr > maxSqr && deltaSqr > 0.0001) {
                    delta = delta.scale(1.0D / Math.sqrt(deltaSqr)); // normalize without calling normalize()
                    pos = homeVec.add(delta.scale(max - 1.0D));
                }
            }
        }

        // Move + run animation intent
        final double speed = info.fleeSpeedModifier();
        mob.getNavigation().moveTo(pos.x, pos.y, pos.z, speed);
        mob.setMoveMode(CatoBaseMob.MOVE_RUN);
        mob.getLookControl().setLookAt(pos.x, pos.y + 1.0, pos.z);
    }
}
