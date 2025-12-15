package com.chriskatze.catomobs.entity;

import net.minecraft.world.entity.EntityType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * CatoMobSpeciesInfoBuilder
 *
 * Fluent builder for CatoMobSpeciesInfo so you never have to count constructor args again.
 */
public final class CatoMobSpeciesInfoBuilder {

    // -----------------------------
    // 1) Identity & classification
    // -----------------------------
    private CatoMobMovementType movementType = CatoMobMovementType.LAND;
    private CatoMobTemperament temperament = CatoMobTemperament.PASSIVE;
    private CatoMobSizeCategory sizeCategory = CatoMobSizeCategory.SMALL;

    // -----------------------------
    // 2) Core attributes
    // -----------------------------
    private double maxHealth = 10.0D;
    private double attackDamage = 1.0D;
    private double movementSpeed = 0.25D;
    private double followRange = 16.0D;
    private double gravity = 0.08D;

    // -----------------------------
    // 3) Combat timing
    // -----------------------------
    private double attackTriggerRange = 2.5D;
    private double attackHitRange = 2.5D;
    private int attackCooldownTicks = 20;
    private int attackAnimTotalTicks = 20;
    private int attackHitDelayTicks = 10;

    // -----------------------------
    // 4) Wander / movement
    // -----------------------------
    private double wanderWalkSpeed = 1.0D;
    private double wanderRunSpeed = 1.2D;
    private float wanderRunChance = 0.0F;
    private double wanderMinRadius = 2.0D;
    private double wanderMaxRadius = 16.0D;

    private boolean stayWithinHomeRadius = false;
    private double homeRadius = 64.0D;
    private double wanderRunDistanceThreshold = -1.0D;

    // -----------------------------
    // 5) Water tuning
    // -----------------------------
    private double waterSwimSpeedMultiplier = 1.0D;

    /**
     * Per-species water movement "feel" config used by WaterMovementComponent.
     * Default: OFF (so land mobs don't damp unless you enable it).
     */
    private CatoMobSpeciesInfo.WaterMovementConfig waterMovement =
            CatoMobSpeciesInfo.WaterMovementConfig.disabled();

    // -----------------------------
    // 6) Sleep: enable + window + pacing
    // -----------------------------
    private boolean sleepEnabled = false;
    private boolean sleepAtNight = true;
    private boolean sleepAtDay = false;

    private int sleepAttemptIntervalTicks = 20 * 10;
    private float sleepAttemptChance = 0.0F;

    private float sleepContinueChance = 0.0F;
    private int sleepMinTicks = 20 * 20;
    private int sleepMaxTicks = 20 * 40;

    private int sleepTimeWindowWakeGraceMinTicks = 40;
    private int sleepTimeWindowWakeGraceMaxTicks = 120;

    private int sleepDesireWindowTicks = 200;

    // -----------------------------
    // 7) Sleep spot memory
    // -----------------------------
    private int sleepSpotMemorySize = 0;
    private int sleepSpotMemoryMaxStrikes = 2;

    // -----------------------------
    // 8) Sleep buddies
    // -----------------------------
    private boolean sleepPreferSleepingBuddies = false;
    private double sleepBuddySearchRadius = 0.0D;
    private int sleepBuddyMaxCount = 0;
    private int sleepBuddyRelocateRadiusBlocks = 1;
    private int sleepBuddyScoreBonusPerBuddy = 0;
    private boolean sleepBuddyCanOverrideMemory = false;
    private Set<EntityType<?>> sleepBuddyTypes = Collections.emptySet();

    // -----------------------------
    // 9) Constraints & wake conditions
    // -----------------------------
    private boolean sleepRequiresRoof = false;
    private boolean sleepAllowedOnWaterSurface = false;

    private boolean wakeOnDamage = true;
    private boolean wakeOnAir = true;
    private boolean wakeOnTouchingWater = true;
    private boolean wakeOnUnderwater = true;
    private boolean wakeOnSunlight = false;

    // -----------------------------
    // 10) Sleep search
    // -----------------------------
    private int sleepSearchMaxAttempts = 60;
    private int sleepSearchMaxPathAttempts = 20;
    private int sleepSearchMinHeadroomBlocks = 2;
    private int sleepSearchCeilingScanMaxBlocks = 8;
    private int sleepSearchTimeoutTicks = 20 * 5;
    private int sleepSearchCooldownTicks = 20 * 3;
    private double sleepSearchRadiusMultiplier = 1.5D;
    private double sleepSearchMinDistance = 2.0D;
    private boolean sleepSearchRespectHomeRadius = true;
    private boolean sleepSearchRequireSolidGround = true;

    private CatoMobSpeciesInfoBuilder() {}

    public static CatoMobSpeciesInfoBuilder create() {
        return new CatoMobSpeciesInfoBuilder();
    }

    // ================================================================
    // Fluent setters
    // ================================================================

    public CatoMobSpeciesInfoBuilder identity(CatoMobMovementType movementType,
                                              CatoMobTemperament temperament,
                                              CatoMobSizeCategory sizeCategory) {
        this.movementType = movementType;
        this.temperament = temperament;
        this.sizeCategory = sizeCategory;
        return this;
    }

    public CatoMobSpeciesInfoBuilder core(double maxHealth, double attackDamage,
                                          double movementSpeed, double followRange, double gravity) {
        this.maxHealth = maxHealth;
        this.attackDamage = attackDamage;
        this.movementSpeed = movementSpeed;
        this.followRange = followRange;
        this.gravity = gravity;
        return this;
    }

    public CatoMobSpeciesInfoBuilder combat(double triggerRange, double hitRange,
                                            int cooldownTicks, int animTotalTicks, int hitDelayTicks) {
        this.attackTriggerRange = triggerRange;
        this.attackHitRange = hitRange;
        this.attackCooldownTicks = cooldownTicks;
        this.attackAnimTotalTicks = animTotalTicks;
        this.attackHitDelayTicks = hitDelayTicks;
        return this;
    }

    public CatoMobSpeciesInfoBuilder wander(double walkSpeed, double runSpeed, float runChance,
                                            double minRadius, double maxRadius) {
        this.wanderWalkSpeed = walkSpeed;
        this.wanderRunSpeed = runSpeed;
        this.wanderRunChance = runChance;
        this.wanderMinRadius = minRadius;
        this.wanderMaxRadius = maxRadius;
        return this;
    }

    public CatoMobSpeciesInfoBuilder home(boolean stayWithinHomeRadius, double homeRadius) {
        this.stayWithinHomeRadius = stayWithinHomeRadius;
        this.homeRadius = homeRadius;
        return this;
    }

    public CatoMobSpeciesInfoBuilder wanderRunDistanceThreshold(double threshold) {
        this.wanderRunDistanceThreshold = threshold;
        return this;
    }

    public CatoMobSpeciesInfoBuilder waterSwimSpeedMultiplier(double multiplier) {
        this.waterSwimSpeedMultiplier = multiplier;
        return this;
    }

    public CatoMobSpeciesInfoBuilder waterMovement(CatoMobSpeciesInfo.WaterMovementConfig cfg) {
        if (cfg != null) this.waterMovement = cfg;
        return this;
    }

    public CatoMobSpeciesInfoBuilder waterMovement(boolean dampingEnabled,
                                                   double verticalDamping,
                                                   double verticalSpeedClamp,
                                                   double dampingApplyThreshold) {
        this.waterMovement = new CatoMobSpeciesInfo.WaterMovementConfig(
                dampingEnabled, verticalDamping, verticalSpeedClamp, dampingApplyThreshold
        );
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepWindow(boolean enabled, boolean atNight, boolean atDay) {
        this.sleepEnabled = enabled;
        this.sleepAtNight = atNight;
        this.sleepAtDay = atDay;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepAttempts(int intervalTicks, float chance) {
        this.sleepAttemptIntervalTicks = intervalTicks;
        this.sleepAttemptChance = chance;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepDuration(int minTicks, int maxTicks, float continueChance) {
        this.sleepMinTicks = minTicks;
        this.sleepMaxTicks = maxTicks;
        this.sleepContinueChance = continueChance;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepGrace(int minTicks, int maxTicks) {
        this.sleepTimeWindowWakeGraceMinTicks = minTicks;
        this.sleepTimeWindowWakeGraceMaxTicks = maxTicks;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepDesireWindow(int ticks) {
        this.sleepDesireWindowTicks = ticks;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepMemory(int size, int maxStrikes) {
        this.sleepSpotMemorySize = size;
        this.sleepSpotMemoryMaxStrikes = maxStrikes;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepBuddies(boolean prefer, double searchRadius, int maxCount,
                                                  int relocateRadiusBlocks, int scoreBonusPerBuddy,
                                                  boolean canOverrideMemory, Set<EntityType<?>> types) {
        this.sleepPreferSleepingBuddies = prefer;
        this.sleepBuddySearchRadius = searchRadius;
        this.sleepBuddyMaxCount = maxCount;
        this.sleepBuddyRelocateRadiusBlocks = relocateRadiusBlocks;
        this.sleepBuddyScoreBonusPerBuddy = scoreBonusPerBuddy;
        this.sleepBuddyCanOverrideMemory = canOverrideMemory;
        this.sleepBuddyTypes = (types == null ? Collections.emptySet() : new HashSet<>(types));
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepConstraints(boolean requiresRoof, boolean allowedOnWaterSurface) {
        this.sleepRequiresRoof = requiresRoof;
        this.sleepAllowedOnWaterSurface = allowedOnWaterSurface;
        return this;
    }

    public CatoMobSpeciesInfoBuilder wakeRules(boolean onDamage, boolean onAir, boolean onTouchingWater,
                                               boolean onUnderwater, boolean onSunlight) {
        this.wakeOnDamage = onDamage;
        this.wakeOnAir = onAir;
        this.wakeOnTouchingWater = onTouchingWater;
        this.wakeOnUnderwater = onUnderwater;
        this.wakeOnSunlight = onSunlight;
        return this;
    }

    public CatoMobSpeciesInfoBuilder sleepSearch(int maxAttempts, int maxPathAttempts,
                                                 int minHeadroomBlocks, int ceilingScanMaxBlocks,
                                                 int timeoutTicks, int cooldownTicks,
                                                 double radiusMultiplier, double minDistance,
                                                 boolean respectHomeRadius, boolean requireSolidGround) {
        this.sleepSearchMaxAttempts = maxAttempts;
        this.sleepSearchMaxPathAttempts = maxPathAttempts;
        this.sleepSearchMinHeadroomBlocks = minHeadroomBlocks;
        this.sleepSearchCeilingScanMaxBlocks = ceilingScanMaxBlocks;
        this.sleepSearchTimeoutTicks = timeoutTicks;
        this.sleepSearchCooldownTicks = cooldownTicks;
        this.sleepSearchRadiusMultiplier = radiusMultiplier;
        this.sleepSearchMinDistance = minDistance;
        this.sleepSearchRespectHomeRadius = respectHomeRadius;
        this.sleepSearchRequireSolidGround = requireSolidGround;
        return this;
    }

    // ================================================================
    // Build with basic safety
    // ================================================================
    public CatoMobSpeciesInfo build() {
        // Clamp/validate common mistakes
        float attemptChance = clamp01(this.sleepAttemptChance);
        float continueChance = clamp01(this.sleepContinueChance);
        float runChance = clamp01(this.wanderRunChance);

        int sleepMin = Math.max(1, this.sleepMinTicks);
        int sleepMax = Math.max(sleepMin, this.sleepMaxTicks);

        int graceMin = Math.max(1, this.sleepTimeWindowWakeGraceMinTicks);
        int graceMax = Math.max(graceMin, this.sleepTimeWindowWakeGraceMaxTicks);

        int attemptInterval = Math.max(1, this.sleepAttemptIntervalTicks);

        int memSize = Math.max(0, this.sleepSpotMemorySize);
        int memStrikes = Math.max(1, this.sleepSpotMemoryMaxStrikes);

        int searchAttempts = Math.max(1, this.sleepSearchMaxAttempts);
        int pathAttempts = Math.max(0, this.sleepSearchMaxPathAttempts);
        int headroom = Math.max(1, this.sleepSearchMinHeadroomBlocks);
        int ceilingScan = Math.max(1, this.sleepSearchCeilingScanMaxBlocks);
        int timeout = Math.max(1, this.sleepSearchTimeoutTicks);
        int cooldown = Math.max(0, this.sleepSearchCooldownTicks);

        double maxRadius = Math.max(this.wanderMaxRadius, 0.0D);
        double minRadius = Math.max(0.0D, Math.min(this.wanderMinRadius, maxRadius));

        double waterMul = (this.waterSwimSpeedMultiplier <= 0.0D) ? 1.0D : this.waterSwimSpeedMultiplier;

        // Normalize + clamp water config
        CatoMobSpeciesInfo.WaterMovementConfig wmIn =
                (this.waterMovement == null) ? CatoMobSpeciesInfo.WaterMovementConfig.disabled() : this.waterMovement;

        final CatoMobSpeciesInfo.WaterMovementConfig waterMovementSafe;
        if (!wmIn.dampingEnabled()) {
            waterMovementSafe = CatoMobSpeciesInfo.WaterMovementConfig.disabled();
        } else {
            double damping = Math.max(0.0D, wmIn.verticalDamping());
            double clamp = Math.max(0.0D, wmIn.verticalSpeedClamp());
            double thresh = Math.max(0.0D, wmIn.dampingApplyThreshold());
            waterMovementSafe = new CatoMobSpeciesInfo.WaterMovementConfig(true, damping, clamp, thresh);
        }

        // If buddies are disabled, normalize buddy config so you don't accidentally waste cycles.
        boolean preferBuddies = this.sleepPreferSleepingBuddies
                && this.sleepBuddySearchRadius > 0.0D
                && this.sleepBuddyMaxCount > 0
                && this.sleepBuddyTypes != null
                && !this.sleepBuddyTypes.isEmpty();

        Set<EntityType<?>> buddyTypes = preferBuddies ? Set.copyOf(this.sleepBuddyTypes) : Collections.emptySet();

        return new CatoMobSpeciesInfo(
                // 1) Identity
                movementType, temperament, sizeCategory,

                // 2) Core
                Math.max(1.0D, maxHealth),
                Math.max(0.0D, attackDamage),
                Math.max(0.0D, movementSpeed),
                Math.max(0.0D, followRange),
                gravity,

                // 3) Combat
                Math.max(0.0D, attackTriggerRange),
                Math.max(0.0D, attackHitRange),
                Math.max(0, attackCooldownTicks),
                Math.max(1, attackAnimTotalTicks),
                Math.max(0, attackHitDelayTicks),

                // 4) Wander
                Math.max(0.0D, wanderWalkSpeed),
                Math.max(0.0D, wanderRunSpeed),
                runChance,
                minRadius,
                maxRadius,
                stayWithinHomeRadius,
                Math.max(0.0D, homeRadius),
                wanderRunDistanceThreshold,

                // 5) Water
                waterMul,
                waterMovementSafe,

                // 6) Sleep window + attempts
                sleepEnabled,
                sleepAtNight,
                sleepAtDay,
                attemptInterval,
                attemptChance,
                continueChance,
                sleepMin,
                sleepMax,
                graceMin,
                graceMax,
                Math.max(1, sleepDesireWindowTicks),

                // 7) Memory
                memSize,
                memStrikes,

                // 8) Buddies
                preferBuddies,
                Math.max(0.0D, sleepBuddySearchRadius),
                Math.max(0, sleepBuddyMaxCount),
                Math.max(1, sleepBuddyRelocateRadiusBlocks),
                Math.max(0, sleepBuddyScoreBonusPerBuddy),
                sleepBuddyCanOverrideMemory,
                buddyTypes,

                // 9) Constraints & wake rules
                sleepRequiresRoof,
                sleepAllowedOnWaterSurface,
                wakeOnDamage,
                wakeOnAir,
                wakeOnTouchingWater,
                wakeOnUnderwater,
                wakeOnSunlight,

                // 10) Search
                searchAttempts,
                pathAttempts,
                headroom,
                ceilingScan,
                timeout,
                cooldown,
                sleepSearchRadiusMultiplier,
                sleepSearchMinDistance,
                sleepSearchRespectHomeRadius,
                sleepSearchRequireSolidGround
        );
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
