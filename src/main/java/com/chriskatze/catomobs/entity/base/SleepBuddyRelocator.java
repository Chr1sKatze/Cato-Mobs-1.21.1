package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class SleepBuddyRelocator {

    private SleepBuddyRelocator() {}

    @Nullable
    public static BlockPos findBuddyAdjacentSleepSpot(CatoBaseMob mob, CatoMobSpeciesInfo info, @Nullable BlockPos fromPos) {
        if (fromPos == null) return null;
        if (!info.sleepPreferSleepingBuddies()) return null;

        Level level = mob.level();

        int searchR = (int) Math.ceil(Math.max(0.0D, info.sleepBuddySearchRadius()));
        if (searchR <= 0) return null;

        var box = new net.minecraft.world.phys.AABB(fromPos).inflate(searchR, 4, searchR);

        var buddies = level.getEntitiesOfClass(
                CatoBaseMob.class,
                box,
                e -> e != mob
                        && e.isAlive()
                        && e.isSleeping()
                        && (info.sleepBuddyTypes() == null
                        || info.sleepBuddyTypes().isEmpty()
                        || info.sleepBuddyTypes().contains(e.getType()))
        );

        if (buddies.isEmpty()) return null;

        // Nearest sleeping buddy to THIS mob
        CatoBaseMob nearest = null;
        double best = Double.MAX_VALUE;
        for (CatoBaseMob b : buddies) {
            double d = b.distanceToSqr(mob);
            if (d < best) {
                best = d;
                nearest = b;
            }
        }
        if (nearest == null) return null;

        int rr = Math.max(1, info.sleepBuddyRelocateRadiusBlocks());
        BlockPos buddyBase = nearest.blockPosition();

        BlockPos bestPos = null;
        double bestDistSqr = Double.MAX_VALUE;

        int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());

        // IMPORTANT: buddy relocation should NOT consume the global path budget
        SleepSpotFinder.PathBudget pathBudget = null;

        // Small preference: try closer offsets first (spiral-ish via dist check)
        for (int dx = -rr; dx <= rr; dx++) {
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx == 0 && dz == 0) continue;

                // Prefer near-adjacent positions (skip far corners if you like)
                // if (Math.abs(dx) + Math.abs(dz) > rr) continue; // optional manhattan cutoff

                BlockPos candidate = buddyBase.offset(dx, 0, dz);

                // Snap to ground (same as normal search)
                BlockPos ground = SleepSpotFinder.findGround(level, candidate.above(8));
                if (ground == null) continue;

                BlockPos standPos = ground.above();

                // Validate under the same rules as regular sleep spots
                if (!SleepSpotFinder.isStandPosValidForSleep(mob, info, standPos, minHeadroom, pathBudget)) continue;

                // Prefer closest valid position to buddy (snuggly)
                double d2 = standPos.distSqr(buddyBase);
                if (d2 < bestDistSqr) {
                    bestDistSqr = d2;
                    bestPos = standPos;
                }
            }
        }

        return bestPos;
    }
}
