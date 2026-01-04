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
        // 1.5) RENDER (cosmetic)
        // ================================================================
        float shadowRadius,

        // ================================================================
        // 2) CORE ATTRIBUTES (fed into AttributeSupplier)
        // ================================================================
        double maxHealth,
        double movementSpeed,
        double flyingSpeed,
        double followRange,
        double gravity,

        // ================================================================
        // 2.5) RETALIATION / ANGER (neutral mobs)
        // ================================================================
        boolean retaliateWhenAngered,
        int retaliationDurationTicks,

        // ================================================================
        // 2.6) FLEE / PANIC (overrides retaliation while active)
        // ================================================================
        boolean fleeEnabled,
        boolean fleeOnLowHealth,
        float fleeLowHealthThreshold,
        boolean fleeOnHurt,
        int fleeDurationTicks,
        int fleeCooldownTicks,
        double fleeSpeedModifier,
        double fleeDesiredDistance,

        // ================================================================
        // 2.7) GROUP FLEE
        // ================================================================
        boolean groupFleeEnabled,
        double groupFleeRadius,
        int groupFleeMaxAllies,
        boolean groupFleeBypassCooldown,

        // ================================================================
        // 2.8) GROUP FLEE — ALLY DEFINITION
        // ================================================================
        boolean groupFleeAnyCatoMobAllies,
        java.util.Set<net.minecraft.world.entity.EntityType<?>> groupFleeAllyTypes,

        // ================================================================
        // 3) COMBAT / ATTACK TIMING (timed-attack system)
        // ================================================================
        double attackDamage,
        double attackTriggerRange,
        double attackHitRange,
        int attackCooldownTicks,
        int attackAnimTotalTicks,
        int attackHitDelayTicks,

        double chaseSpeedModifier,
        boolean moveDuringAttackAnimation,
        int attackMoveStartDelayTicks,
        int attackMoveStopAfterTicks,

        // ================================================================
        // 3.1) SPECIAL MELEE (timed-attack system)
        // ================================================================
        boolean meleeSpecialEnabled,
        double meleeSpecialTriggerRange,
        double meleeSpecialHitRange,
        int meleeSpecialCooldownTicks,
        int meleeSpecialAnimTotalTicks,
        int meleeSpecialHitDelayTicks,
        double meleeSpecialDamage,
        boolean meleeSpecialMoveDuringAttackAnimation,
        int meleeSpecialMoveStartDelayTicks,
        int meleeSpecialMoveStopAfterTicks,
        float meleeSpecialUseChance,
        int meleeSpecialAfterNormalHits,
        boolean meleeSpecialChancePersistence,

        // ================================================================
        // 3.2) RANGED (timed-attack system)
        // ================================================================
        boolean rangedEnabled,
        double rangedTriggerRange,
        int rangedCooldownTicks,
        int rangedAnimTotalTicks,
        int rangedFireDelayTicks,
        double rangedDamage,
        boolean rangedMoveDuringAttackAnimation,
        int rangedAttackMoveStartDelayTicks,
        int rangedAttackMoveStopAfterTicks,
        float rangedProjectileVelocity,
        float rangedProjectileInaccuracy,
        RangedDelivery rangedDelivery,


        boolean rangedSpecialEnabled,
        double rangedSpecialTriggerRange,
        int rangedSpecialCooldownTicks,
        int rangedSpecialAnimTotalTicks,
        int rangedSpecialFireDelayTicks,
        double rangedSpecialDamage,
        boolean rangedSpecialMoveDuringAttackAnimation,
        int rangedSpecialAttackMoveStartDelayTicks,
        int rangedSpecialAttackMoveStopAfterTicks,
        float rangedSpecialProjectileVelocity,
        float rangedSpecialProjectileInaccuracy,
        RangedDelivery rangedSpecialDelivery,
        float rangedSpecialUseChance,
        int rangedSpecialAfterNormalHits,
        boolean rangedSpecialChancePersistence,

        // ================================================================
        // 4) WANDER / MOVEMENT (goal tuning)
        // ================================================================
        double wanderWalkSpeed,
        double wanderRunSpeed,
        float wanderRunChance,
        double wanderMinRadius,
        double wanderMaxRadius,

        int wanderAttemptIntervalTicks,
        float wanderAttemptChance,

        boolean stayWithinHomeRadius,
        double homeRadius,

        double wanderRunDistanceThreshold,

        // ================================================================
        // 4.5) HOVER (Magnemite-like)
        // ================================================================
        double hoverDesiredHeight,
        double hoverMaxUpStepPerTick,
        double hoverMaxDownStepPerTick,
        double hoverFloorTraceMax,
        boolean hoverBobbingEnabled,
        double hoverBobAmplitude,
        int hoverBobPeriodTicks,
        double hoverBobBlend,

        // ================================================================
        // 4.6) HOVER (variance)
        // ================================================================
        // Hover variance (mood)
        boolean hoverHeightVarianceEnabled,
        double hoverExtraHeightUpMax,
        double hoverExtraHeightDownMax,
        int hoverExtraHeightHoldMinTicks,
        int hoverExtraHeightHoldMaxTicks,
        int hoverExtraHeightRerollIntervalTicks,
        float hoverExtraHeightChancePerReroll,

        // ================================================================
        // 5.0) SURFACE PREFERENCE (water vs solid + soft vs hard ground)
        // ================================================================
        SurfacePreferenceConfig surfacePreference,

        // ================================================================
        // 5.3) FUN SWIM (optional playful water behavior for land mobs)
        // ================================================================
        boolean funSwimEnabled,
        boolean funSwimOnlyIfSunny,
        boolean funSwimAvoidNight,
        int funSwimCheckIntervalTicks,
        float funSwimChance,
        int funSwimDurationTicks,
        double funSwimSearchRadius,
        int funSwimSearchAttempts,

        // ================================================================
        // 5.5) RAIN SHELTER (seek roof while raining + peek + roof-wander)
        // ================================================================
        boolean rainShelterEnabled,

        int rainShelterAttemptIntervalTicks,
        float rainShelterAttemptChance,

        double rainShelterSearchRadiusBlocks,
        int rainShelterSearchAttempts,
        int rainShelterRoofScanMaxBlocks,

        double rainShelterRunToShelterSpeed,
        double rainShelterWalkSpeed,

        int rainShelterLingerAfterRainTicks,

        int rainShelterPeekAvgIntervalTicks,
        int rainShelterPeekMinTicks,
        int rainShelterPeekMaxTicks,
        double rainShelterPeekDistanceMinBlocks,
        double rainShelterPeekDistanceMaxBlocks,
        int rainShelterPeekSearchAttempts,

        boolean rainShelterShuffleEnabled,
        int rainShelterShuffleIntervalMinTicks,
        int rainShelterShuffleIntervalMaxTicks,
        int rainShelterShuffleSearchAttempts,

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
        double sleepSearchRadius,
        double sleepSearchMinDistance,
        boolean sleepSearchRespectHomeRadius,
        boolean sleepSearchRequireSolidGround,

        // ================================================================
        // 11) ATTACK BEHAVIOUR
        // ================================================================
        boolean onlyUseRanged,
        boolean onlyUseMelee,
        boolean rangedUnlessClose,
        double rangedSwitchToDistance,
        double meleeSwitchBackDistance
)
{
    // ================================================================
    // GETTER METHODS
    // ================================================================
    public boolean isOnlyUseRanged() {
        return onlyUseRanged;
    }

    public boolean isOnlyUseMelee() {
        return onlyUseMelee;
    }

    public boolean isRangedUnlessClose() {
        return rangedUnlessClose;
    }

    public double rangedSwitchToDistance() {
        return rangedSwitchToDistance;
    }

    public double meleeSwitchBackDistance() {
        return meleeSwitchBackDistance;
    }

    // ================================================================
    // CONFIGS
    // ================================================================
    public record SurfacePreferenceConfig(
            double preferWaterSurfaceWeight,
            double preferSolidSurfaceWeight,
            double preferSoftGroundWeight,
            double preferHardGroundWeight
    ) {
        public static SurfacePreferenceConfig neutral() {
            return new SurfacePreferenceConfig(0.0D, 0.0D, 0.0D, 0.0D);
        }

        public static SurfacePreferenceConfig preferSoftLand() {
            return new SurfacePreferenceConfig(0.0D, 1.0D, 1.0D, 0.0D);
        }

        public static SurfacePreferenceConfig preferHardLand() {
            return new SurfacePreferenceConfig(0.0D, 1.0D, 0.0D, 1.0D);
        }

        public static SurfacePreferenceConfig waterLover() {
            return new SurfacePreferenceConfig(1.0D, 0.2D, 0.0D, 0.0D);
        }
    }

    // ================================================================
    // ENUMS
    // ================================================================
    public enum RangedDelivery {
        HITSCAN,
        PROJECTILE
    }
}
