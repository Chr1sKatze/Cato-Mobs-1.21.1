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

        /**
         * Navigation speed modifier used while chasing a target (melee goal).
         * This is the "speed" value passed into Navigation#moveTo(..., speed).
         *
         * Actual in-game movement will still be influenced by the vanilla movementSpeed attribute,
         * but this controls how aggressively the mob chases.
         */
        double chaseSpeedModifier,

        /**
         * If false: normally root during attack animation (no chasing movement while isAttacking() is true).
         * If true: allow navigation to keep updating while the attack animation plays,
         *          subject to the start-delay / stop-after tick windows below.
         */
        boolean moveDuringAttackAnimation,

        /**
         * If moveDuringAttackAnimation == true:
         *   - root until attackAnimAgeTicks >= this value (delay before movement starts)
         * If moveDuringAttackAnimation == false:
         *   - (optional) still used only if you choose to implement special behavior, otherwise ignored
         */
        int attackMoveStartDelayTicks,

        /**
         * Movement window limiter during attack animation.
         *
         * If > 0:
         *   - when moveDuringAttackAnimation == true:
         *       movement is allowed only while attackAnimAgeTicks is in [attackMoveStartDelayTicks .. attackMoveStopAfterTicks-1]
         *   - when moveDuringAttackAnimation == false:
         *       movement is allowed only while attackAnimAgeTicks < attackMoveStopAfterTicks (early-move window), then rooted.
         *
         * If <= 0: "no stop limit" (move until animation ends, if enabled and after delay).
         */
        int attackMoveStopAfterTicks,

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
        public static WaterMovementConfig disabled() {
            return new WaterMovementConfig(false, 1.0D, 0.0D, 0.0D);
        }

        public static WaterMovementConfig defaultLandDamping() {
            return new WaterMovementConfig(true, 0.7D, 0.4D, 0.2D);
        }
    }
}
