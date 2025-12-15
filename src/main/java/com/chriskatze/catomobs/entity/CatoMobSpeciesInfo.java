package com.chriskatze.catomobs.entity;

/**
 * CatoMobSpeciesInfo
 *
 * Central, data-driven configuration for a mob "species".
 *
 * All AI goals and base mob logic READ from this record.
 * Nothing in here executes logic directly — it only provides tuning data.
 */
public record CatoMobSpeciesInfo(

        // ================================================================
        // 1) IDENTITY & CLASSIFICATION
        // ================================================================
        CatoMobMovementType movementType,
        CatoMobTemperament temperament,
        CatoMobSizeCategory sizeCategory,

        // ================================================================
        // 2) CORE ATTRIBUTES (fed into AttributeSupplier)
        // ================================================================
        double maxHealth,
        double attackDamage,
        double movementSpeed,   // vanilla attribute (NOT wander speed)
        double followRange,
        double gravity,

        // ================================================================
        // 3) COMBAT / ATTACK TIMING (timed-attack system)
        // ================================================================
        double attackTriggerRange,
        double attackHitRange,
        int attackCooldownTicks,
        int attackAnimTotalTicks,
        int attackHitDelayTicks,

        // ================================================================
        // 4) WANDER / MOVEMENT (goal tuning)
        // ================================================================
        double wanderWalkSpeed,
        double wanderRunSpeed,
        float wanderRunChance,
        double wanderMinRadius,
        double wanderMaxRadius,

        // Home radius behavior (used by wander + sleep search bounds)
        boolean stayWithinHomeRadius,
        double homeRadius,

        double wanderRunDistanceThreshold,

        // ================================================================
        // 5) WATER MOVEMENT TUNING (travel() modifiers)
        // ================================================================
        double waterSwimSpeedMultiplier,

        /**
         * Water movement “feel” configuration for travel().
         * Used by WaterMovementComponent.
         *
         * Tip: keep defaults sane for land mobs and tune per species.
         */
        WaterMovementConfig waterMovement,

        // ================================================================
        // 6) SLEEP — ENABLE + TIME WINDOW + ATTEMPT PACING
        // ================================================================
        boolean sleepEnabled,
        boolean sleepAtNight,
        boolean sleepAtDay,

        int sleepAttemptIntervalTicks,
        float sleepAttemptChance,
        float sleepContinueChance,
        int sleepMinTicks,
        int sleepMaxTicks,
        int sleepTimeWindowWakeGraceMinTicks,
        int sleepTimeWindowWakeGraceMaxTicks,
        int sleepDesireWindowTicks,

        // ================================================================
        // 7) SLEEP — SPOT MEMORY (prefer known good spots)
        // ================================================================
        int sleepSpotMemorySize,
        int sleepSpotMemoryMaxStrikes,

        // ================================================================
        // 8) SLEEP — SOCIAL / BUDDY PREFERENCE
        // ================================================================
        boolean sleepPreferSleepingBuddies,
        double sleepBuddySearchRadius,
        int sleepBuddyMaxCount,
        int sleepBuddyRelocateRadiusBlocks,
        int sleepBuddyScoreBonusPerBuddy,
        boolean sleepBuddyCanOverrideMemory,
        java.util.Set<net.minecraft.world.entity.EntityType<?>> sleepBuddyTypes,

        // ================================================================
        // 9) SLEEP — CONSTRAINTS & WAKE CONDITIONS
        // ================================================================
        boolean sleepRequiresRoof,
        boolean sleepAllowedOnWaterSurface,

        boolean wakeOnDamage,
        boolean wakeOnAir,
        boolean wakeOnTouchingWater,
        boolean wakeOnUnderwater,
        boolean wakeOnSunlight,

        // ================================================================
        // 10) SLEEP SEARCH (roof-finding behavior)
        // ================================================================
        int sleepSearchMaxAttempts,
        int sleepSearchMaxPathAttempts,
        int sleepSearchMinHeadroomBlocks,
        int sleepSearchCeilingScanMaxBlocks,
        int sleepSearchTimeoutTicks,
        int sleepSearchCooldownTicks,
        double sleepSearchRadiusMultiplier,
        double sleepSearchMinDistance,
        boolean sleepSearchRespectHomeRadius,
        boolean sleepSearchRequireSolidGround

) {
    /**
     * WaterMovementConfig
     *
     * Pure data used by WaterMovementComponent.
     */
    public record WaterMovementConfig(
            boolean dampingEnabled,
            double verticalDamping,
            double verticalSpeedClamp,
            double dampingApplyThreshold
    ) {
        /** Safe canonical "off" config (prevents accidental math doing anything). */
        public static WaterMovementConfig disabled() {
            return new WaterMovementConfig(false, 1.0D, 0.0D, 0.0D);
        }

        /** Handy default that matches your previous Pikachu tuning. */
        public static WaterMovementConfig defaultLandDamping() {
            return new WaterMovementConfig(true, 0.7D, 0.4D, 0.2D);
        }
    }
}
