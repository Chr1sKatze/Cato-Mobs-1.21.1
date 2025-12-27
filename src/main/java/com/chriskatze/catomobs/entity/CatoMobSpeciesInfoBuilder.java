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
    // 1.5) Render (cosmetic)
    // -----------------------------
    private float shadowRadius = 0.5F;

    // -----------------------------
    // 2) Core attributes
    // -----------------------------
    private double maxHealth = 10.0D;
    private double attackDamage = 1.0D;
    private double movementSpeed = 0.25D;
    private double followRange = 16.0D;
    private double gravity = 0.08D;

    // -----------------------------
    // 2.5) Retaliation / anger
    // -----------------------------
    private boolean retaliateWhenAngered = true;
    private int retaliationDurationTicks = 180;

    // -----------------------------
    // 2.6) Flee / panic
    // -----------------------------
    private boolean fleeEnabled = false;
    private boolean fleeOnLowHealth = false;
    private float fleeLowHealthThreshold = 3.0F;
    private boolean fleeOnHurt = false;
    private int fleeDurationTicks = 20 * 4;
    private int fleeCooldownTicks = 20 * 10;
    private double fleeSpeedModifier = 1.25D;
    private double fleeDesiredDistance = 12.0D;

    // -----------------------------
    // 2.7) Group flee
    // -----------------------------
    private boolean groupFleeEnabled = false;
    private double groupFleeRadius = 16.0D;
    private int groupFleeMaxAllies = 4;
    private boolean groupFleeBypassCooldown = false;

    // -----------------------------
    // 2.8) Group flee ally definition
    // -----------------------------
    private boolean groupFleeAnyCatoMobAllies = false;
    private Set<EntityType<?>> groupFleeAllyTypes = Collections.emptySet();

    // -----------------------------
    // 3) Combat timing
    // -----------------------------
    private double attackTriggerRange = 2.5D;
    private double attackHitRange = 2.5D;
    private int attackCooldownTicks = 20;
    private int attackAnimTotalTicks = 20;
    private int attackHitDelayTicks = 10;

    private double chaseSpeedModifier = 1.1D;

    private boolean moveDuringAttackAnimation = false;
    private int attackMoveStartDelayTicks = 0;
    private int attackMoveStopAfterTicks = 0;

    // -----------------------------
    // 3.5) Special Melee Combat
    // -----------------------------
    private boolean meleeSpecialEnabled = false;
    private double meleeSpecialTriggerRange = 2.5D;
    private double meleeSpecialHitRange = 2.5D;
    private int meleeSpecialCooldownTicks = 40;
    private int meleeSpecialAnimTotalTicks = 20;
    private int meleeSpecialHitDelayTicks = 10;
    private double meleeSpecialDamage = 2.0D; // example
    private boolean meleeSpecialMoveDuringAttackAnimation = false;
    private int meleeSpecialMoveStartDelayTicks = 0;
    private int meleeSpecialMoveStopAfterTicks = 0;
    private float meleeSpecialUseChance = 0.2f;
    private int meleeSpecialAfterNormalHits = 0; // 0 = disabled

    // -----------------------------
    // 4) Wander / movement
    // -----------------------------
    private double wanderWalkSpeed = 1.0D;
    private double wanderRunSpeed = 1.2D;
    private float wanderRunChance = 0.0F;
    private double wanderMinRadius = 2.0D;
    private double wanderMaxRadius = 16.0D;

    // ✅ NEW: wander attempt pacing (interval + chance, like sleep)
    // Default keeps old feel (≈ 1% per tick => ~every 100 ticks)
    private int wanderAttemptIntervalTicks = 100;
    private float wanderAttemptChance = 1.0F;

    private boolean stayWithinHomeRadius = false;
    private double homeRadius = 64.0D;
    private double wanderRunDistanceThreshold = -1.0D;

    // -----------------------------
    // 5) Water tuning
    // -----------------------------
    private double waterSwimSpeedMultiplier = 1.0D;

    private CatoMobSpeciesInfo.WaterMovementConfig waterMovement =
            CatoMobSpeciesInfo.WaterMovementConfig.disabled();

    // -----------------------------
    // 5.2) Surface preference (NEW)
    // -----------------------------
    private CatoMobSpeciesInfo.SurfacePreferenceConfig surfacePreference =
            CatoMobSpeciesInfo.SurfacePreferenceConfig.neutral();

    // -----------------------------
    // 5.3) Fun swim (optional; disabled by default) (NEW)
    // -----------------------------
    private boolean funSwimEnabled = false;
    private boolean funSwimOnlyIfSunny = false;
    private boolean funSwimAvoidNight = false;
    private int funSwimCheckIntervalTicks = 20 * 30; // check every 30s
    private float funSwimChance = 0.0F;              // 0 = never
    private int funSwimDurationTicks = 20 * 15;      // 15s swim
    private double funSwimSearchRadius = 16.0D;
    private int funSwimSearchAttempts = 16;

    // -----------------------------
    // 5.5) Rain shelter (disabled by default)
    // -----------------------------
    private boolean rainShelterEnabled = false;

    private int rainShelterAttemptIntervalTicks = 20 * 5;
    private float rainShelterAttemptChance = 0.0F;

    private double rainShelterSearchRadiusBlocks = 16.0D;
    private int rainShelterSearchAttempts = 24;
    private int rainShelterRoofScanMaxBlocks = 12;

    private double rainShelterRunToShelterSpeed = 1.2D;
    private double rainShelterWalkSpeed = 1.0D;

    private int rainShelterLingerAfterRainTicks = 0;

    // ✅ Peek (human-tunable)
    private int rainShelterPeekAvgIntervalTicks = 0; // 0 = disabled
    private int rainShelterPeekMinTicks = 0;
    private int rainShelterPeekMaxTicks = 0;
    private double rainShelterPeekDistanceMinBlocks = 2.0D;
    private double rainShelterPeekDistanceMaxBlocks = 6.0D;
    private int rainShelterPeekSearchAttempts = 16;

    // ✅ Roof-wander pacing under shelter (cleaner API — no radius fields)
    private boolean rainShelterShuffleEnabled = false;
    private int rainShelterShuffleIntervalMinTicks = 20 * 2;
    private int rainShelterShuffleIntervalMaxTicks = 20 * 4;
    private int rainShelterShuffleSearchAttempts = 16;

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

    public CatoMobSpeciesInfoBuilder shadow(float radius) {
        this.shadowRadius = Math.max(0.0F, radius);
        return this;
    }

    public CatoMobSpeciesInfoBuilder retaliation(boolean enabled, int durationTicks) {
        this.retaliateWhenAngered = enabled;
        this.retaliationDurationTicks = Math.max(0, durationTicks);
        return this;
    }

    public CatoMobSpeciesInfoBuilder flee(
            boolean enabled,
            boolean onLowHealth,
            float lowHealthThreshold,
            boolean onHurt,
            int durationTicks,
            int cooldownTicks,
            double speedModifier,
            double desiredDistance
    ) {
        this.fleeEnabled = enabled;
        this.fleeOnLowHealth = onLowHealth;
        this.fleeLowHealthThreshold = Math.max(0.0F, lowHealthThreshold);
        this.fleeOnHurt = onHurt;
        this.fleeDurationTicks = Math.max(0, durationTicks);
        this.fleeCooldownTicks = Math.max(0, cooldownTicks);
        this.fleeSpeedModifier = Math.max(0.05D, speedModifier);
        this.fleeDesiredDistance = Math.max(1.0D, desiredDistance);
        return this;
    }

    public CatoMobSpeciesInfoBuilder groupFlee(boolean enabled, double radius, int maxAllies, boolean bypassCooldown) {
        this.groupFleeEnabled = enabled;
        this.groupFleeRadius = Math.max(0.0D, radius);
        this.groupFleeMaxAllies = Math.max(0, maxAllies);
        this.groupFleeBypassCooldown = bypassCooldown;
        return this;
    }

    public CatoMobSpeciesInfoBuilder groupFleeAllies(boolean anyCatoBaseMob, Set<EntityType<?>> types) {
        this.groupFleeAnyCatoMobAllies = anyCatoBaseMob;
        this.groupFleeAllyTypes = (types == null ? Collections.emptySet() : new HashSet<>(types));
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

    public CatoMobSpeciesInfoBuilder specialMelee(
            boolean enabled,
            double triggerRange,
            double hitRange,
            int cooldownTicks,
            int animTotalTicks,
            int hitDelayTicks,
            double damage,
            boolean moveDuringAnim,
            int moveStartDelay,
            int moveStopAfter,
            float useChance,
            int afterNormalHits
    ) {
        this.meleeSpecialEnabled = enabled;
        this.meleeSpecialTriggerRange = triggerRange;
        this.meleeSpecialHitRange = hitRange;
        this.meleeSpecialCooldownTicks = cooldownTicks;
        this.meleeSpecialAnimTotalTicks = animTotalTicks;
        this.meleeSpecialHitDelayTicks = hitDelayTicks;
        this.meleeSpecialDamage = damage;
        this.meleeSpecialMoveDuringAttackAnimation = moveDuringAnim;
        this.meleeSpecialMoveStartDelayTicks = moveStartDelay;
        this.meleeSpecialMoveStopAfterTicks = moveStopAfter;
        this.meleeSpecialUseChance = useChance;
        this.meleeSpecialAfterNormalHits = afterNormalHits;
        return this;
    }

    public CatoMobSpeciesInfoBuilder chaseSpeed(double modifier) {
        this.chaseSpeedModifier = modifier;
        return this;
    }

    public CatoMobSpeciesInfoBuilder moveDuringAttackAnimation(boolean allowMove) {
        this.moveDuringAttackAnimation = allowMove;
        return this;
    }

    public CatoMobSpeciesInfoBuilder attackMoveWindow(int startDelayTicks, int stopAfterTicks) {
        this.attackMoveStartDelayTicks = startDelayTicks;
        this.attackMoveStopAfterTicks = stopAfterTicks;
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

    /**
     * ✅ NEW: Wander attempt pacing (interval + chance), just like sleepAttempts().
     *
     * intervalTicks: how often we "consider" wandering
     * chance: chance to actually start wandering when that interval triggers
     */
    public CatoMobSpeciesInfoBuilder wanderAttempts(int intervalTicks, float chance) {
        this.wanderAttemptIntervalTicks = intervalTicks;
        this.wanderAttemptChance = chance;
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

    // ================================================================
    // ✅ Surface preference fluent setters (NEW)
    // ================================================================

    public CatoMobSpeciesInfoBuilder surfacePreference(double water, double solid, double soft, double hard) {
        this.surfacePreference = new CatoMobSpeciesInfo.SurfacePreferenceConfig(water, solid, soft, hard);
        return this;
    }

    public CatoMobSpeciesInfoBuilder preferSoftLand() {
        this.surfacePreference = CatoMobSpeciesInfo.SurfacePreferenceConfig.preferSoftLand();
        return this;
    }

    public CatoMobSpeciesInfoBuilder preferHardLand() {
        this.surfacePreference = CatoMobSpeciesInfo.SurfacePreferenceConfig.preferHardLand();
        return this;
    }

    public CatoMobSpeciesInfoBuilder waterLover() {
        this.surfacePreference = CatoMobSpeciesInfo.SurfacePreferenceConfig.waterLover();
        return this;
    }

    // ================================================================
    // ✅ Fun swim fluent setter (NEW)
    // ================================================================

    public CatoMobSpeciesInfoBuilder funSwim(
            boolean enabled,
            boolean onlyIfSunny,
            boolean avoidNight,
            int checkIntervalTicks,
            float chance,
            int durationTicks,
            double searchRadius,
            int searchAttempts
    ) {
        this.funSwimEnabled = enabled;
        this.funSwimOnlyIfSunny = onlyIfSunny;
        this.funSwimAvoidNight = avoidNight;

        this.funSwimCheckIntervalTicks = Math.max(20, checkIntervalTicks);
        this.funSwimChance = clamp01(chance);
        this.funSwimDurationTicks = Math.max(20, durationTicks);
        this.funSwimSearchRadius = Math.max(4.0D, searchRadius);
        this.funSwimSearchAttempts = Math.max(4, searchAttempts);
        return this;
    }

    // ================================================================
    // ✅ Rain shelter fluent setters
    // ================================================================

    public CatoMobSpeciesInfoBuilder rainShelter(
            boolean enabled,
            int attemptIntervalTicks,
            float attemptChance,
            double searchRadiusBlocks,
            int searchAttempts,
            int roofScanMaxBlocks,
            double runToShelterSpeed,
            double walkSpeed,
            int lingerAfterRainTicks
    ) {
        this.rainShelterEnabled = enabled;
        this.rainShelterAttemptIntervalTicks = attemptIntervalTicks;
        this.rainShelterAttemptChance = attemptChance;
        this.rainShelterSearchRadiusBlocks = searchRadiusBlocks;
        this.rainShelterSearchAttempts = searchAttempts;
        this.rainShelterRoofScanMaxBlocks = roofScanMaxBlocks;
        this.rainShelterRunToShelterSpeed = runToShelterSpeed;
        this.rainShelterWalkSpeed = walkSpeed;
        this.rainShelterLingerAfterRainTicks = lingerAfterRainTicks;
        return this;
    }

    /**
     * ✅ Peek is now tuned by "avg interval ticks" instead of per-tick probability.
     * avgIntervalTicks = 0 disables peeking.
     */
    public CatoMobSpeciesInfoBuilder rainShelterPeek(
            int avgIntervalTicks,
            int peekMinTicks,
            int peekMaxTicks,
            double peekDistMinBlocks,
            double peekDistMaxBlocks,
            int peekSearchAttempts
    ) {
        this.rainShelterPeekAvgIntervalTicks = Math.max(0, avgIntervalTicks);
        this.rainShelterPeekMinTicks = Math.max(0, peekMinTicks);
        this.rainShelterPeekMaxTicks = Math.max(this.rainShelterPeekMinTicks, peekMaxTicks);
        this.rainShelterPeekDistanceMinBlocks = Math.max(0.0D, peekDistMinBlocks);
        this.rainShelterPeekDistanceMaxBlocks = Math.max(this.rainShelterPeekDistanceMinBlocks, peekDistMaxBlocks);
        this.rainShelterPeekSearchAttempts = Math.max(0, peekSearchAttempts);
        return this;
    }

    /**
     * ✅ Cleaner API: no radius params.
     * Roof-wander radius is taken from wanderMinRadius/wanderMaxRadius, with fallback to 0..max if needed.
     */
    public CatoMobSpeciesInfoBuilder rainShelterShuffle(
            boolean enabled,
            int intervalMinTicks,
            int intervalMaxTicks,
            int searchAttempts
    ) {
        this.rainShelterShuffleEnabled = enabled;
        this.rainShelterShuffleIntervalMinTicks = Math.max(1, intervalMinTicks);
        this.rainShelterShuffleIntervalMaxTicks = Math.max(this.rainShelterShuffleIntervalMinTicks, intervalMaxTicks);
        this.rainShelterShuffleSearchAttempts = Math.max(0, searchAttempts);
        return this;
    }

    // ================================================================
    // Sleep setters (unchanged)
    // ================================================================

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

        double chaseMod = Math.max(0.05D, this.chaseSpeedModifier);

        int moveDelay = Math.max(0, this.attackMoveStartDelayTicks);
        int moveStopAfter = this.attackMoveStopAfterTicks;
        if (moveStopAfter > 0 && moveStopAfter < moveDelay) {
            moveStopAfter = moveDelay;
        }

        // ✅ NEW: wander attempt pacing safety
        int wanderInterval = Math.max(1, this.wanderAttemptIntervalTicks);
        float wanderChance = clamp01(this.wanderAttemptChance);

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

        // ✅ Surface preference safety
        CatoMobSpeciesInfo.SurfacePreferenceConfig spIn =
                (this.surfacePreference == null) ? CatoMobSpeciesInfo.SurfacePreferenceConfig.neutral() : this.surfacePreference;

        final CatoMobSpeciesInfo.SurfacePreferenceConfig surfacePreferenceSafe =
                new CatoMobSpeciesInfo.SurfacePreferenceConfig(
                        spIn.preferWaterSurfaceWeight(),
                        spIn.preferSolidSurfaceWeight(),
                        spIn.preferSoftGroundWeight(),
                        spIn.preferHardGroundWeight()
                );

        // ================================================================
        // ✅ Fun swim safety (NEW)
        // ================================================================
        boolean funEnabled = this.funSwimEnabled;

        boolean funOnlySunny = this.funSwimOnlyIfSunny;
        boolean funAvoidNight = this.funSwimAvoidNight;

        int funInterval = Math.max(20, this.funSwimCheckIntervalTicks);
        float funChance = clamp01(this.funSwimChance);
        int funDuration = Math.max(20, this.funSwimDurationTicks);
        double funRadius = Math.max(4.0D, this.funSwimSearchRadius);
        int funAttempts = Math.max(4, this.funSwimSearchAttempts);

        if (!funEnabled) {
            funOnlySunny = false;
            funAvoidNight = false;
            funChance = 0.0f;
        }

        boolean preferBuddies = this.sleepPreferSleepingBuddies
                && this.sleepBuddySearchRadius > 0.0D
                && this.sleepBuddyMaxCount > 0
                && this.sleepBuddyTypes != null
                && !this.sleepBuddyTypes.isEmpty();

        Set<EntityType<?>> buddyTypes = preferBuddies ? Set.copyOf(this.sleepBuddyTypes) : Collections.emptySet();

        float shadow = Math.max(0.0F, this.shadowRadius);

        boolean retaliate = this.retaliateWhenAngered;
        int retaliationTicks = Math.max(0, this.retaliationDurationTicks);

        boolean fleeEnabledSafe = this.fleeEnabled;
        boolean fleeLowSafe = fleeEnabledSafe && this.fleeOnLowHealth;
        boolean fleeOnHurtSafe = fleeEnabledSafe && this.fleeOnHurt;

        float fleeHp = Math.max(0.0F, this.fleeLowHealthThreshold);
        int fleeDuration = Math.max(0, this.fleeDurationTicks);
        int fleeCooldown = Math.max(0, this.fleeCooldownTicks);
        double fleeSpeed = Math.max(0.05D, this.fleeSpeedModifier);
        double fleeDist = Math.max(1.0D, this.fleeDesiredDistance);

        if (!fleeEnabledSafe) {
            fleeLowSafe = false;
            fleeOnHurtSafe = false;
            fleeDuration = 0;
            fleeCooldown = 0;
        }

        boolean groupEnabled =
                this.groupFleeEnabled
                        && this.groupFleeRadius > 0.0D
                        && this.groupFleeMaxAllies > 0;

        double groupRadius = Math.max(0.0D, this.groupFleeRadius);
        int groupMax = Math.max(0, this.groupFleeMaxAllies);
        boolean groupBypass = this.groupFleeBypassCooldown;

        if (!fleeEnabledSafe) {
            groupEnabled = false;
        }

        boolean anyCatoAllies = this.groupFleeAnyCatoMobAllies;
        Set<EntityType<?>> allyTypesSafe = anyCatoAllies
                ? Collections.emptySet()
                : (this.groupFleeAllyTypes == null ? Collections.emptySet() : Set.copyOf(this.groupFleeAllyTypes));

        if (!groupEnabled) {
            anyCatoAllies = false;
            allyTypesSafe = Collections.emptySet();
        }

        // ================================================================
        // ✅ Rain shelter safety
        // ================================================================
        boolean rainEnabled = this.rainShelterEnabled;

        int rainInterval = Math.max(1, this.rainShelterAttemptIntervalTicks);
        float rainChance = clamp01(this.rainShelterAttemptChance);

        double rainRadius = Math.max(4.0D, this.rainShelterSearchRadiusBlocks);
        int rainAttempts = Math.max(1, this.rainShelterSearchAttempts);
        int rainRoofMax = Math.max(1, this.rainShelterRoofScanMaxBlocks);

        double rainRun = Math.max(0.05D, this.rainShelterRunToShelterSpeed);
        double rainWalk = Math.max(0.05D, this.rainShelterWalkSpeed);

        int rainLinger = Math.max(0, this.rainShelterLingerAfterRainTicks);

        int peekAvg = Math.max(0, this.rainShelterPeekAvgIntervalTicks);
        int peekMin = Math.max(0, this.rainShelterPeekMinTicks);
        int peekMax = Math.max(peekMin, this.rainShelterPeekMaxTicks);
        double peekMinDist = Math.max(0.0D, this.rainShelterPeekDistanceMinBlocks);
        double peekMaxDist = Math.max(peekMinDist, this.rainShelterPeekDistanceMaxBlocks);
        int peekAttempts = Math.max(0, this.rainShelterPeekSearchAttempts);

        boolean shuffleEnabled = this.rainShelterShuffleEnabled;
        int shufMin = Math.max(1, this.rainShelterShuffleIntervalMinTicks);
        int shufMax = Math.max(shufMin, this.rainShelterShuffleIntervalMaxTicks);
        int shufAttempts = Math.max(0, this.rainShelterShuffleSearchAttempts);

        if (!rainEnabled) {
            rainChance = 0.0f;
            rainLinger = 0;

            peekAvg = 0;
            shuffleEnabled = false;
        }

        // ================================================================
        // ✅ SPECIAL MELEE safety (THIS WAS MISSING)
        // ================================================================
        boolean specialEnabled = this.meleeSpecialEnabled;

        double specialTriggerRange = Math.max(0.0D, this.meleeSpecialTriggerRange);
        double specialHitRange = Math.max(0.0D, this.meleeSpecialHitRange);

        int specialCooldown = Math.max(0, this.meleeSpecialCooldownTicks);
        int specialAnimTotal = Math.max(1, this.meleeSpecialAnimTotalTicks);
        int specialHitDelay = Math.max(0, this.meleeSpecialHitDelayTicks);

        double specialDamage = Math.max(0.0D, this.meleeSpecialDamage);

        boolean specialMoveDuring = this.meleeSpecialMoveDuringAttackAnimation;

        int specialMoveDelay = Math.max(0, this.meleeSpecialMoveStartDelayTicks);
        int specialMoveStopAfter = this.meleeSpecialMoveStopAfterTicks;
        if (specialMoveStopAfter > 0 && specialMoveStopAfter < specialMoveDelay) {
            specialMoveStopAfter = specialMoveDelay;
        }

        float specialUseChance = clamp01(this.meleeSpecialUseChance);
        int specialAfterHits = Math.max(0, this.meleeSpecialAfterNormalHits);

        // If disabled, force “off” values so downstream logic stays simple
        if (!specialEnabled) {
            specialUseChance = 0.0f;
            specialCooldown = 0;
            specialDamage = 0.0D;

            specialMoveDuring = false;
            specialMoveDelay = 0;
            specialMoveStopAfter = 0;

            specialTriggerRange = 0.0D;
            specialHitRange = 0.0D;

            specialAnimTotal = 1;
            specialHitDelay = 0;

            specialAfterHits = 0;
        }


        return new CatoMobSpeciesInfo(
                // 1) Identity
                movementType, temperament, sizeCategory,

                // 1.5) Render
                shadow,

                // 2) Core
                Math.max(1.0D, maxHealth),
                Math.max(0.0D, attackDamage),
                Math.max(0.0D, movementSpeed),
                Math.max(0.0D, followRange),
                gravity,

                // 2.5) Retaliation
                retaliate,
                retaliationTicks,

                // 2.6) Flee / panic
                fleeEnabledSafe,
                fleeLowSafe,
                fleeHp,
                fleeOnHurtSafe,
                fleeDuration,
                fleeCooldown,
                fleeSpeed,
                fleeDist,

                // 2.7) Group flee
                groupEnabled,
                groupRadius,
                groupMax,
                groupBypass,

                // 2.8) Group flee ally definition
                anyCatoAllies,
                allyTypesSafe,

                // 3) Combat (normal timed attack)
                Math.max(0.0D, attackTriggerRange),
                Math.max(0.0D, attackHitRange),
                Math.max(0, attackCooldownTicks),
                Math.max(1, attackAnimTotalTicks),
                Math.max(0, attackHitDelayTicks),
                chaseMod,
                this.moveDuringAttackAnimation,
                moveDelay,
                moveStopAfter,

                // 3.1) ✅ SPECIAL MELEE (INSERTED HERE)
                specialEnabled,
                specialTriggerRange,
                specialHitRange,
                specialCooldown,
                specialAnimTotal,
                specialHitDelay,
                specialDamage,
                specialMoveDuring,
                specialMoveDelay,
                specialMoveStopAfter,
                specialUseChance,
                specialAfterHits,

                // 4) Wander
                Math.max(0.0D, wanderWalkSpeed),
                Math.max(0.0D, wanderRunSpeed),
                runChance,
                minRadius,
                maxRadius,

                // ✅ NEW: wander attempt pacing
                wanderInterval,
                wanderChance,

                stayWithinHomeRadius,
                Math.max(0.0D, homeRadius),
                wanderRunDistanceThreshold,

                // 5) Water
                waterMul,
                waterMovementSafe,

                // 5.2) Surface preference (NEW)
                surfacePreferenceSafe,

                // 5.3) Fun swim (NEW)
                funEnabled,
                funOnlySunny,
                funAvoidNight,
                funInterval,
                funChance,
                funDuration,
                funRadius,
                funAttempts,

                // 5.5) Rain shelter
                rainEnabled,
                rainInterval,
                rainChance,
                rainRadius,
                rainAttempts,
                rainRoofMax,
                rainRun,
                rainWalk,
                rainLinger,

                // peek (avg interval)
                peekAvg,
                peekMin,
                peekMax,
                peekMinDist,
                peekMaxDist,
                peekAttempts,

                // roof-wander pacing
                shuffleEnabled,
                shufMin,
                shufMax,
                shufAttempts,

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
