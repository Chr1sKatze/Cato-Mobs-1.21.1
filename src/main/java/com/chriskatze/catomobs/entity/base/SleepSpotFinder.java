package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

public final class SleepSpotFinder {

    private SleepSpotFinder() {}

    /** Tiny mutable counter so we can enforce maxPathAttempts across calls. */
    static final class PathBudget {
        int used = 0;
        final int max;
        PathBudget(int max) { this.max = Math.max(0, max); }
        boolean exhausted() { return max > 0 && used >= max; }
        void consumeOne() { used++; }
    }

    @Nullable
    public static BlockPos findRoofedSleepSpot(CatoBaseMob mob, CatoMobSpeciesInfo info) {
        Level level = mob.level();

        BlockPos center = (mob.shouldStayWithinHomeRadius() && mob.getHomePos() != null)
                ? mob.getHomePos()
                : mob.blockPosition();

        double mul = Math.max(0.1D, info.sleepSearchRadiusMultiplier());
        double maxRadius = Math.max(info.wanderMaxRadius(), 4.0D) * mul;
        double minRadius = Math.max(0.0D, Math.min(info.wanderMinRadius() * mul, maxRadius));

        double homeRadiusSqr = mob.shouldStayWithinHomeRadius()
                ? mob.getHomeRadius() * mob.getHomeRadius()
                : 0.0D;

        int maxAttempts = Math.max(1, info.sleepSearchMaxAttempts());
        PathBudget pathBudget = new PathBudget(info.sleepSearchMaxPathAttempts());

        final int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());

        // 0) Remembered spots first
        if (isCurrentlySleepTime(mob, info)) {
            for (CatoBaseMob.SleepSpotMemory mem : mob.getRememberedSleepSpots()) {
                BlockPos standPos = mem.pos;

                if (pathBudget.exhausted()) break;

                if (!isStandPosValidForSleep(mob, info, standPos, minHeadroom, pathBudget)) {
                    mob.strikeSleepSpot(standPos);
                    continue;
                }

                return standPos;
            }
        }

        // 1) Random candidate search
        BlockPos bestPos = null;
        int bestScore = Integer.MAX_VALUE;
        double bestDistSqr = Double.MAX_VALUE;

        Set<Long> testedPos = new HashSet<>();
        Set<Long> failedPathPos = new HashSet<>();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (pathBudget.exhausted()) break;

            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

            if (dist < info.sleepSearchMinDistance()) continue;

            int x = center.getX() + Mth.floor(Math.cos(angle) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(angle) * dist);
            int y = mob.blockPosition().getY() + 8;

            // Home radius respect (optional)
            if (info.sleepSearchRespectHomeRadius() && mob.shouldStayWithinHomeRadius()) {
                int dxHome = x - center.getX();
                int dzHome = z - center.getZ();
                if ((double) (dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) continue;
            }

            BlockPos probe = new BlockPos(x, y, z);

            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos standPos = ground.above();
            long key = standPos.asLong();

            if (!testedPos.add(key)) continue;
            if (failedPathPos.contains(key)) continue;

            boolean ok = isStandPosValidForSleep(mob, info, standPos, minHeadroom, pathBudget);
            if (!ok) {
                // If validation failed due to path, avoid retrying same spot
                // (We can’t perfectly know why it failed, but path failures are common.)
                failedPathPos.add(key);
                continue;
            }

            // Score: prefer low roof distance (cozy ceiling)
            int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
            int roofDy = mob.roofDistance(standPos, roofMax);
            int score = Math.max(0, roofDy - minHeadroom);

            // Social sleeping bonus
            int buddies = countSleepingBuddiesNear(mob, info, level, standPos);
            score -= buddies * Math.max(0, info.sleepBuddyScoreBonusPerBuddy());

            double distSqrToMob = mob.distanceToSqr(
                    standPos.getX() + 0.5D,
                    standPos.getY(),
                    standPos.getZ() + 0.5D
            );

            if (score < bestScore || (score == bestScore && distSqrToMob < bestDistSqr)) {
                bestScore = score;
                bestDistSqr = distSqrToMob;
                bestPos = standPos;
            }
        }

        return bestPos;
    }

    // ================================================================
    // Package-private helpers (shared by buddy-relocator)
    // ================================================================

    static boolean isStandPosValidForSleep(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            BlockPos standPos,
            int minHeadroom,
            PathBudget pathBudget
    ) {
        if (standPos == null) return false;

        Level level = mob.level();

        // Don’t stack sleepers on the same block
        if (isSleepingMobAlreadyHere(mob, level, standPos)) return false;

        // Home radius rule (optional)
        if (info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && mob.getHomePos() != null) {

            BlockPos home = mob.getHomePos();
            double r = mob.getHomeRadius();
            double dx = (standPos.getX() + 0.5D) - (home.getX() + 0.5D);
            double dz = (standPos.getZ() + 0.5D) - (home.getZ() + 0.5D);
            if ((dx * dx + dz * dz) > (r * r)) return false;
        }

        // Headroom
        if (!hasHeadroom(level, standPos, minHeadroom)) return false;

        // Roof
        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        if (roofDy == -1) return false;
        if (roofDy < minHeadroom) return false;

        // Reachability (counts against budget)
        if (pathBudget != null && pathBudget.exhausted()) return false;
        if (pathBudget != null) pathBudget.consumeOne();

        var path = mob.getNavigation().createPath(standPos, 0);
        return path != null && path.canReach();
    }

    static boolean hasHeadroom(Level level, BlockPos standPos, int minHeadroom) {
        for (int dy = 0; dy < minHeadroom; dy++) {
            if (!level.isEmptyBlock(standPos.above(dy))) return false;
        }
        return true;
    }

    @Nullable
    static BlockPos findGround(Level level, BlockPos start) {
        BlockPos pos = start;

        while (pos.getY() < level.getMaxBuildHeight() && !level.isEmptyBlock(pos)) {
            pos = pos.above();
        }

        while (pos.getY() > level.getMinBuildHeight() && level.isEmptyBlock(pos)) {
            pos = pos.below();
        }

        if (level.isEmptyBlock(pos)) return null;
        return pos;
    }

    static boolean isSleepingMobAlreadyHere(CatoBaseMob mob, Level level, BlockPos standPos) {
        var box = new net.minecraft.world.phys.AABB(
                standPos.getX(), standPos.getY(), standPos.getZ(),
                standPos.getX() + 1.0D, standPos.getY() + 1.5D, standPos.getZ() + 1.0D
        );

        for (var e : level.getEntities(mob, box, ent -> ent instanceof CatoBaseMob)) {
            CatoBaseMob other = (CatoBaseMob) e;
            if (other != mob && other.isSleeping()) return true;
        }
        return false;
    }

    private static boolean isCurrentlySleepTime(CatoBaseMob mob, CatoMobSpeciesInfo info) {
        boolean isDay = mob.level().isDay();
        return (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());
    }

    // Buddy scoring stays here so scoring is centralized
    private static int countSleepingBuddiesNear(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            Level level,
            BlockPos standPos
    ) {
        if (!info.sleepPreferSleepingBuddies()) return 0;
        if (info.sleepBuddyTypes() == null || info.sleepBuddyTypes().isEmpty()) return 0;

        double r = Math.max(0.0D, info.sleepBuddySearchRadius());
        if (r <= 0.0D) return 0;

        int max = Math.max(0, info.sleepBuddyMaxCount());
        if (max <= 0) return 0;

        var box = new net.minecraft.world.phys.AABB(
                standPos.getX() - r, standPos.getY() - 2, standPos.getZ() - r,
                standPos.getX() + 1 + r, standPos.getY() + 2, standPos.getZ() + 1 + r
        );

        int count = 0;

        for (var e : level.getEntities(mob, box, ent -> ent instanceof CatoBaseMob)) {
            CatoBaseMob other = (CatoBaseMob) e;

            if (other == mob) continue;
            if (!other.isSleeping()) continue;
            if (!info.sleepBuddyTypes().contains(other.getType())) continue;

            count++;
            if (count >= max) break;
        }

        return count;
    }
}
