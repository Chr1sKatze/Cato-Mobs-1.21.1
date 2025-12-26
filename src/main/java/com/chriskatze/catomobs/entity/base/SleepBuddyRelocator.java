package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class SleepBuddyRelocator {

    private SleepBuddyRelocator() {}

    @Nullable
    public static BlockPos findBuddyAdjacentSleepSpot(CatoBaseMob mob, CatoMobSpeciesInfo info, @Nullable BlockPos fromPos) {
        if (fromPos == null) return null;
        if (!info.sleepPreferSleepingBuddies()) return null;

        final Level level = mob.level();

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

        int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());
        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());

        // IMPORTANT: buddy relocation should NOT consume the global path budget
        SleepSpotFinder.PathBudget pathBudget = null;

        // Reuse mutables to reduce allocations
        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();       // ground finder + validator scratch
        final BlockPos.MutableBlockPos standScratch = new BlockPos.MutableBlockPos();  // candidate stand pos

        int bestX = 0, bestY = 0, bestZ = 0;
        double bestDistSqr = Double.MAX_VALUE;
        boolean found = false;

        for (int dx = -rr; dx <= rr; dx++) {
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx == 0 && dz == 0) continue;

                int x = buddyBase.getX() + dx;
                int z = buddyBase.getZ() + dz;

                int startY = buddyBase.getY() + 8;

                int groundY = findGroundY(level, scratch, x, startY, z);
                if (groundY == Integer.MIN_VALUE) continue;

                int standY = groundY + 1;

                standScratch.set(x, standY, z);

                // Cheap pre-filter
                if (mob.isSleepSpotBlacklisted(standScratch)) continue;

                int roofDy = mob.roofDistance(standScratch, roofMax);
                if (roofDy == -1 || roofDy < minHeadroom) continue;

                // ✅ FULL validation, but now allocation-free (no immutable(), no internal new scratch)
                if (!SleepSpotFinder.isStandPosValidForSleepMutable(
                        mob, info, standScratch, minHeadroom, pathBudget, scratch
                )) {
                    continue;
                }

                double d2 = buddyBase.distToCenterSqr(x + 0.5D, standY, z + 0.5D);
                if (d2 < bestDistSqr) {
                    bestDistSqr = d2;
                    bestX = x;
                    bestY = standY;
                    bestZ = z;
                    found = true;
                }
            }
        }

        return found ? new BlockPos(bestX, bestY, bestZ) : null;
    }

    /**
     * Allocation-light ground finder (returns Y or MIN_VALUE).
     * - move up until empty
     * - move down until non-empty
     */
    private static int findGroundY(Level level, BlockPos.MutableBlockPos scratch, int x, int startY, int z) {
        int y = Mth.clamp(startY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);

        scratch.set(x, y, z);

        while (y < level.getMaxBuildHeight() - 1 && !level.isEmptyBlock(scratch)) {
            y++;
            scratch.setY(y);
        }

        while (y > level.getMinBuildHeight() && level.isEmptyBlock(scratch)) {
            y--;
            scratch.setY(y);
        }

        if (level.isEmptyBlock(scratch)) return Integer.MIN_VALUE;
        return y;
    }
}
