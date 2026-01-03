package com.chriskatze.catomobs.entity.component;

import com.chriskatze.catomobs.entity.CatoMobMovementType;
import com.chriskatze.catomobs.entity.CatoMobSpeciesInfo;
import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

public final class HoverMovementComponent {

    private HoverMovementComponent() {}

    private static final Map<CatoBaseMob, State> STATE = new WeakHashMap<>();

    private static final class State {
        double smoothedFloorDist = -1.0;

        // NEW: continuous bob phase + smoothed amplitude (NOT smoothed position)
        double bobPhaseRad = 0.0;
        double bobAmpSmoothed = 0.0;

        int stepUpSuppressTicks = 0;
    }

    public static void tick(CatoBaseMob mob, CatoMobSpeciesInfo info) {
        if (mob.level().isClientSide) return;
        if (info.movementType() != CatoMobMovementType.HOVERING) return;

        if (mob.isSleeping()) return;
        if (mob.isPassenger()) return;
        if (mob.isUnderWater() || mob.isInWater()) return;

        final double rawFloorDist = findFloorDistance(mob, info.hoverFloorTraceMax());
        if (rawFloorDist < 0) return;

        final State st = STATE.computeIfAbsent(mob, m -> new State());
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

        final double baseDesired = info.hoverDesiredHeight();

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
        //    - smooth amplitude only
        //    - phase always advances so it never "sticks" at neutral
        // ------------------------------------------------------------
        double bobPos = 0.0;

        final int period = Math.max(1, info.hoverBobPeriodTicks());
        final double blend = clamp01(info.hoverBobBlend());

        if (info.hoverBobbingEnabled() && period > 0 && info.hoverBobAmplitude() != 0.0) {
            // same bob idle + moving
            double ampTarget = info.hoverBobAmplitude() * 1.20 * blend;

            // During suppress: reduce gently (NOT 0.35, that causes "neutral pauses")
            if (suppress) ampTarget *= 0.75;

            // Smooth amplitude (fast enough that it doesn't "flatten" around 0)
            final double ampAlpha = suppress ? 0.05 : 0.10;
            st.bobAmpSmoothed = lerp(st.bobAmpSmoothed, ampTarget, ampAlpha);

            // Advance phase continuously
            st.bobPhaseRad += (Math.PI * 2.0) / (double) period;
            if (st.bobPhaseRad > Math.PI * 2.0) st.bobPhaseRad -= (Math.PI * 2.0);

            // Continuous position
            bobPos = Math.sin(st.bobPhaseRad) * st.bobAmpSmoothed;
        } else {
            // If bob disabled, smoothly return amplitude to 0
            st.bobAmpSmoothed = lerp(st.bobAmpSmoothed, 0.0, 0.10);
            bobPos = 0.0;
        }

        // ------------------------------------------------------------
        // 3) Control: base hover stabilized + bob added on top
        // Deadzone only applies to BASE.
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
        // 5) Damping -> base only (do NOT damp bob or it will "pause")
        // ------------------------------------------------------------
        Vec3 v = mob.getDeltaMovement();

        if (suppress && v.y > 0.08D) {
            v = new Vec3(v.x, 0.08D, v.z);
        }

        final double damp = suppress ? 0.55D : (floorCloser ? 0.35D : 0.22D);
        baseCorrection -= v.y * damp;

        double correction = baseCorrection + bobCorrection;

        // ------------------------------------------------------------
        // 6) Clamp per tick
        // Ensure clamp is enough to follow your bob amplitude/period.
        // ------------------------------------------------------------
        double maxUp = info.hoverMaxUpStepPerTick();
        double maxDown = info.hoverMaxDownStepPerTick();

        if (suppress) {
            maxUp = Math.min(maxUp, 0.012D);
        } else {
            // bob velocity requirement ~ A * 2π / period
            final double need = (Math.abs(st.bobAmpSmoothed) * (Math.PI * 2.0)) / (double) period * 1.25;
            if (maxUp < need) maxUp = need;
            if (maxDown < need) maxDown = need;
        }

        correction = clamp(correction, -maxDown, maxUp);

        mob.setDeltaMovement(v.x, v.y + correction, v.z);
        mob.fallDistance = 0.0F;
    }

    private static double findFloorDistance(CatoBaseMob mob, double maxDown) {
        Level level = mob.level();

        double x = mob.getX();
        double z = mob.getZ();
        double feetY = mob.getBoundingBox().minY;

        double step = 0.04D;
        double max = Math.max(0.0D, maxDown);

        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();

        for (double d = 0.0D; d <= max; d += step) {
            double y = feetY - d - 0.01D;

            bp.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                return d;
            }
        }
        return -1.0D;
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
