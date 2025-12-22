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
            // fallback to current target if any
            threat = mob.getTarget();
        }
        if (threat == null) {
            mob.getNavigation().stop();
            return;
        }

        // Recalc every few ticks to reduce spam
        if (recalcCooldown-- > 0) return;
        recalcCooldown = 5;

        // ------------------------------------------------------------
        // Use vanilla helper: pick a reachable position AWAY from threat
        // ------------------------------------------------------------
        // Horizontal search radius and vertical range for candidate points:
        int horiz = (int) Math.max(8, Math.ceil(info.fleeDesiredDistance()));
        int vert = 7;

        // DefaultRandomPos tries to find a pathable point away from threat.
        // It returns null if it can't find something reasonable.
        net.minecraft.world.phys.Vec3 pos =
                net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(
                        mob,
                        horiz,
                        vert,
                        threat.position()
                );

        if (pos == null) {
            // Fallback: at least try to move directly away (your old approach),
            // but clamp to something sane to reduce wild unreachable targets.
            net.minecraft.world.phys.Vec3 away = mob.position().subtract(threat.position());
            if (away.lengthSqr() < 0.0001) away = new net.minecraft.world.phys.Vec3(1, 0, 0);
            away = away.normalize();

            double dist = Math.max(6.0D, info.fleeDesiredDistance());
            net.minecraft.world.phys.Vec3 dest = mob.position().add(away.scale(dist));

            mob.getNavigation().moveTo(dest.x, dest.y, dest.z, info.fleeSpeedModifier());
            mob.getLookControl().setLookAt(dest.x, dest.y + 1.0, dest.z);
            return;
        }

        // ------------------------------------------------------------
        // Optional: respect home radius if your mob uses it
        // (prevents fleeing outside your "home" bounds)
        // ------------------------------------------------------------
        if (mob.shouldStayWithinHomeRadius()) {
            net.minecraft.core.BlockPos home = mob.getHomePos();
            if (home != null) {
                double max = Math.max(1.0D, mob.getHomeRadius());
                double maxSqr = max * max;

                // If candidate is too far from home, clamp direction back toward home.
                if (pos.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(home)) > max) {
                    net.minecraft.world.phys.Vec3 homeVec = net.minecraft.world.phys.Vec3.atCenterOf(home);
                    net.minecraft.world.phys.Vec3 dir = pos.subtract(homeVec);

                    if (dir.lengthSqr() > 0.0001) {
                        dir = dir.normalize();
                        pos = homeVec.add(dir.scale(max - 1.0D));
                    }
                }
            }
        }

        // Move to the chosen flee position
        mob.getNavigation().moveTo(pos.x, pos.y, pos.z, info.fleeSpeedModifier());

        // Look where we're going (less "staring at attacker" during panic)
        mob.getLookControl().setLookAt(pos.x, pos.y + 1.0, pos.z);
    }
}
