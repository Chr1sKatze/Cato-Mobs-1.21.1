package com.chriskatze.catomobs.entity.component;

import com.chriskatze.catomobs.entity.CatoMobMovementType;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public final class HoverMovementComponent {

    private HoverMovementComponent() {}

    private static final Map<CatoBaseMob, State> STATE = new WeakHashMap<>();

    // ============================================================
    // Water recovery tuning (can be moved into species config later)
    // ============================================================
    private static final int WATER_RECOVER_TICKS = 30;      // ~1.5s
    private static final int WATER_POST_RECOVER_TICKS = 12; // ~0.6s (prevents dipping back in)
    private static final double WATER_UP_PUSH = 0.018D;     // gentle upward bias per tick
    private static final double WATER_MAX_UP_VY = 0.045D;   // cap upward speed (floaty)
    private static final double WATER_MAX_DOWN_VY = -0.020D;// don't "drop" while recovering

    private static final class State {
        double smoothedFloorDist = -1.0;

        // continuous bob phase + smoothed amplitude (NOT smoothed position)
        double bobPhaseRad = 0.0;
        double bobAmpSmoothed = 0.0;

        int stepUpSuppressTicks = 0;

        // ============================================================
        // "mood" height variance (absolute offset, NOT additive)
        // ============================================================
        double extraHeightSmoothed = 0.0;
        double extraHeightTarget = 0.0;
        int extraHeightHoldTicks = 0;
        long nextExtraRerollTick = 0L;

        // ============================================================
        // NEW: water recovery state
        // ============================================================
        int waterRecoverTicks = 0;
        int waterPostRecoverTicks = 0;
        boolean wasInWaterLastTick = false;
    }

    public static void tick(CatoBaseMob mob, CatoMobSpeciesInfo info) {
        if (mob.level().isClientSide) return;
        if (info.movementType() != CatoMobMovementType.HOVERING) return;

        if (mob.isSleeping()) return;
        if (mob.isPassenger()) return;

        final State st = STATE.computeIfAbsent(mob, m -> new State());

        // ------------------------------------------------------------
        // Water state / recovery timers
        // ------------------------------------------------------------
        final boolean inWaterNow = mob.isInWater() || mob.isUnderWater();

        if (inWaterNow) {
            st.waterRecoverTicks = Math.max(st.waterRecoverTicks, WATER_RECOVER_TICKS);
            st.waterPostRecoverTicks = WATER_POST_RECOVER_TICKS;
        } else {
            // just left water? keep a short post-recover window
            if (st.wasInWaterLastTick) {
                st.waterPostRecoverTicks = Math.max(st.waterPostRecoverTicks, WATER_POST_RECOVER_TICKS);
            }
        }
        st.wasInWaterLastTick = inWaterNow;

        // ------------------------------------------------------------
        // Floor distance: solid collision OR water surface (hover over water)
        // ------------------------------------------------------------
        double rawFloorDist = findFloorOrWaterSurfaceDistance(mob, info.hoverFloorTraceMax());

        // If neither solid nor water found within trace, treat as "far below" (edge drift descent)
        if (rawFloorDist < 0.0D) {
            rawFloorDist = Math.max(0.0D, info.hoverFloorTraceMax());
        }

        if (st.smoothedFloorDist < 0) st.smoothedFloorDist = rawFloorDist;

        // ------------------------------------------------------------
        // Step-up detection + suppression window
        // ------------------------------------------------------------
        final double prevSmooth = st.smoothedFloorDist;
        final boolean floorCloser = rawFloorDist < prevSmooth;

        if (floorCloser && (prevSmooth - rawFloorDist) > 0.25D) {
            st.stepUpSuppressTicks = 10;
        } else if (st.stepUpSuppressTicks > 0) {
            st.stepUpSuppressTicks--;
        }
        final boolean suppress = st.stepUpSuppressTicks > 0;

        // ------------------------------------------------------------
        // Mood height variance
        // ------------------------------------------------------------
        tickExtraHeightVariance(mob, info, st);

        // Base desired hover height + mood offset
        final double baseDesired = info.hoverDesiredHeight() + st.extraHeightSmoothed;

        // ------------------------------------------------------------
        // 1) Smooth floor distance (asymmetric)
        // ------------------------------------------------------------
        final double alphaUp   = suppress ? 0.10 : 0.18;
        final double alphaDown = 0.06;

        final double alpha = floorCloser ? alphaUp : alphaDown;
        st.smoothedFloorDist = lerp(st.smoothedFloorDist, rawFloorDist, alpha);

        // Keep suppression alive until close to base desired (no bob)
        if (st.stepUpSuppressTicks > 0) {
            final double stableBand = 0.10D;
            if (Math.abs(baseDesired - st.smoothedFloorDist) < stableBand) {
                st.stepUpSuppressTicks = Math.min(st.stepUpSuppressTicks, 2);
            } else {
                st.stepUpSuppressTicks = Math.max(st.stepUpSuppressTicks, 6);
            }
        }

        // ------------------------------------------------------------
        // 2) Continuous bob (NO position low-pass)
        // ------------------------------------------------------------
        double bobPos = 0.0;

        final int period = Math.max(1, info.hoverBobPeriodTicks());
        final double blend = clamp01(info.hoverBobBlend());

        if (info.hoverBobbingEnabled() && period > 0 && info.hoverBobAmplitude() != 0.0) {
            double ampTarget = info.hoverBobAmplitude() * 1.20 * blend;
            if (suppress) ampTarget *= 0.75;

            final double ampAlpha = suppress ? 0.05 : 0.10;
            st.bobAmpSmoothed = lerp(st.bobAmpSmoothed, ampTarget, ampAlpha);

            st.bobPhaseRad += (Math.PI * 2.0) / (double) period;
            if (st.bobPhaseRad > Math.PI * 2.0) st.bobPhaseRad -= (Math.PI * 2.0);

            bobPos = Math.sin(st.bobPhaseRad) * st.bobAmpSmoothed;
        } else {
            st.bobAmpSmoothed = lerp(st.bobAmpSmoothed, 0.0, 0.10);
            bobPos = 0.0;
        }

        // ------------------------------------------------------------
        // 3) Control: base hover stabilized + bob added on top
        // ------------------------------------------------------------
        final double baseError = baseDesired - st.smoothedFloorDist;

        final double deadzone = 0.05;
        double eBase = baseError;
        if (Math.abs(eBase) < deadzone) eBase = 0.0;

        final double kBase = (suppress ? 0.045D : 0.065D);
        final double kBob  = kBase * 0.85;

        double baseCorrection = eBase * kBase;
        double bobCorrection  = bobPos * kBob;

        // ------------------------------------------------------------
        // 4) Minimum clearance lift (RAW floor dist) -> base only
        // ------------------------------------------------------------
        final double minClearance = Math.max(0.70D, baseDesired * 0.30D);

        if (rawFloorDist < minClearance) {
            final double deficit = (minClearance - rawFloorDist);

            final Vec3 dv = mob.getDeltaMovement();
            final boolean movingHorizontally = dv.x * dv.x + dv.z * dv.z > 0.002;
            final boolean movingIntent = movingHorizontally
                    || mob.getNavigation().isInProgress()
                    || mob.getTarget() != null;

            final double k = suppress ? 0.14D : 0.22D;

            final double liftCap = suppress
                    ? 0.010D
                    : (movingIntent ? 0.035D : 0.020D);

            baseCorrection += clamp(deficit * k, 0.0D, liftCap);
        }

        // ------------------------------------------------------------
        // 5) Damping -> base only
        // ------------------------------------------------------------
        Vec3 v = mob.getDeltaMovement();

        if (suppress && v.y > 0.08D) {
            v = new Vec3(v.x, 0.08D, v.z);
        }

        final double damp = suppress ? 0.55D : (floorCloser ? 0.35D : 0.22D);
        baseCorrection -= v.y * damp;

        // ------------------------------------------------------------
        // 5.5) Water recovery: gentle upward bias + vertical speed caps
        // ------------------------------------------------------------
        if (st.waterRecoverTicks > 0 || st.waterPostRecoverTicks > 0) {
            // Bias upward slightly (feels buoyant / "Magnemite-y")
            baseCorrection += WATER_UP_PUSH;

            // Clamp vertical velocity softly so it doesn't "fight" and jitter
            double vy = v.y;
            if (vy < WATER_MAX_DOWN_VY) vy = lerp(vy, WATER_MAX_DOWN_VY, 0.35D);
            if (vy > WATER_MAX_UP_VY)   vy = lerp(vy, WATER_MAX_UP_VY,   0.35D);
            v = new Vec3(v.x, vy, v.z);

            mob.fallDistance = 0.0F;

            if (st.waterRecoverTicks > 0) st.waterRecoverTicks--;
            if (!inWaterNow && st.waterPostRecoverTicks > 0) st.waterPostRecoverTicks--;
            if (inWaterNow) st.waterPostRecoverTicks = WATER_POST_RECOVER_TICKS; // keep while still in water
        }

        double correction = baseCorrection + bobCorrection;

        // ------------------------------------------------------------
        // 6) Clamp per tick
        // ------------------------------------------------------------
        double maxUp = info.hoverMaxUpStepPerTick();
        double maxDown = info.hoverMaxDownStepPerTick();

        if (suppress) {
            maxUp = Math.min(maxUp, 0.012D);
        } else {
            final double need = (Math.abs(st.bobAmpSmoothed) * (Math.PI * 2.0)) / (double) period * 1.25;
            if (maxUp < need) maxUp = need;
            if (maxDown < need) maxDown = need;
        }

        correction = clamp(correction, -maxDown, maxUp);

        // Prevent "rocket" by capping resulting vertical velocity (floaty cap already applied above if recovering)
        double newVy = v.y + correction;
        newVy = clamp(newVy, -maxDown, maxUp);

        mob.setDeltaMovement(v.x, newVy, v.z);
        mob.fallDistance = 0.0F;
    }

    /**
     * Distance to the nearest "support" below:
     * - solid collision shape, OR
     * - water surface (so hovering over water works).
     *
     * Returns:
     *  >= 0 : distance from feet to surface (0 means "at/under surface")
     *  -1   : nothing found within maxDown
     */
    private static double findFloorOrWaterSurfaceDistance(CatoBaseMob mob, double maxDown) {
        Level level = mob.level();

        double x = mob.getX();
        double z = mob.getZ();
        double feetY = mob.getBoundingBox().minY;

        double step = 0.04D;
        double max = Math.max(0.0D, maxDown);

        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();

        for (double d = 0.0D; d <= max; d += step) {
            double y = feetY - d - 0.01D;

            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            bp.set(bx, by, bz);

            // 1) Solid collision => real floor
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                return d;
            }

            // 2) Fluid surface => treat as floor for hovering
            // If there's fluid in this block, its surface is at (by + 1).
            var fluid = level.getFluidState(bp);
            if (!fluid.isEmpty()) {
                double surfaceY = by + 1.0D;
                double dist = feetY - surfaceY;

                // If feet are below the surface, treat distance as 0 (we want to go up).
                if (dist < 0.0D) dist = 0.0D;

                // Only accept if within our trace range
                if (dist <= max) {
                    return dist;
                }
            }
        }

        return -1.0D;
    }

    /**
     * Handles the long-lived random offset above/below normal hover height.
     * Absolute offset with hard clamps => cannot drift upwards over time.
     */
    private static void tickExtraHeightVariance(CatoBaseMob mob, CatoMobSpeciesInfo info, State st) {
        if (!info.hoverHeightVarianceEnabled()) {
            st.extraHeightTarget = 0.0;
            st.extraHeightSmoothed = lerp(st.extraHeightSmoothed, 0.0, 0.10);
            st.extraHeightHoldTicks = 0;
            st.nextExtraRerollTick = -1L;
            return;
        }

        final double upMax = Math.max(0.0D, info.hoverExtraHeightUpMax());
        final double dnMax = Math.max(0.0D, info.hoverExtraHeightDownMax());

        st.extraHeightTarget = Mth.clamp(st.extraHeightTarget, -dnMax, upMax);
        st.extraHeightSmoothed = Mth.clamp(st.extraHeightSmoothed, -dnMax, upMax);

        if (upMax == 0.0D && dnMax == 0.0D) {
            st.extraHeightTarget = 0.0D;
            st.extraHeightSmoothed = lerp(st.extraHeightSmoothed, 0.0, 0.10);
            return;
        }

        final long now = mob.level().getGameTime();

        if (st.extraHeightHoldTicks > 0) st.extraHeightHoldTicks--;

        final int interval = Math.max(1, info.hoverExtraHeightRerollIntervalTicks());
        if (st.nextExtraRerollTick < 0L) {
            st.nextExtraRerollTick = now + interval + (mob.getId() & 7);
        }
        final boolean intervalTick = (now >= st.nextExtraRerollTick);

        if ((st.extraHeightHoldTicks <= 0 || intervalTick)) {
            st.nextExtraRerollTick = now + interval + (mob.getId() & 7);

            int minHold = Math.max(1, info.hoverExtraHeightHoldMinTicks());
            int maxHold = Math.max(minHold, info.hoverExtraHeightHoldMaxTicks());
            st.extraHeightHoldTicks = minHold + mob.getRandom().nextInt(maxHold - minHold + 1);

            float chance = (float) clamp01(info.hoverExtraHeightChancePerReroll());

            if (chance > 0.0f && mob.getRandom().nextFloat() < chance) {
                boolean goUp = mob.getRandom().nextBoolean();

                if (goUp && upMax > 0.0D) {
                    st.extraHeightTarget = mob.getRandom().nextDouble() * upMax;
                } else if (dnMax > 0.0D) {
                    st.extraHeightTarget = -mob.getRandom().nextDouble() * dnMax;
                } else {
                    st.extraHeightTarget = 0.0D;
                }
            } else {
                st.extraHeightTarget = 0.0D;
            }

            st.extraHeightTarget = Mth.clamp(st.extraHeightTarget, -dnMax, upMax);
        }

        final double alpha = (st.stepUpSuppressTicks > 0) ? 0.035D : 0.060D;
        st.extraHeightSmoothed = lerp(st.extraHeightSmoothed, st.extraHeightTarget, alpha);
        st.extraHeightSmoothed = Mth.clamp(st.extraHeightSmoothed, -dnMax, upMax);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        return clamp(v, 0.0D, 1.0D);
    }
}
