package com.chriskatze.catomobs.entity.base;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class WaterExitFinder {
    private WaterExitFinder() {}

    private static final int KEEP = 6;

    public static @Nullable BlockPos findDryStandableNear(CatoBaseMob mob, int radius) {
        final Level level = mob.level();

        final int fx = mob.getBlockX();
        final int fy = mob.getBlockY();
        final int fz = mob.getBlockZ();

        final int r = Math.max(1, radius);

        final BlockPos.MutableBlockPos ground = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos stand  = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = fx + dx;
                int z = fz + dz;

                for (int dy = -2; dy <= 2; dy++) {
                    int y = fy + dy;

                    ground.set(x, y, z);
                    stand.set(x, y + 1, z);

                    if (!level.isEmptyBlock(stand)) continue;
                    if (!level.getFluidState(stand).isEmpty()) continue;
                    if (level.isEmptyBlock(ground)) continue;

                    return new BlockPos(x, y + 1, z);
                }
            }
        }

        return null;
    }

    public static @Nullable BlockPos findNearestDryStandableBounded(CatoBaseMob mob, int radius) {
        final Level level = mob.level();

        final int fx = mob.getBlockX();
        final int fy = mob.getBlockY();
        final int fz = mob.getBlockZ();

        final int r = Math.max(6, radius);

        // Store candidates as packed longs to avoid BlockPos allocations.
        final long[] bestPosLong = new long[KEEP];
        final boolean[] bestHas = new boolean[KEEP];
        final double[] bestD2 = new double[KEEP];
        Arrays.fill(bestD2, Double.MAX_VALUE);

        final BlockPos.MutableBlockPos ground = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos stand  = new BlockPos.MutableBlockPos();

        int maxSamples = 900;
        int samples = 0;

        for (int d = 1; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                if (samples >= maxSamples) break;

                samples += checkColumn(mob, level, fx + dx, fy, fz + d, ground, stand, bestPosLong, bestHas, bestD2);
                if (samples >= maxSamples) break;
                samples += checkColumn(mob, level, fx + dx, fy, fz - d, ground, stand, bestPosLong, bestHas, bestD2);
            }
            if (samples >= maxSamples) break;

            for (int dz = -d + 1; dz <= d - 1; dz++) {
                if (samples >= maxSamples) break;

                samples += checkColumn(mob, level, fx + d, fy, fz + dz, ground, stand, bestPosLong, bestHas, bestD2);
                if (samples >= maxSamples) break;
                samples += checkColumn(mob, level, fx - d, fy, fz + dz, ground, stand, bestPosLong, bestHas, bestD2);
            }
            if (samples >= maxSamples) break;

            if (hasAny(bestHas) && min(bestD2) <= 9.0D) { // within ~3 blocks
                break;
            }
        }

        // Path-check only nearest candidates; reuse a mutable target to avoid allocations.
        final int[] order = sortIdxByD2(bestD2);
        final BlockPos.MutableBlockPos pathTarget = new BlockPos.MutableBlockPos();

        for (int i = 0; i < KEEP; i++) {
            int idx = order[i];
            if (!bestHas[idx]) continue;

            long packed = bestPosLong[idx];
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);

            pathTarget.set(x, y, z);
            var path = mob.getNavigation().createPath(pathTarget, 0);
            if (path != null && path.canReach()) {
                return new BlockPos(x, y, z); // final single allocation
            }
        }

        return null;
    }

    private static int checkColumn(
            CatoBaseMob mob,
            Level level,
            int x, int baseY, int z,
            BlockPos.MutableBlockPos ground,
            BlockPos.MutableBlockPos stand,
            long[] bestPosLong,
            boolean[] bestHas,
            double[] bestD2
    ) {
        int used = 0;

        for (int dy = -2; dy <= 2; dy++) {
            used++;

            int y = baseY + dy;
            ground.set(x, y, z);
            stand.set(x, y + 1, z);

            if (!level.isEmptyBlock(stand)) continue;
            if (!level.getFluidState(stand).isEmpty()) continue;
            if (level.isEmptyBlock(ground)) continue;

            double d2 = mob.distanceToSqr(x + 0.5D, (double)(y + 1), z + 0.5D);

            // find worst slot
            int worstIdx = 0;
            double worst = bestD2[0];
            for (int i = 1; i < KEEP; i++) {
                if (bestD2[i] > worst) {
                    worst = bestD2[i];
                    worstIdx = i;
                }
            }

            if (d2 < worst) {
                bestD2[worstIdx] = d2;
                bestPosLong[worstIdx] = BlockPos.asLong(x, y + 1, z);
                bestHas[worstIdx] = true;
            }
        }

        return used;
    }

    private static boolean hasAny(boolean[] arr) {
        for (boolean b : arr) if (b) return true;
        return false;
    }

    private static double min(double[] arr) {
        double m = Double.MAX_VALUE;
        for (double v : arr) m = Math.min(m, v);
        return m;
    }

    private static int[] sortIdxByD2(double[] d2) {
        int n = d2.length;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        for (int i = 0; i < n; i++) {
            int best = i;
            for (int j = i + 1; j < n; j++) {
                if (d2[idx[j]] < d2[idx[best]]) best = j;
            }
            int tmp = idx[i]; idx[i] = idx[best]; idx[best] = tmp;
        }
        return idx;
    }
}
