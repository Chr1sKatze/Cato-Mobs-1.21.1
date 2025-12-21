package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
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

        // Search around where the mob actually is right now.
        BlockPos center = mob.blockPosition();

        double mul = Math.max(0.1D, info.sleepSearchRadiusMultiplier());
        double maxRadius = Math.max(info.wanderMaxRadius(), 4.0D) * mul;
        double minRadius = Math.max(0.0D, Math.min(info.wanderMinRadius() * mul, maxRadius));

        // Home radius enforcement is based on the real home center (if any)
        BlockPos home = mob.getHomePos();
        boolean enforceHome = info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && home != null;

        double homeRadiusSqr = enforceHome
                ? mob.getHomeRadius() * mob.getHomeRadius()
                : 0.0D;

        int maxAttempts = Math.max(1, info.sleepSearchMaxAttempts());
        PathBudget pathBudget = new PathBudget(info.sleepSearchMaxPathAttempts());

        final int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());

        // ============================================================
        // BEST-CANDIDATE TRACKING (single scoring system for all passes)
        // ============================================================
        BlockPos bestPos = null;
        int bestScore = Integer.MAX_VALUE;
        double bestDistSqr = Double.MAX_VALUE;

        // Track tested pos so later passes don't redo earlier candidates
        Set<Long> testedPos = new HashSet<>();
        Set<Long> failedPathPos = new HashSet<>();

        // ============================================================
        // PASS -1) "LATE SLEEPERS JOIN EXISTING SLEEPERS" (STRONG)
        // ============================================================
        if (info.sleepPreferSleepingBuddies()
                && info.sleepBuddyTypes() != null
                && !info.sleepBuddyTypes().isEmpty()) {

            BlockPos join = findBestAdjacentToSleepingBuddy(
                    mob, info, level, center, minHeadroom, pathBudget, enforceHome, home, homeRadiusSqr
            );

            if (join != null) {
                // ✅ NEW: blacklist guard
                if (!mob.isSleepSpotBlacklisted(join)) {
                    long k = join.asLong();
                    testedPos.add(k);

                    int s = scoreSpot(mob, info, level, join, minHeadroom);
                    double d = distSqrToMob(mob, join);

                    bestPos = join;
                    bestScore = s;
                    bestDistSqr = d;
                }
            }
        }

        // ============================================================
        // PASS 0) Remembered spots (STRICT, includes path check)
        // ============================================================
        if (isCurrentlySleepTime(mob, info)) {
            for (CatoBaseMob.SleepSpotMemory mem : mob.getRememberedSleepSpots()) {
                BlockPos standPos = mem.pos;
                if (standPos == null) continue;

                // ✅ NEW: blacklist guard
                if (mob.isSleepSpotBlacklisted(standPos)) {
                    continue;
                }

                if (pathBudget.exhausted()) break;

                long key = standPos.asLong();
                testedPos.add(key);

                if (!isStandPosValidForSleep(mob, info, standPos, minHeadroom, pathBudget)) {
                    mob.strikeSleepSpot(standPos);
                    failedPathPos.add(key);
                    continue;
                }

                int score = scoreSpot(mob, info, level, standPos, minHeadroom);

                // Memory bias: slightly attractive, but not enough to beat buddies.
                score -= 2;

                // Strike penalty: remembered-but-problematic spots should stop winning
                score += strikePenalty(mob, info, standPos);

                double distSqrToMob = distSqrToMob(mob, standPos);

                if (score < bestScore || (score == bestScore && distSqrToMob < bestDistSqr)) {
                    bestScore = score;
                    bestDistSqr = distSqrToMob;
                    bestPos = standPos;
                }
            }
        }

        // ============================================================
        // PASS 1) Random candidate search (STRICT scoring, includes path check)
        // ============================================================
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (pathBudget.exhausted()) break;

            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

            if (dist < info.sleepSearchMinDistance()) continue;

            int x = center.getX() + Mth.floor(Math.cos(angle) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(angle) * dist);
            int y = mob.blockPosition().getY() + 8;

            // Home radius respect (optional) — compare to HOME, not to center
            if (enforceHome) {
                int dxHome = x - home.getX();
                int dzHome = z - home.getZ();
                if ((double) (dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) continue;
            }

            BlockPos probe = new BlockPos(x, y, z);

            BlockPos ground = findGround(level, probe);
            if (ground == null) continue;

            BlockPos standPos = ground.above();
            long key = standPos.asLong();

            if (!testedPos.add(key)) continue;
            if (failedPathPos.contains(key)) continue;

            // ✅ NEW: blacklist guard (early, before pathing)
            if (mob.isSleepSpotBlacklisted(standPos)) {
                failedPathPos.add(key);
                continue;
            }

            boolean ok = isStandPosValidForSleep(mob, info, standPos, minHeadroom, pathBudget);
            if (!ok) {
                failedPathPos.add(key);
                continue;
            }

            int score = scoreSpot(mob, info, level, standPos, minHeadroom);

            // Strike penalty (only affects remembered matches; still helpful)
            score += strikePenalty(mob, info, standPos);

            double distSqrToMob = distSqrToMob(mob, standPos);

            if (score < bestScore || (score == bestScore && distSqrToMob < bestDistSqr)) {
                bestScore = score;
                bestDistSqr = distSqrToMob;
                bestPos = standPos;
            }
        }

        // ============================================================
        // PASS 2) FALLBACK PASS (NO PATH PRE-CHECK)
        // Only used if STRICT found nothing at all.
        // ============================================================
        if (bestPos == null && maxAttempts > 0) {
            int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
                double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

                if (dist < info.sleepSearchMinDistance()) continue;

                int x = center.getX() + Mth.floor(Math.cos(angle) * dist);
                int z = center.getZ() + Mth.floor(Math.sin(angle) * dist);
                int y = mob.blockPosition().getY() + 8;

                if (enforceHome) {
                    int dxHome = x - home.getX();
                    int dzHome = z - home.getZ();
                    if ((double) (dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) continue;
                }

                BlockPos probe = new BlockPos(x, y, z);

                BlockPos ground = findGround(level, probe);
                if (ground == null) continue;

                BlockPos standPos = ground.above();

                // ✅ NEW: blacklist guard (fallback too)
                if (mob.isSleepSpotBlacklisted(standPos)) continue;

                if (!isStandPosValidForSleepNoPath(mob, info, standPos, minHeadroom)) continue;

                // Must be roofed
                if (mob.roofDistance(standPos, roofMax) == -1) continue;

                return standPos;
            }
        }

        return bestPos;
    }

    // ================================================================
    // Scoring helpers
    // ================================================================

    private static int scoreSpot(CatoBaseMob mob, CatoMobSpeciesInfo info, Level level, BlockPos standPos, int minHeadroom) {
        // Base score: prefer low roof distance (cozy ceiling)
        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        int score = Math.max(0, roofDy - minHeadroom);

        // Social sleeping bonus (sleeping buddies strong)
        int sleepingBuddies = countSleepingBuddiesNear(mob, info, level, standPos);
        score -= sleepingBuddies * Math.max(0, info.sleepBuddyScoreBonusPerBuddy());

        // Awake buddies weaker (helps converge before anyone sleeps)
        int awakeBuddies = countAwakeBuddiesNear(mob, info, level, standPos);
        int awakeBonus = Math.max(1, info.sleepBuddyScoreBonusPerBuddy() / 3);
        score -= awakeBuddies * awakeBonus;

        return score;
    }

    private static double distSqrToMob(CatoBaseMob mob, BlockPos standPos) {
        return mob.distanceToSqr(
                standPos.getX() + 0.5D,
                standPos.getY(),
                standPos.getZ() + 0.5D
        );
    }

    /**
     * Penalize spots that exist in memory with strikes.
     * This is the ONLY strike-data you currently have (until blacklist is added).
     */
    private static int strikePenalty(CatoBaseMob mob, CatoMobSpeciesInfo info, BlockPos pos) {
        int strikes = getMemoryStrikesFor(mob.getRememberedSleepSpots(), pos);
        if (strikes <= 0) return 0;

        int unit = Math.max(6, info.sleepBuddyScoreBonusPerBuddy() * 2);
        return strikes * unit;
    }

    private static int getMemoryStrikesFor(List<CatoBaseMob.SleepSpotMemory> mem, BlockPos pos) {
        if (mem == null || mem.isEmpty() || pos == null) return 0;
        for (CatoBaseMob.SleepSpotMemory m : mem) {
            if (m != null && m.pos != null && m.pos.equals(pos)) {
                return Math.max(0, m.strikes);
            }
        }
        return 0;
    }

    // ================================================================
    // Buddy-join pass
    // ================================================================

    @Nullable
    private static BlockPos findBestAdjacentToSleepingBuddy(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            Level level,
            BlockPos center,
            int minHeadroom,
            PathBudget pathBudget,
            boolean enforceHome,
            @Nullable BlockPos home,
            double homeRadiusSqr
    ) {
        double r = Math.max(0.0D, info.sleepBuddySearchRadius());
        if (r <= 0.0D) return null;

        int rr = Math.max(1, info.sleepBuddyRelocateRadiusBlocks());

        // Find sleeping buddies near the mob
        var box = new net.minecraft.world.phys.AABB(
                center.getX() - r, center.getY() - 4, center.getZ() - r,
                center.getX() + 1 + r, center.getY() + 4, center.getZ() + 1 + r
        );

        var buddies = level.getEntitiesOfClass(
                CatoBaseMob.class,
                box,
                e -> e != mob
                        && e.isAlive()
                        && e.isSleeping()
                        && info.sleepBuddyTypes().contains(e.getType())
        );

        if (buddies.isEmpty()) return null;

        BlockPos bestPos = null;
        int bestScore = Integer.MAX_VALUE;
        double bestDistSqr = Double.MAX_VALUE;

        for (CatoBaseMob buddy : buddies) {
            if (pathBudget.exhausted()) break;

            BlockPos buddyBase = buddy.blockPosition();

            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockPos candidate = buddyBase.offset(dx, 0, dz);

                    // Snap to ground
                    BlockPos ground = findGround(level, candidate.above(8));
                    if (ground == null) continue;

                    BlockPos standPos = ground.above();

                    // ✅ NEW: blacklist guard (join pass too)
                    if (mob.isSleepSpotBlacklisted(standPos)) continue;

                    // Home radius check
                    if (enforceHome && home != null) {
                        double ddx = (standPos.getX() + 0.5D) - (home.getX() + 0.5D);
                        double ddz = (standPos.getZ() + 0.5D) - (home.getZ() + 0.5D);
                        if ((ddx * ddx + ddz * ddz) > homeRadiusSqr) continue;
                    }

                    if (!isStandPosValidForSleep(mob, info, standPos, minHeadroom, pathBudget)) continue;

                    int score = scoreSpot(mob, info, level, standPos, minHeadroom);

                    // EXTRA strong "join sleeper" preference:
                    double distToBuddy = standPos.distSqr(buddyBase);
                    if (distToBuddy <= 2.5D) score -= Math.max(4, info.sleepBuddyScoreBonusPerBuddy() * 2);
                    else if (distToBuddy <= 10.0D) score -= Math.max(2, info.sleepBuddyScoreBonusPerBuddy());

                    score += strikePenalty(mob, info, standPos);

                    double distToMob = distSqrToMob(mob, standPos);

                    if (score < bestScore || (score == bestScore && distToMob < bestDistSqr)) {
                        bestScore = score;
                        bestDistSqr = distToMob;
                        bestPos = standPos;
                    }
                }
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

        // ✅ NEW: global blacklist check (covers all callers)
        if (mob.isSleepSpotBlacklisted(standPos)) return false;

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

        // Ground rule
        if (!hasRequiredGround(level, standPos, info)) return false;

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

    // Same validation as above, but WITHOUT createPath()/budget use
    static boolean isStandPosValidForSleepNoPath(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            BlockPos standPos,
            int minHeadroom
    ) {
        if (standPos == null) return false;

        // ✅ NEW: global blacklist check (covers all callers)
        if (mob.isSleepSpotBlacklisted(standPos)) return false;

        Level level = mob.level();

        if (isSleepingMobAlreadyHere(mob, level, standPos)) return false;

        if (info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && mob.getHomePos() != null) {

            BlockPos home = mob.getHomePos();
            double r = mob.getHomeRadius();
            double dx = (standPos.getX() + 0.5D) - (home.getX() + 0.5D);
            double dz = (standPos.getZ() + 0.5D) - (home.getZ() + 0.5D);
            if ((dx * dx + dz * dz) > (r * r)) return false;
        }

        if (!hasRequiredGround(level, standPos, info)) return false;
        if (!hasHeadroom(level, standPos, minHeadroom)) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        if (roofDy == -1) return false;
        if (roofDy < minHeadroom) return false;

        return true;
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

    static boolean hasRequiredGround(Level level, BlockPos standPos, CatoMobSpeciesInfo info) {
        if (!info.sleepSearchRequireSolidGround()) return true;

        BlockPos below = standPos.below();
        BlockState belowState = level.getBlockState(below);

        // Don't sleep on leaves as "ground"
        if (belowState.is(BlockTags.LEAVES)) return false;

        // Must be sturdy enough to stand on
        return belowState.isFaceSturdy(level, below, Direction.UP);
    }

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

    private static int countAwakeBuddiesNear(
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
            if (!other.isAlive()) continue;
            if (!info.sleepBuddyTypes().contains(other.getType())) continue;
            if (other.isSleeping()) continue;

            count++;
            if (count >= max) break;
        }

        return count;
    }
}
