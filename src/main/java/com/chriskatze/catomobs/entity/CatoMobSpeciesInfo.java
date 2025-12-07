package com.chriskatze.catomobs.entity;

public record CatoMobSpeciesInfo(
        CatoMobMovementType movementType,
        CatoMobTemperament temperament,
        CatoMobSizeCategory sizeCategory,
        double maxHealth,
        double attackDamage,
        double movementSpeed,
        double followRange,
        double gravity,
        double attackTriggerRange,
        double attackHitRange,
        int attackCooldownTicks,
        int attackAnimTotalTicks,
        int attackHitDelayTicks,
        double wanderWalkSpeed,
        double wanderRunSpeed,
        float wanderRunChance,
        double wanderMinRadius,
        double wanderMaxRadius,
        boolean stayWithinHomeRadius,
        double homeRadius,
        double wanderRunDistanceThreshold,
        double waterSwimSpeedMultiplier,
        boolean sleepEnabled,
        boolean sleepAtNight,
        boolean sleepAtDay,
        float sleepStartChancePerTick,
        int sleepMinTicks,
        int sleepMaxTicks,
        boolean sleepRequiresRoof, // must NOT see sky above
        boolean sleepAllowedOnWaterSurface, // can sleep while in water (surface-ish)
        boolean wakeOnDamage,
        boolean wakeOnAir,
        boolean wakeOnTouchingWater,
        boolean wakeOnUnderwater,
        boolean wakeOnSunlight,
        int sleepSearchMaxAttempts, // how many random candidates to test per search
        int sleepSearchMaxPathAttempts, // how many of those candidates must also be pathable (optional)
        int sleepSearchTimeoutTicks, // how long we keep searching before giving up (prevents endless searching)
        int sleepSearchCooldownTicks, // after a failed search, wait this long before trying again
        double sleepSearchRadiusMultiplier, // multiplies wanderMaxRadius for search radius (1.0 = same as wander)
        double sleepSearchMinDistance, // ignore spots closer than this (avoid sleeping “right here” if roof needed)
        boolean sleepSearchRespectHomeRadius, // if true, keep spots inside home circle (even if stayWithinHomeRadius is false)
        boolean sleepSearchRequireSolidGround, // only accept spots with solid block below + space to stand
        boolean sleepSearchRequireRoof // when true, a spot must NOT see sky (roof over head)
) {}
