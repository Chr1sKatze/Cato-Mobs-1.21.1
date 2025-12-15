package com.chriskatze.catomobs.entity;

/**
 * CatoMobSpeciesInfo
 *
 * Central, data-driven configuration for a mob "species".
 *
 * This record defines:
 * - core stats (health, damage, speed, gravity)
 * - movement behavior (wander, run chance, home radius)
 * - combat timing (attack trigger, hit delay, cooldown)
 * - sleep behavior (when, how long, how often, where)
 * - sleep search behavior (roof finding, cooldowns, radii)
 *
 * All AI goals and base mob logic READ from this record.
 * Nothing in here executes logic directly — it only provides tuning data.
 */
public record CatoMobSpeciesInfo(

        // ================================================================
        // Identity & classification
        // ================================================================

        /** Movement category (LAND, FLYING, UNDERWATER, etc.) */
        CatoMobMovementType movementType,

        /** Behavioral temperament (PASSIVE, NEUTRAL, HOSTILE) */
        CatoMobTemperament temperament,

        /** Size category (used for balance, hitboxes, future systems) */
        CatoMobSizeCategory sizeCategory,

        // ================================================================
        // Core attributes (fed into AttributeSupplier)
        // ================================================================

        /** Maximum health value */
        double maxHealth,

        /** Base attack damage */
        double attackDamage,

        /** Base movement speed (vanilla attribute, NOT wander speed) */
        double movementSpeed,

        /** How far the mob can detect targets */
        double followRange,

        /** Gravity modifier (lower = floaty, higher = heavy) */
        double gravity,

        // ================================================================
        // Combat / attack timing (used by timed-attack system)
        // ================================================================

        /** Distance at which an attack is allowed to START */
        double attackTriggerRange,

        /** Distance at which damage is actually applied */
        double attackHitRange,

        /** Cooldown between attack attempts (ticks) */
        int attackCooldownTicks,

        /** Total length of the attack animation (ticks) */
        int attackAnimTotalTicks,

        /** Delay from attack start until damage is applied (ticks) */
        int attackHitDelayTicks,

        // ================================================================
        // Wander / idle movement behavior
        // ================================================================

        /** Navigation speed used for walking wander */
        double wanderWalkSpeed,

        /** Navigation speed used for running wander */
        double wanderRunSpeed,

        /** Chance (0..1) that a wwander becomes a run */
        float wanderRunChance,

        /** Minimum wander distance from center */
        double wanderMinRadius,

        /** Maximum wander distance from center */
        double wanderMaxRadius,

        /** If true, mob stays near a fixed home position */
        boolean stayWithinHomeRadius,

        /** Radius around home position the mob should stay within */
        double homeRadius,

        /**
         * If > 0:
         * Any wander target at or beyond this distance FORCES running.
         * If <= 0: disabled.
         */
        double wanderRunDistanceThreshold,

        // ================================================================
        // Water movement tuning
        // ================================================================

        /** Multiplier for horizontal swim speed (1.0 = vanilla) */
        double waterSwimSpeedMultiplier,

        // ================================================================
        // Sleep system — high-level behavior
        // ================================================================

        /** Master toggle: can this species sleep at all? */
        boolean sleepEnabled,

        /** Can sleep during night */
        boolean sleepAtNight,

        /** Can sleep during day */
        boolean sleepAtDay,

        /**
         * How often the mob attempts to sleep (ticks).
         * Example: 20*10 = once every 10 seconds.
         */
        int sleepAttemptIntervalTicks,

        /**
         * Chance (0..1) per attempt to start sleeping.
         * Rolled only when conditions are valid.
         */
        float sleepAttemptChance,

        /**
         * Chance (0..1) to EXTEND sleep when the sleep timer ends.
         * Example: 0.35f = 35% chance to nap longer.
         */
        float sleepContinueChance,

        /** Minimum sleep duration (ticks) */
        int sleepMinTicks,

        /** Maximum sleep duration (ticks) */
        int sleepMaxTicks,

        int sleepTimeWindowWakeGraceMinTicks,

        int sleepTimeWindowWakeGraceMaxTicks,

        int sleepSpotMemorySize,

        int sleepSpotMemoryMaxStrikes,

        // --- Sleep buddy preference (social sleeping) ---
        boolean sleepPreferSleepingBuddies,
        double sleepBuddySearchRadius,
        int sleepBuddyMaxCount,
        int sleepBuddyRelocateRadiusBlocks,
        int sleepBuddyScoreBonusPerBuddy,
        boolean sleepBuddyCanOverrideMemory,
        java.util.Set<net.minecraft.world.entity.EntityType<?>> sleepBuddyTypes,

        // ================================================================
        // Sleep constraints & wake conditions
        // ================================================================

        /** If true, mob must NOT see the sky to sleep */
        boolean sleepRequiresRoof,

        /** If true, mob may sleep while floating on water */
        boolean sleepAllowedOnWaterSurface,

        /** Wake immediately when taking damage */
        boolean wakeOnDamage,

        /** Wake if no longer on ground (falling, jumping) */
        boolean wakeOnAir,

        /** Wake when touching water (unless water-sleep is allowed) */
        boolean wakeOnTouchingWater,

        /** Wake when fully underwater */
        boolean wakeOnUnderwater,

        /** Wake during daylight if sky is visible */
        boolean wakeOnSunlight,

        // ================================================================
        // Sleep SEARCH behavior (used when roof is required)
        // ================================================================

        /** How many random candidate spots to test per search */
        int sleepSearchMaxAttempts,

        /** How many of those candidates must also be pathable (optional future use) */
        int sleepSearchMaxPathAttempts,

        /**
         * Minimum vertical headroom (in blocks) required at the sleep spot.
         * Example:
         * - 2 = classic “2-block tall” clearance
         * - 3+ = taller mobs
         */
        int sleepSearchMinHeadroomBlocks,

        /** How far up (in blocks) we scan above standPos to find a ceiling (roof detection). */
        int sleepSearchCeilingScanMaxBlocks,

        /** How long we keep walking/searching before giving up (ticks) */
        int sleepSearchTimeoutTicks,

        /** Cooldown after a failed search before trying again (ticks) */
        int sleepSearchCooldownTicks,

        /**
         * Multiplier for search radius based on wanderMaxRadius.
         * Example: 1.5 = search wider than normal wandering.
         */
        double sleepSearchRadiusMultiplier,

        /**
         * Minimum distance a sleep spot must be away from the mob.
         * Prevents "walk 1 block → sleep" behavior.
         */
        double sleepSearchMinDistance,

        /**
         * If true, sleep search respects home radius
         * even if normal wandering does not.
         */
        boolean sleepSearchRespectHomeRadius,

        /** Require solid ground below + space to stand */
        boolean sleepSearchRequireSolidGround,

        int sleepDesireWindowTicks
) {}
