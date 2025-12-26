package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * SleepSpotFinder
 *
 * Performance-focused version for "many mobs":
 * - Avoids repeated entity AABB scans per candidate (combined buddy counting, run late)
 * - Avoids repeated roofDistance scans per candidate (compute once)
 * - Avoids boxing HashSet<Long> (custom primitive long sets)
 * - Two-stage selection: cheap filtering -> expensive finalize (path + buddy scan) only for TOP_K candidates
 * - Uses MutableBlockPos scratch where possible to reduce allocations
 */
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

    // ------------------------------------------------------------
    // Primitive long set (open addressing) to avoid HashSet<Long> boxing.
    // ------------------------------------------------------------
    private static final class LongOpenHashSet {
        private long[] keys;
        private boolean[] used;
        private int size;
        private int mask;
        private int resizeAt;

        LongOpenHashSet(int expectedSize) {
            int cap = 1;
            int need = Math.max(8, expectedSize);
            while (cap < need * 2) cap <<= 1;
            keys = new long[cap];
            used = new boolean[cap];
            mask = cap - 1;
            resizeAt = (int) (cap * 0.7f);
        }

        boolean add(long k) {
            if (size >= resizeAt) rehash(keys.length << 1);

            int i = mix64to32(k) & mask;
            while (used[i]) {
                if (keys[i] == k) return false;
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = k;
            size++;
            return true;
        }

        boolean contains(long k) {
            int i = mix64to32(k) & mask;
            while (used[i]) {
                if (keys[i] == k) return true;
                i = (i + 1) & mask;
            }
            return false;
        }

        private void rehash(int newCap) {
            long[] oldK = keys;
            boolean[] oldU = used;

            keys = new long[newCap];
            used = new boolean[newCap];
            mask = newCap - 1;
            resizeAt = (int) (newCap * 0.7f);
            size = 0;

            for (int i = 0; i < oldK.length; i++) {
                if (!oldU[i]) continue;
                add(oldK[i]);
            }
        }

        // decent mixing for blockpos.asLong keys
        private static int mix64to32(long z) {
            z ^= (z >>> 33);
            z *= 0xff51afd7ed558ccdL;
            z ^= (z >>> 33);
            z *= 0xc4ceb9fe1a85ec53L;
            z ^= (z >>> 33);
            return (int) z;
        }
    }

    // ------------------------------------------------------------
    // Candidate buffer (top K by cheap score)
    // ------------------------------------------------------------
    private static final class Candidate {
        int x, y, z;          // stand pos
        int roofDy;           // cached roof distance
        int baseScore;        // roof/headroom + strike + memory bias (NO buddy effects)
        double distSqr;       // to mob
        boolean memory;       // remembered spot
    }

    private static final class BuddyCounts {
        int sleeping;
        int awake;
    }

    @Nullable
    public static BlockPos findRoofedSleepSpot(CatoBaseMob mob, CatoMobSpeciesInfo info) {
        final Level level = mob.level();

        final int centerX = mob.getBlockX();
        final int centerY = mob.getBlockY();
        final int centerZ = mob.getBlockZ();

        final double mul = Math.max(0.1D, info.sleepSearchRadiusMultiplier());
        final double maxRadius = Math.max(info.wanderMaxRadius(), 4.0D) * mul;
        final double minRadius = Math.max(0.0D, Math.min(info.wanderMinRadius() * mul, maxRadius));

        final BlockPos home = mob.getHomePos();
        final boolean enforceHome = info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && home != null;

        final double homeRadiusSqr = enforceHome
                ? (mob.getHomeRadius() * mob.getHomeRadius())
                : 0.0D;

        final int maxAttempts = Math.max(1, info.sleepSearchMaxAttempts());
        final PathBudget pathBudget = new PathBudget(info.sleepSearchMaxPathAttempts());

        final int minHeadroom = Math.max(1, info.sleepSearchMinHeadroomBlocks());
        final int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        final int buddyBonusPer = Math.max(0, info.sleepBuddyScoreBonusPerBuddy());

        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        // ------------------------------------------------------------
        // PASS -1) "Late sleepers join existing sleepers" (strong + early return)
        // ------------------------------------------------------------
        if (info.sleepPreferSleepingBuddies()
                && info.sleepBuddyTypes() != null
                && !info.sleepBuddyTypes().isEmpty()) {

            BlockPos join = findBestAdjacentToSleepingBuddyFast(
                    mob, info, level,
                    centerX, centerY, centerZ,
                    minHeadroom, roofMax, pathBudget,
                    enforceHome, home, homeRadiusSqr, scratch
            );

            if (join != null && !mob.isSleepSpotBlacklisted(join)) {
                return join;
            }
        }

        final LongOpenHashSet testedPos = new LongOpenHashSet(Math.max(32, maxAttempts * 2));
        final LongOpenHashSet failedPathPos = new LongOpenHashSet(Math.max(32, maxAttempts));

        final int TOP_K = 8;
        final Candidate[] top = new Candidate[TOP_K];
        for (int i = 0; i < TOP_K; i++) top[i] = new Candidate();
        int topCount = 0;

        // ------------------------------------------------------------
        // PASS 0) Remembered spots (cheap validate -> add to top-K)
        // ------------------------------------------------------------
        if (isCurrentlySleepTime(mob, info)) {
            List<CatoBaseMob.SleepSpotMemory> mem = mob.getRememberedSleepSpots();
            if (mem != null && !mem.isEmpty()) {
                for (CatoBaseMob.SleepSpotMemory m : mem) {
                    if (m == null || m.pos == null) continue;

                    BlockPos standPos = m.pos;
                    if (mob.isSleepSpotBlacklisted(standPos)) continue;

                    long key = standPos.asLong();
                    testedPos.add(key);

                    int sx = standPos.getX();
                    int sy = standPos.getY();
                    int sz = standPos.getZ();

                    if (!isWithinHomeIfNeeded(mob, enforceHome, home, homeRadiusSqr, sx, sy, sz)) continue;
                    if (!hasRequiredGroundFast(level, scratch, sx, sy, sz, info)) continue;
                    if (!hasHeadroomFast(level, scratch, sx, sy, sz, minHeadroom)) continue;

                    int roofDy = mob.roofDistance(standPos, roofMax);
                    if (roofDy == -1 || roofDy < minHeadroom) continue;

                    Candidate cand = new Candidate();
                    cand.x = sx; cand.y = sy; cand.z = sz;
                    cand.roofDy = roofDy;

                    int base = Math.max(0, roofDy - minHeadroom);
                    base -= 2; // memory bias
                    base += strikePenalty(mob, info, standPos);

                    cand.baseScore = base;
                    cand.distSqr = distSqrToMob(mob, sx, sy, sz);
                    cand.memory = true;

                    topCount = insertTop(top, topCount, TOP_K, cand);
                }
            }
        }

        // ------------------------------------------------------------
        // PASS 1) Random candidates (Stage A: cheap filter only)
        // ------------------------------------------------------------
        final int baseY = centerY;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

            if (dist < info.sleepSearchMinDistance()) continue;

            int x = centerX + Mth.floor(Math.cos(angle) * dist);
            int z = centerZ + Mth.floor(Math.sin(angle) * dist);
            int startY = baseY + 8;

            if (enforceHome) {
                int dxHome = x - home.getX();
                int dzHome = z - home.getZ();
                if ((double) (dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) continue;
            }

            int groundY = findGroundY(level, scratch, x, startY, z);
            if (groundY == Integer.MIN_VALUE) continue;

            int standY = groundY + 1;

            long key = BlockPos.asLong(x, standY, z);
            if (!testedPos.add(key)) continue;
            if (failedPathPos.contains(key)) continue;

            scratch.set(x, standY, z);
            if (mob.isSleepSpotBlacklisted(scratch)) {
                failedPathPos.add(key);
                continue;
            }

            if (!hasRequiredGroundFast(level, scratch, x, standY, z, info)) continue;
            if (!hasHeadroomFast(level, scratch, x, standY, z, minHeadroom)) continue;

            int roofDy = mob.roofDistance(scratch, roofMax);
            if (roofDy == -1 || roofDy < minHeadroom) continue;

            Candidate cand = new Candidate();
            cand.x = x; cand.y = standY; cand.z = z;
            cand.roofDy = roofDy;

            int baseScore = Math.max(0, roofDy - minHeadroom);
            baseScore += strikePenalty(mob, info, scratch);
            cand.baseScore = baseScore;
            cand.distSqr = distSqrToMob(mob, x, standY, z);
            cand.memory = false;

            topCount = insertTop(top, topCount, TOP_K, cand);
        }

        // ------------------------------------------------------------
        // Stage B finalize: path + buddy scan ONLY for top-K
        // ------------------------------------------------------------
        if (topCount > 0) {
            int bestFinalScore = Integer.MAX_VALUE;
            double bestFinalDist = Double.MAX_VALUE;
            Candidate best = null;

            BuddyCounts counts = new BuddyCounts();

            // ✅ Reused target pos to avoid new BlockPos(...) allocations in finalize
            final BlockPos.MutableBlockPos pathTarget = new BlockPos.MutableBlockPos();

            for (int i = 0; i < topCount; i++) {
                if (pathBudget.exhausted()) break;

                Candidate c = top[i];

                if (isSleepingMobAlreadyHereFast(mob, level, c.x, c.y, c.z)) continue;
                if (!isWithinHomeIfNeeded(mob, enforceHome, home, homeRadiusSqr, c.x, c.y, c.z)) continue;

                scratch.set(c.x, c.y, c.z);
                if (mob.isSleepSpotBlacklisted(scratch)) continue;

                pathBudget.consumeOne();

                pathTarget.set(c.x, c.y, c.z);
                var path = mob.getNavigation().createPath(pathTarget, 0);

                if (path == null || !path.canReach()) {
                    failedPathPos.add(BlockPos.asLong(c.x, c.y, c.z));
                    continue;
                }

                int finalScore = c.baseScore;

                if (info.sleepPreferSleepingBuddies()
                        && info.sleepBuddyTypes() != null
                        && !info.sleepBuddyTypes().isEmpty()
                        && info.sleepBuddySearchRadius() > 0.0D
                        && info.sleepBuddyMaxCount() > 0) {

                    countBuddiesNearCombined(mob, info, level, c.x, c.y, c.z, counts);

                    finalScore -= counts.sleeping * buddyBonusPer;

                    int awakeBonus = Math.max(1, buddyBonusPer / 3);
                    finalScore -= counts.awake * awakeBonus;
                }

                if (finalScore < bestFinalScore || (finalScore == bestFinalScore && c.distSqr < bestFinalDist)) {
                    bestFinalScore = finalScore;
                    bestFinalDist = c.distSqr;
                    best = c;
                }
            }

            if (best != null) {
                return new BlockPos(best.x, best.y, best.z);
            }
        }

        // ------------------------------------------------------------
        // PASS 2) Fallback (NO PATH pre-check)
        // ------------------------------------------------------------
        if (maxAttempts > 0) {
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
                double dist = minRadius + mob.getRandom().nextDouble() * (maxRadius - minRadius);

                if (dist < info.sleepSearchMinDistance()) continue;

                int x = centerX + Mth.floor(Math.cos(angle) * dist);
                int z = centerZ + Mth.floor(Math.sin(angle) * dist);
                int startY = baseY + 8;

                if (enforceHome) {
                    int dxHome = x - home.getX();
                    int dzHome = z - home.getZ();
                    if ((double) (dxHome * dxHome + dzHome * dzHome) > homeRadiusSqr) continue;
                }

                int groundY = findGroundY(level, scratch, x, startY, z);
                if (groundY == Integer.MIN_VALUE) continue;

                int standY = groundY + 1;
                scratch.set(x, standY, z);

                if (mob.isSleepSpotBlacklisted(scratch)) continue;
                if (!hasRequiredGroundFast(level, scratch, x, standY, z, info)) continue;
                if (!hasHeadroomFast(level, scratch, x, standY, z, minHeadroom)) continue;

                int roofDy = mob.roofDistance(scratch, roofMax);
                if (roofDy == -1 || roofDy < minHeadroom) continue;

                return new BlockPos(x, standY, z);
            }
        }

        return null;
    }

    // ================================================================
    // Buddy-join pass (fast)
    // ================================================================

    @Nullable
    private static BlockPos findBestAdjacentToSleepingBuddyFast(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            Level level,
            int centerX, int centerY, int centerZ,
            int minHeadroom,
            int roofMax,
            PathBudget pathBudget,
            boolean enforceHome,
            @Nullable BlockPos home,
            double homeRadiusSqr,
            BlockPos.MutableBlockPos scratch
    )
 {
        double r = Math.max(0.0D, info.sleepBuddySearchRadius());
        if (r <= 0.0D) return null;

        int rr = Math.max(1, info.sleepBuddyRelocateRadiusBlocks());

     var box = new net.minecraft.world.phys.AABB(
             centerX - r, centerY - 4, centerZ - r,
             centerX + 1 + r, centerY + 4, centerZ + 1 + r
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

        int bestX = 0, bestY = 0, bestZ = 0;
        boolean found = false;

        int bestScore = Integer.MAX_VALUE;
        double bestDistSqr = Double.MAX_VALUE;

        final int buddyBonusPer = Math.max(0, info.sleepBuddyScoreBonusPerBuddy());

        // Reused target for pathing (no new BlockPos per candidate)
        final BlockPos.MutableBlockPos pathTarget = new BlockPos.MutableBlockPos();

        for (CatoBaseMob buddy : buddies) {
            if (pathBudget.exhausted()) break;

            // ✅ no BlockPos allocation
            final int buddyX = buddy.getBlockX();
            final int buddyY = buddy.getBlockY();
            final int buddyZ = buddy.getBlockZ();

            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    int x = buddyX + dx;
                    int z = buddyZ + dz;
                    int startY = buddyY + 8;

                    int groundY = findGroundY(level, scratch, x, startY, z);
                    if (groundY == Integer.MIN_VALUE) continue;

                    int standY = groundY + 1;

                    scratch.set(x, standY, z);
                    if (mob.isSleepSpotBlacklisted(scratch)) continue;

                    if (enforceHome && home != null) {
                        double hx = (home.getX() + 0.5D);
                        double hz = (home.getZ() + 0.5D);
                        double ddhx = (x + 0.5D) - hx;
                        double ddhz = (z + 0.5D) - hz;
                        if ((ddhx * ddhx + ddhz * ddhz) > homeRadiusSqr) continue;
                    }

                    if (!hasRequiredGroundFast(level, scratch, x, standY, z, info)) continue;
                    if (!hasHeadroomFast(level, scratch, x, standY, z, minHeadroom)) continue;

                    int roofDy = mob.roofDistance(scratch, roofMax);
                    if (roofDy == -1 || roofDy < minHeadroom) continue;

                    if (isSleepingMobAlreadyHereFast(mob, level, x, standY, z)) continue;

                    if (pathBudget.exhausted()) break;
                    pathBudget.consumeOne();

                    pathTarget.set(x, standY, z);
                    var path = mob.getNavigation().createPath(pathTarget, 0);
                    if (path == null || !path.canReach()) continue;

                    int score = Math.max(0, roofDy - minHeadroom);
                    score += strikePenalty(mob, info, scratch);

                    // ✅ no BlockPos-based dist calc
                    double ddx = (buddyX + 0.5D) - (x + 0.5D);
                    double ddy = (buddyY + 0.5D) - (standY + 0.5D);
                    double ddz = (buddyZ + 0.5D) - (z + 0.5D);
                    double distToBuddy = ddx * ddx + ddy * ddy + ddz * ddz;

                    if (distToBuddy <= (2.5D * 2.5D)) score -= Math.max(4, buddyBonusPer * 2);
                    else if (distToBuddy <= (10.0D * 10.0D)) score -= Math.max(2, buddyBonusPer);

                    double distToMob = mob.distanceToSqr(x + 0.5D, standY, z + 0.5D);

                    if (score < bestScore || (score == bestScore && distToMob < bestDistSqr)) {
                        bestScore = score;
                        bestDistSqr = distToMob;
                        bestX = x;
                        bestY = standY;
                        bestZ = z;
                        found = true;
                    }
                }
            }
        }

        return found ? new BlockPos(bestX, bestY, bestZ) : null;
    }


    // ================================================================
    // Package-private helpers (still available for buddy-relocator)
    // ================================================================

    static boolean isStandPosValidForSleep(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            BlockPos standPos,
            int minHeadroom,
            PathBudget pathBudget
    ) {
        if (standPos == null) return false;
        if (mob.isSleepSpotBlacklisted(standPos)) return false;

        Level level = mob.level();

        if (isSleepingMobAlreadyHereFast(mob, level, standPos.getX(), standPos.getY(), standPos.getZ())) return false;

        boolean enforceHome = info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && mob.getHomePos() != null;

        BlockPos home = mob.getHomePos();
        double homeRadiusSqr = (home != null) ? (mob.getHomeRadius() * mob.getHomeRadius()) : 0.0D;

        if (!isWithinHomeIfNeeded(mob, enforceHome, home, homeRadiusSqr, standPos.getX(), standPos.getY(), standPos.getZ())) return false;

        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        if (!hasRequiredGroundFast(level, scratch, standPos.getX(), standPos.getY(), standPos.getZ(), info)) return false;
        if (!hasHeadroomFast(level, scratch, standPos.getX(), standPos.getY(), standPos.getZ(), minHeadroom)) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        if (roofDy == -1 || roofDy < minHeadroom) return false;

        if (pathBudget != null && pathBudget.exhausted()) return false;
        if (pathBudget != null) pathBudget.consumeOne();

        var path = mob.getNavigation().createPath(standPos, 0);
        return path != null && path.canReach();
    }

    // ✅ NEW: allocation-free validator for callers that already have mutables
    static boolean isStandPosValidForSleepMutable(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            BlockPos.MutableBlockPos standPos,
            int minHeadroom,
            @Nullable PathBudget pathBudget,
            BlockPos.MutableBlockPos scratch
    ) {
        if (standPos == null) return false;
        if (mob.isSleepSpotBlacklisted(standPos)) return false;

        final Level level = mob.level();

        final int x = standPos.getX();
        final int y = standPos.getY();
        final int z = standPos.getZ();

        if (isSleepingMobAlreadyHereFast(mob, level, x, y, z)) return false;

        boolean enforceHome = info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && mob.getHomePos() != null;

        BlockPos home = mob.getHomePos();
        double homeRadiusSqr = (home != null) ? (mob.getHomeRadius() * mob.getHomeRadius()) : 0.0D;

        if (!isWithinHomeIfNeeded(mob, enforceHome, home, homeRadiusSqr, x, y, z)) return false;

        if (!hasRequiredGroundFast(level, scratch, x, y, z, info)) return false;
        if (!hasHeadroomFast(level, scratch, x, y, z, minHeadroom)) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        if (roofDy == -1 || roofDy < minHeadroom) return false;

        if (pathBudget != null && pathBudget.exhausted()) return false;
        if (pathBudget != null) pathBudget.consumeOne();

        var path = mob.getNavigation().createPath(standPos, 0);
        return path != null && path.canReach();
    }

    static boolean isStandPosValidForSleepNoPath(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            BlockPos standPos,
            int minHeadroom
    ) {
        if (standPos == null) return false;
        if (mob.isSleepSpotBlacklisted(standPos)) return false;

        Level level = mob.level();

        if (isSleepingMobAlreadyHereFast(mob, level, standPos.getX(), standPos.getY(), standPos.getZ())) return false;

        boolean enforceHome = info.sleepSearchRespectHomeRadius()
                && mob.shouldStayWithinHomeRadius()
                && mob.getHomePos() != null;

        BlockPos home = mob.getHomePos();
        double homeRadiusSqr = (home != null) ? (mob.getHomeRadius() * mob.getHomeRadius()) : 0.0D;

        if (!isWithinHomeIfNeeded(mob, enforceHome, home, homeRadiusSqr, standPos.getX(), standPos.getY(), standPos.getZ())) return false;

        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        if (!hasRequiredGroundFast(level, scratch, standPos.getX(), standPos.getY(), standPos.getZ(), info)) return false;
        if (!hasHeadroomFast(level, scratch, standPos.getX(), standPos.getY(), standPos.getZ(), minHeadroom)) return false;

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        int roofDy = mob.roofDistance(standPos, roofMax);
        return roofDy != -1 && roofDy >= minHeadroom;
    }

    // ------------------------------------------------------------
    // Top-K insert (NO lambdas; Java-friendly)
    // ------------------------------------------------------------
    private static int insertTop(Candidate[] top, int topCount, int TOP_K, Candidate cand) {
        if (topCount < TOP_K) {
            topCount++;
        } else {
            Candidate worst = top[TOP_K - 1];
            if (cand.baseScore > worst.baseScore) return topCount;
            if (cand.baseScore == worst.baseScore && cand.distSqr >= worst.distSqr) return topCount;
        }

        int pos = 0;
        while (pos < topCount - 1) {
            Candidate cur = top[pos];
            if (cand.baseScore < cur.baseScore) break;
            if (cand.baseScore == cur.baseScore && cand.distSqr < cur.distSqr) break;
            pos++;
        }

        for (int j = topCount - 1; j > pos; j--) {
            Candidate dst = top[j];
            Candidate src = top[j - 1];
            dst.x = src.x; dst.y = src.y; dst.z = src.z;
            dst.roofDy = src.roofDy;
            dst.baseScore = src.baseScore;
            dst.distSqr = src.distSqr;
            dst.memory = src.memory;
        }

        Candidate slot = top[pos];
        slot.x = cand.x; slot.y = cand.y; slot.z = cand.z;
        slot.roofDy = cand.roofDy;
        slot.baseScore = cand.baseScore;
        slot.distSqr = cand.distSqr;
        slot.memory = cand.memory;

        return topCount;
    }

    // ================================================================
    // Fast primitives
    // ================================================================

    private static boolean isWithinHomeIfNeeded(
            CatoBaseMob mob,
            boolean enforceHome,
            @Nullable BlockPos home,
            double homeRadiusSqr,
            int x, int y, int z
    ) {
        if (!enforceHome || home == null) return true;
        double dx = (x + 0.5D) - (home.getX() + 0.5D);
        double dz = (z + 0.5D) - (home.getZ() + 0.5D);
        return (dx * dx + dz * dz) <= homeRadiusSqr;
    }

    private static double distSqrToMob(CatoBaseMob mob, int x, int y, int z) {
        return mob.distanceToSqr(x + 0.5D, y, z + 0.5D);
    }

    /**
     * Allocation-free ground finder (returns Y or MIN_VALUE).
     * startY is clamped. Uses scratch.
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

    private static boolean hasHeadroomFast(Level level, BlockPos.MutableBlockPos scratch, int x, int standY, int z, int minHeadroom) {
        for (int dy = 0; dy < minHeadroom; dy++) {
            scratch.set(x, standY + dy, z);
            if (!level.isEmptyBlock(scratch)) return false;
        }
        return true;
    }

    private static boolean hasRequiredGroundFast(Level level, BlockPos.MutableBlockPos scratch, int x, int standY, int z, CatoMobSpeciesInfo info) {
        if (!info.sleepSearchRequireSolidGround()) return true;

        scratch.set(x, standY - 1, z);
        BlockState belowState = level.getBlockState(scratch);

        if (belowState.is(BlockTags.LEAVES)) return false;

        return belowState.isFaceSturdy(level, scratch, Direction.UP);
    }

    private static boolean isSleepingMobAlreadyHereFast(CatoBaseMob mob, Level level, int x, int y, int z) {
        var box = new net.minecraft.world.phys.AABB(
                x, y, z,
                x + 1.0D, y + 1.5D, z + 1.0D
        );

        var list = level.getEntitiesOfClass(CatoBaseMob.class, box, e -> e != mob && e.isSleeping());
        return !list.isEmpty();
    }

    private static void countBuddiesNearCombined(
            CatoBaseMob mob,
            CatoMobSpeciesInfo info,
            Level level,
            int x, int y, int z,
            BuddyCounts out
    ) {
        out.sleeping = 0;
        out.awake = 0;

        double r = Math.max(0.0D, info.sleepBuddySearchRadius());
        if (r <= 0.0D) return;

        int max = Math.max(0, info.sleepBuddyMaxCount());
        if (max <= 0) return;

        var box = new net.minecraft.world.phys.AABB(
                x - r, y - 2, z - r,
                x + 1 + r, y + 2, z + 1 + r
        );

        for (CatoBaseMob other : level.getEntitiesOfClass(CatoBaseMob.class, box, e -> e != mob && e.isAlive())) {
            if (info.sleepBuddyTypes() == null || !info.sleepBuddyTypes().contains(other.getType())) continue;

            if (other.isSleeping()) out.sleeping++;
            else out.awake++;

            if (out.sleeping + out.awake >= max) break;
        }
    }

    // ================================================================
    // Scoring helpers (cheap / memory-based)
    // ================================================================

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

    private static boolean isCurrentlySleepTime(CatoBaseMob mob, CatoMobSpeciesInfo info) {
        boolean isDay = mob.level().isDay();
        return (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());
    }

    // ================================================================
    // Compatibility helper: old callers (SleepBuddyRelocator, etc.)
    // ================================================================
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
}
