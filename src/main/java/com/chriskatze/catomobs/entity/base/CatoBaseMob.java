package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for all CatoMobs.
 *
 * Responsibilities:
 * 1) Central place to register and configure goals (sleep, wander, combat, etc.).
 * 2) Shared "timed attack" system (start anim now, deal damage later at a specific tick).
 * 3) Shared sleep system (random sleep chance + optional roof-search goal).
 * 4) Shared aggression/anger state handling (retaliate for X ticks, then calm down).
 * 5) Shared "home position + radius" concept used by wandering / sleep search.
 * 6) Shared synced data (sleep flag, movement mode, visually-angry flag).
 *
 * Subclasses provide:
 * - species info (CatoMobSpeciesInfo)
 * - optional overrides for animation hooks (attack start/end, wander start/stop, chase start/tick/stop)
 */
public abstract class CatoBaseMob extends Animal {

    // ================================================================
    // Configuration toggles (subclass enables via helper methods)
    // ================================================================

    /** If set, the mob will add a TemptGoal (follow the item) */
    protected Ingredient temptItem = null;

    /** If true, the mob will add BreedGoal + FollowParentGoal */
    protected boolean canBreed = false;

    // ================================================================
    // Aggression / retaliation
    // ================================================================

    /**
     * How long this mob should stay "angry" (in ticks).
     * While > 0, we keep target + aggressive state and do angry-looking-at-target.
     * When it hits 0, we clear target + aggression and cancel attack state.
     */
    protected int angerTime = 0;

    // ================================================================
    // Timed attack system (animation-driven attacks)
    // ================================================================

    /**
     * The target that will actually receive damage when the "hit moment" arrives.
     * We store this when the AI decides to attack, then apply damage later in aiStep().
     */
    protected LivingEntity queuedAttackTarget = null;

    /** Server-side countdown until the hit moment (ticks). When it reaches 0, we deal damage. */
    protected int attackTicksUntilHit = -1;

    /**
     * Server-side countdown for how long the attack animation is considered active.
     * Used to prevent restarting attacks while already mid-swing.
     */
    protected int attackAnimTicksRemaining = 0;

    // ================================================================
    // Home position (for "stay within radius" behavior)
    // ================================================================

    /**
     * If the species has "stayWithinHomeRadius", we store the first position as a home center.
     * Wander + sleep search goals can then use this center to keep the mob near home.
     */
    protected BlockPos homePos = null;

    // ================================================================
    // Synced data (client visuals)
    // ================================================================

    /**
     * Synced sleeping flag.
     * - Written server-side (tickSleepServer, beginSleepingFromGoal)
     * - Read client-side for animations / visuals
     */
    private static final EntityDataAccessor<Boolean> DATA_SLEEPING =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    /** Synced integer representing the current movement intent */
    private static final EntityDataAccessor<Integer> DATA_MOVE_MODE =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.INT);

    /**
     * This is *visual* anger (used for overlays, faces, animation layers).
     * It mirrors server aggression state in a client-friendly synced boolean.
     */
    private static final EntityDataAccessor<Boolean> DATA_VISUALLY_ANGRY =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    // ================================================================
    // Sleep system (server state)
    // ================================================================

    /**
     * Server-only: how many ticks of sleep remain.
     * Decrements each tick while sleeping; when it reaches 0 we wake up.
     */
    private int sleepTicksRemaining = 0;

    /**
     * Not synced. This is purely a server-side "internal" flag used to prevent
     * re-rolling sleep chance while we are already searching for a roofed spot.
     *
     * - Set by CatoSleepSearchGoal.start()
     * - Cleared by CatoSleepSearchGoal.stop()
     */
    private boolean sleepSearching = false;

    /**
     * Server-side cooldown timer for sleep searching.
     * Prevents the mob from attempting a roof-search every single tick if there is no roof nearby.
     */
    private long sleepSearchCooldownUntil = 0L;

    // ================================================================
    // Sleep attempt pacing (server-only)
    // ================================================================

    /** Next tick at which we are allowed to roll "start sleeping" (attempt-based, not per-tick). */
    private long nextSleepAttemptTick = 0L;

    // ================================================================
    // Move mode (authoritative animation intent)
    // ================================================================

    /**
     * These are NOT Minecraft movement speeds; they are animation intent/state.
     * Goals set this state so the client can always pick the correct animation.
     */
    public static final int MOVE_IDLE = 0;
    public static final int MOVE_WALK = 1;
    public static final int MOVE_RUN  = 2;

    // ================================================================
    // Construction & required overrides
    // ================================================================

    protected CatoBaseMob(EntityType<? extends Animal> type, Level level) {
        super(type, level);
    }

    /**
     * Each concrete mob must provide its species info.
     * This record holds movement type, temperament, stats, sleep config, wander config, etc.
     */
    protected abstract CatoMobSpeciesInfo getSpeciesInfo();

    /**
     * Goal priority profile:
     * - central place to tune goal priorities and enable/disable entire groups of goals.
     * Subclasses can override this to provide different priorities per mob.
     */
    protected CatoGoalPriorityProfile getGoalPriorities() {
        return CatoGoalPriorityProfile.defaults();
    }

    // ================================================================
    // Synced data registration
    // ================================================================

    /**
     * Register synced data fields.
     * Runs on both sides; initial values must be provided here.
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOVE_MODE, MOVE_IDLE);
        builder.define(DATA_VISUALLY_ANGRY, false);
        builder.define(DATA_SLEEPING, false);
    }

    // ================================================================
    // Simple state accessors (synced + local)
    // ================================================================

    /** @return true if server says we're sleeping (synced to client for visuals) */
    public boolean isSleeping() {
        return this.entityData.get(DATA_SLEEPING);
    }

    /** Server-side setter for sleep flag */
    protected void setSleeping(boolean sleeping) {
        this.entityData.set(DATA_SLEEPING, sleeping);
    }

    public boolean isSleepSearching() { return sleepSearching; }
    public void setSleepSearching(boolean searching) { this.sleepSearching = searching; }

    /** @return current synced move mode (IDLE/WALK/RUN) */
    public int getMoveMode() {
        return this.entityData.get(DATA_MOVE_MODE);
    }

    /**
     * Server-side setter for move mode (synced to clients).
     * Typically called by goals like CatoWanderGoal and CatoMeleeAttackGoal.
     */
    protected void setMoveMode(int mode) {
        this.entityData.set(DATA_MOVE_MODE, mode);
    }

    /** @return true if the client should show angry visuals */
    public boolean isVisuallyAngry() {
        return this.entityData.get(DATA_VISUALLY_ANGRY);
    }

    /** Server-side setter; clients read to display overlays/animations */
    protected void setVisuallyAngry(boolean angry) {
        this.entityData.set(DATA_VISUALLY_ANGRY, angry);
    }

    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos homePos) { this.homePos = homePos; }

    /** Convenience so goals don't have to touch species info directly. */
    public boolean shouldStayWithinHomeRadius() { return getSpeciesInfo().stayWithinHomeRadius(); }
    public double getHomeRadius() { return getSpeciesInfo().homeRadius(); }

    // ================================================================
    // Configuration helpers (called by subclass constructors)
    // ================================================================

    /** Enables temptation behavior (adds TemptGoal in registerGoals if priorities allow it) */
    protected void setTemptItem(Ingredient ingredient) {
        this.temptItem = ingredient;
    }

    /** Enables breeding behavior (adds BreedGoal + FollowParentGoal if priorities allow it) */
    protected void enableBreeding() {
        this.canBreed = true;
    }

    // ================================================================
    // Animation / behavior hooks (subclasses override if they want)
    // ================================================================

    protected void onAttackAnimationStart(LivingEntity target) { }
    protected void onAttackAnimationEnd() { }

    protected void onWanderStart(boolean running) { }
    protected void onWanderStop() { }

    protected void onChaseStart(LivingEntity target) { }
    protected void onChaseTick(LivingEntity target, boolean isMoving) { }
    protected void onChaseStop() { }

    // ================================================================
    // Sleep search cooldown
    // ================================================================

    public boolean isSleepSearchOnCooldown() {
        return !this.level().isClientSide && this.level().getGameTime() < sleepSearchCooldownUntil;
    }

    public void startSleepSearchCooldown(int ticks) {
        sleepSearchCooldownUntil = this.level().getGameTime() + Math.max(0, ticks);
    }

    // ================================================================
    // Sleep helpers (wake/start via search)
    // ================================================================

    /** Utility: instantly wake (clears flag and timer) */
    protected void wakeUp() {
        if (isSleeping()) {
            setSleeping(false);
            sleepTicksRemaining = 0;

            // Enforce cooldown before next sleep attempt
            nextSleepAttemptTick =
                    this.level().getGameTime() + sleepAttemptIntervalTicks();
        }
    }

    protected void beginSleepingFromGoal() {
        if (this.isSleeping()) return;

        CatoMobSpeciesInfo info = getSpeciesInfo();
        if (!info.sleepEnabled()) return;
        if (this.getTarget() != null || this.isAggressive()) return;

        clearSleepDesire(); // ✅ add this

        setSleeping(true);

        nextSleepAttemptTick = 0L;

        int min = Math.max(1, info.sleepMinTicks());
        int max = Math.max(min, info.sleepMaxTicks());
        int range = max - min + 1;

        this.sleepTicksRemaining = min + this.getRandom().nextInt(range);
        this.getNavigation().stop();
    }

    // If the allowed sleep time window flips (day<->night), we don't insta-wake.
    // We start a short grace timer so waking feels natural.
    private int timeWindowWakeGraceTicks = 0;

    // ================================================================
    // Sleep attempt pacing (server-only)
    // ================================================================

    protected int sleepAttemptIntervalTicks() {
        int v = getSpeciesInfo().sleepAttemptIntervalTicks();
        return Math.max(1, v);
    }

    protected float sleepAttemptChance() {
        float v = getSpeciesInfo().sleepAttemptChance();
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        return v;
    }

    protected boolean rollSleepAttempt() {
        if (this.level().isClientSide) return false;

        long now = this.level().getGameTime();
        if (now < nextSleepAttemptTick) return false;

        nextSleepAttemptTick = now + sleepAttemptIntervalTicks();
        return this.getRandom().nextFloat() < sleepAttemptChance();
    }

    // ================================================================
    // Timed attack entry point + cleanup
    // ================================================================

    public boolean startTimedAttack(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        if (this.isSleeping()) {
            return false;
        }

        if (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0 || this.queuedAttackTarget != null) {
            return false;
        }

        CatoMobSpeciesInfo info = getSpeciesInfo();

        double triggerRange = info.attackTriggerRange();
        if (triggerRange > 0.0D) {
            double maxTriggerDistSqr = triggerRange * triggerRange;
            if (this.distanceToSqr(target) > maxTriggerDistSqr) {
                return false;
            }
        }

        this.queuedAttackTarget = target;

        this.attackAnimTicksRemaining = info.attackAnimTotalTicks();
        this.attackTicksUntilHit = info.attackHitDelayTicks();

        this.onAttackAnimationStart(target);

        return true;
    }

    protected void clearAttackState() {
        this.attackTicksUntilHit = -1;
        this.attackAnimTicksRemaining = 0;
        this.queuedAttackTarget = null;
        this.onAttackAnimationEnd();
    }

    // ================================================================
    // Goal registration (AI wiring)
    // ================================================================

    @Override
    protected void registerGoals() {
        CatoMobSpeciesInfo speciesInfo = getSpeciesInfo();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        this.goalSelector.addGoal(prio.floatGoal, new FloatGoal(this));

        if (prio.enableSleep && speciesInfo.sleepEnabled()) {
            this.goalSelector.addGoal(prio.sleepLock, new CatoSleepGoal(this));

            if (prio.enableSleepSearch) {
                this.goalSelector.addGoal(prio.sleepSearch, new CatoSleepSearchGoal(this));
            }
        }

        if (prio.enableLookGoals) {
            this.goalSelector.addGoal(prio.lookAtPlayer,
                    new LookAtPlayerGoal(this, Player.class, 8.0F, 0.1F));
            this.goalSelector.addGoal(prio.randomLook, new RandomLookAroundGoal(this));
        }

        if (prio.enableTempt && temptItem != null) {
            this.goalSelector.addGoal(prio.tempt,
                    new TemptGoal(this, 1.2D, temptItem, false));
        }

        switch (speciesInfo.temperament()) {
            case PASSIVE -> setupPassiveGoals();
            case NEUTRAL_RETALIATE_SHORT, NEUTRAL_RETALIATE_LONG -> setupNeutralGoals();
            case HOSTILE -> setupHostileGoals();
        }

        if (prio.enableWander) {
            switch (speciesInfo.movementType()) {
                case LAND -> setupLandGoals();
                case FLYING -> setupFlyingGoals();
                case HOVERING -> setupHoveringGoals();
                case SURFACE_SWIM -> setupSurfaceSwimGoals();
                case UNDERWATER -> setupUnderwaterGoals();
            }
        }

        if (prio.enableBreeding && canBreed) {
            this.goalSelector.addGoal(prio.breed, new BreedGoal(this, 1.0D));
            this.goalSelector.addGoal(prio.followParent, new FollowParentGoal(this, 1.1D));
        }
    }

    protected void setupLandGoals() {
        CatoMobSpeciesInfo info = getSpeciesInfo();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        this.goalSelector.addGoal(
                prio.wander,
                new CatoWanderGoal(
                        this,
                        info.wanderWalkSpeed(),
                        info.wanderRunSpeed(),
                        info.wanderRunChance(),
                        info.wanderMinRadius(),
                        info.wanderMaxRadius(),
                        info.wanderRunDistanceThreshold()
                )
        );
    }

    protected void setupFlyingGoals() { this.goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0D)); }
    protected void setupHoveringGoals() { this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.7D)); }
    protected void setupSurfaceSwimGoals() { this.goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0D)); }
    protected void setupUnderwaterGoals() { this.goalSelector.addGoal(5, new RandomSwimmingGoal(this, 1.0D, 10)); }

    protected void setupPassiveGoals() { }

    protected void setupNeutralGoals() {
        int cooldown = getSpeciesInfo().attackCooldownTicks();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        this.goalSelector.addGoal(
                prio.meleeAttack,
                new CatoMeleeAttackGoal(this, 1.1D, true, cooldown)
        );
    }

    protected void setupHostileGoals() {
        int cooldown = getSpeciesInfo().attackCooldownTicks();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        this.targetSelector.addGoal(prio.targetHurtBy, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(prio.targetNearestPlayer,
                new NearestAttackableTargetGoal<>(this, Player.class, true));

        this.goalSelector.addGoal(
                prio.meleeAttack,
                new CatoMeleeAttackGoal(this, 1.2D, false, cooldown)
        );
    }

    // ================================================================
    // Retaliation hook: get angry when hurt
    // ================================================================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && getSpeciesInfo().wakeOnDamage()) {
            wakeUp();
        }

        boolean result = super.hurt(source, amount);

        if (!level().isClientSide && result) {
            CatoMobTemperament temperament = getSpeciesInfo().temperament();

            if (source.getEntity() instanceof LivingEntity attacker) {
                switch (temperament) {
                    case NEUTRAL_RETALIATE_SHORT -> {
                        this.angerTime = 180;
                        this.setTarget(attacker);
                        this.setLastHurtByMob(attacker);
                        this.setAggressive(true);
                    }
                    case NEUTRAL_RETALIATE_LONG -> {
                        this.angerTime = 400;
                        this.setTarget(attacker);
                        this.setLastHurtByMob(attacker);
                        this.setAggressive(true);
                    }
                    case HOSTILE -> {
                        this.angerTime = 800;
                        this.setTarget(attacker);
                        this.setLastHurtByMob(attacker);
                        this.setAggressive(true);
                    }
                    case PASSIVE -> { }
                }
            }
        }

        return result;
    }

// ================================================================
// Sleep ticking (server-only behavior state machine)
// ================================================================

    protected void tickSleepServer() {
        CatoMobSpeciesInfo info = getSpeciesInfo();

        if (!info.sleepEnabled()) {
            clearSleepDesire();
            if (isSleeping()) {
                setSleeping(false);
                sleepTicksRemaining = 0;
                timeWindowWakeGraceTicks = 0;
            }
            return;
        }

        // ============================================================
        // Currently sleeping -> maintain / wake checks
        // ============================================================
        if (isSleeping()) {
            if (sleepTicksRemaining > 0) sleepTicksRemaining--;

            boolean shouldWake = false;

            if (this.getTarget() != null || this.isAggressive()) shouldWake = true;
            if (!this.getNavigation().isDone()) shouldWake = true;
            if (info.wakeOnAir() && !this.onGround()) shouldWake = true;

            if (info.wakeOnTouchingWater() && this.isInWater()) {
                if (!info.sleepAllowedOnWaterSurface()) {
                    shouldWake = true;
                }
            }

            if (info.wakeOnUnderwater() && this.isUnderWater()) {
                shouldWake = true;
            }

            if (info.wakeOnSunlight()) {
                if (this.level().isDay() && this.level().canSeeSky(this.blockPosition())) {
                    shouldWake = true;
                }
            }

            // ------------------------------------------------------------
            // Time window changed (day/night flips) -> grace nap then wake
            // ------------------------------------------------------------
            boolean isDayNow = this.level().isDay();
            boolean allowedTimeNow = (isDayNow && info.sleepAtDay()) || (!isDayNow && info.sleepAtNight());

            if (!shouldWake) {
                if (!allowedTimeNow) {
                    if (timeWindowWakeGraceTicks <= 0) {
                        int gMin = Math.max(1, info.sleepTimeWindowWakeGraceMinTicks());
                        int gMax = Math.max(gMin, info.sleepTimeWindowWakeGraceMaxTicks());
                        timeWindowWakeGraceTicks = gMin + this.getRandom().nextInt(gMax - gMin + 1);
                    } else {
                        timeWindowWakeGraceTicks--;
                        if (timeWindowWakeGraceTicks <= 0) {
                            shouldWake = true;
                        }
                    }
                } else {
                    timeWindowWakeGraceTicks = 0;
                }
            }

            if (!shouldWake && sleepTicksRemaining <= 0) {
                float c = info.sleepContinueChance();
                if (c < 0f) c = 0f;
                if (c > 1f) c = 1f;

                if (this.getRandom().nextFloat() < c) {
                    int min = Math.max(1, info.sleepMinTicks());
                    int max = Math.max(min, info.sleepMaxTicks());
                    sleepTicksRemaining = min + this.getRandom().nextInt(max - min + 1);
                    return;
                } else {
                    shouldWake = true;
                }
            }

            if (shouldWake) {
                setSleeping(false);
                sleepTicksRemaining = 0;
                timeWindowWakeGraceTicks = 0;
                clearSleepDesire();

                nextSleepAttemptTick = this.level().getGameTime() + sleepAttemptIntervalTicks();
            }

            return;
        }

        // ============================================================
        // Not currently sleeping -> decide / search / sleep
        // ============================================================

        if (this.isSleepSearching()) return;
        if (this.getTarget() != null || this.isAggressive()) return;

        // Can't sleep if in bad physical state (unless surface-sleep is allowed)
        if (!this.onGround() && !info.sleepAllowedOnWaterSurface()) return;
        if (this.isInWater() && !info.sleepAllowedOnWaterSurface()) return;

        // Surface-sleepers should not sleep while fully underwater
        if (this.isUnderWater() && info.sleepAllowedOnWaterSurface()) return;

        boolean isDay = this.level().isDay();
        boolean allowedTime = (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());
        if (!allowedTime) return;

        // ------------------------------------------------------------
        // Single authoritative "want to sleep" decision:
        // - If we already want to sleep (desire window), don't re-roll.
        // - Otherwise roll once, and if success, open a short desire window.
        // ------------------------------------------------------------
        boolean wantsToSleep = (sleepDesireTicks > 0) || rollSleepAttempt();
        if (!wantsToSleep) return;

        // Keep desire alive briefly so SleepSearchGoal has time to start/path
        int desireWindow = Math.max(1, info.sleepDesireWindowTicks());
        sleepDesireTicks = Math.max(sleepDesireTicks, desireWindow);

        // If roof required but not roofed here -> wait for SleepSearchGoal
        if (info.sleepRequiresRoof()) {
            int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
            if (!this.isRoofed(this.blockPosition(), roofMax)) {
                return; // desire remains, search goal canUse() should pick it up
            }
        }

        // If we got here: roof not required OR already roofed -> sleep in place
        this.getNavigation().stop();
        this.setDeltaMovement(this.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));

        setSleeping(true);
        sleepDesireTicks = 0;
        nextSleepAttemptTick = 0L;
        timeWindowWakeGraceTicks = 0;

        int min = Math.max(1, info.sleepMinTicks());
        int max = Math.max(min, info.sleepMaxTicks());
        sleepTicksRemaining = min + this.getRandom().nextInt(max - min + 1);
    }

    /** @return distance in blocks to the first non-air, non-fluid block above pos; -1 if none within maxHeight */
    protected int roofDistance(BlockPos pos, int maxHeight) {
        Level level = this.level();
        for (int dy = 1; dy <= maxHeight; dy++) {
            BlockPos check = pos.above(dy);
            var state = level.getBlockState(check);

            // Ignore air and fluids as "roof"
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return dy;
            }
        }
        return -1;
    }

    protected boolean isRoofed(BlockPos pos, int maxHeight) {
        return roofDistance(pos, maxHeight) != -1;
    }

    // When > 0, the mob has decided it wants to sleep and will allow SleepSearchGoal to run.
    private int sleepDesireTicks = 0;

    public boolean wantsToSleepNow() { return sleepDesireTicks > 0; }

    protected void clearSleepDesire() {
        this.sleepDesireTicks = 0;
    }

    // ================================================================
    // Sleep spot memory (server-only): ring buffer + strike system
    // ================================================================

    private static final int MAX_SLEEP_SPOT_MEMORY = 6; // N=3–8 recommended
    private static final int MAX_SLEEP_SPOT_STRIKES = 2;

    private final java.util.Deque<SleepSpotMemory> rememberedSleepSpots =
            new java.util.ArrayDeque<>();

    public static final class SleepSpotMemory {
        public final BlockPos pos;
        public int strikes;

        public SleepSpotMemory(BlockPos pos) {
            this.pos = pos.immutable();
            this.strikes = 0;
        }

        public void addStrike() { strikes++; }

        public boolean isDead(CatoMobSpeciesInfo info) {
            return strikes >= Math.max(1, info.sleepSpotMemoryMaxStrikes());
        }
    }

    /** Remember a successful sleep spot (most-recent goes to the end). */
    public void rememberSleepSpot(BlockPos pos) {
        if (this.level().isClientSide) return;
        if (pos == null) return;

        int max = Math.max(0, getSpeciesInfo().sleepSpotMemorySize());
        if (max <= 0) return;

        BlockPos p = pos.immutable();

        // If already present: reset strikes and move to the end (most-recent)
        for (var it = rememberedSleepSpots.iterator(); it.hasNext(); ) {
            SleepSpotMemory mem = it.next();
            if (mem.pos.equals(p)) {
                it.remove();
                mem.strikes = 0;
                rememberedSleepSpots.addLast(mem);
                return;
            }
        }

        // Enforce ring buffer size (species-defined)
        while (rememberedSleepSpots.size() >= max) {
            rememberedSleepSpots.removeFirst();
        }

        rememberedSleepSpots.addLast(new SleepSpotMemory(p));
    }

    /** Returns a stable snapshot list (FIFO order: oldest -> newest). */
    public java.util.List<SleepSpotMemory> getRememberedSleepSpots() {
        return java.util.List.copyOf(rememberedSleepSpots);
    }

    /** Add a strike on failure; delete only after MAX_SLEEP_SPOT_STRIKES. */
    public void strikeSleepSpot(BlockPos pos) {
        if (pos == null) return;

        for (var it = rememberedSleepSpots.iterator(); it.hasNext(); ) {
            SleepSpotMemory mem = it.next();
            BlockPos p = pos.immutable();
            if (mem.pos.equals(p)) {
                mem.addStrike();
                if (mem.isDead(getSpeciesInfo())) {
                    it.remove();
                }
                return;
            }
        }
    }

    // ================================================================
    // Main AI step (server-side behavior + timed attack damage)
    // ================================================================

    @Override
    public void aiStep() {
        super.aiStep();

        if (!level().isClientSide) {

            // ------------------------------------------------------------
            // NEW: decrement sleep desire timer (server-only)
            // ------------------------------------------------------------
            if (sleepDesireTicks > 0) {
                sleepDesireTicks--;
            }

            if (this.homePos == null && this.shouldStayWithinHomeRadius()) {
                this.homePos = this.blockPosition();
            }

            tickSleepServer();

            if (this.isSleeping()) {
                clearAttackState();
                this.setTarget(null);
                this.setLastHurtByMob(null);
                this.setAggressive(false);
                return;
            }

            if (angerTime > 0) {
                angerTime--;
            } else if (getTarget() != null) {
                this.setTarget(null);
                this.setAggressive(false);
                this.setLastHurtByMob(null);
                clearAttackState();
            }

            boolean angryNow = (this.angerTime > 0 && this.getTarget() != null);
            this.setVisuallyAngry(angryNow);

            if (this.getTarget() == null && (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0)) {
                clearAttackState();
            }

            if (this.attackTicksUntilHit > 0) {
                this.attackTicksUntilHit--;

                if (this.attackTicksUntilHit == 0) {
                    if (this.queuedAttackTarget != null && this.queuedAttackTarget.isAlive()) {
                        double hitRange = getSpeciesInfo().attackHitRange();
                        if (hitRange <= 0.0D) hitRange = 2.0D;

                        double maxHitDistSqr = hitRange * hitRange;

                        if (this.distanceToSqr(this.queuedAttackTarget) <= maxHitDistSqr) {
                            this.doHurtTarget(this.queuedAttackTarget);
                        }
                    }

                    this.queuedAttackTarget = null;
                }
            }

            if (this.attackAnimTicksRemaining > 0) {
                this.attackAnimTicksRemaining--;

                if (this.attackAnimTicksRemaining == 0) {
                    this.onAttackAnimationEnd();
                }
            }

            if (this.angerTime > 0 && this.getTarget() != null) {
                int maxYaw = this.getMaxHeadYRot();
                int maxPitch = this.getMaxHeadXRot();

                if (maxYaw != 0 || maxPitch != 0) {
                    this.getLookControl().setLookAt(this.getTarget(), (float) maxYaw, (float) maxPitch);
                }
            }
        }
    }

    // ================================================================
    // Shared attribute factory (used when registering entity attributes)
    // ================================================================

    public static AttributeSupplier.Builder createAttributesFor(CatoMobSpeciesInfo info) {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, info.maxHealth())
                .add(Attributes.ATTACK_DAMAGE, info.attackDamage())
                .add(Attributes.MOVEMENT_SPEED, info.movementSpeed())
                .add(Attributes.FOLLOW_RANGE, info.followRange())
                .add(Attributes.GRAVITY, info.gravity());
    }

    // ================================================================
    // Food / breeding (base stubs; subclasses usually override)
    // ================================================================

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }
}