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
    // "Knobs" a subclass can enable/disable by calling helper methods
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
    // Sleep system (base implementation)
    // ================================================================

    /**
     * Synced sleeping flag.
     * - Written server-side (tickSleepServer, beginSleepingFromGoal)
     * - Read client-side for animations / visuals
     */
    private static final EntityDataAccessor<Boolean> DATA_SLEEPING =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    /**
     * Server-only: how many ticks of sleep remain.
     * Decrements each tick while sleeping; when it reaches 0 we wake up.
     */
    private int sleepTicksRemaining = 0;

    /** @return true if server says we're sleeping (synced to client for visuals) */
    public boolean isSleeping() {
        return this.entityData.get(DATA_SLEEPING);
    }

    /** Server-side setter for sleep flag */
    protected void setSleeping(boolean sleeping) {
        this.entityData.set(DATA_SLEEPING, sleeping);
    }

    /** Utility: instantly wake (clears flag and timer) */
    protected void wakeUp() {
        if (isSleeping()) {
            setSleeping(false);
            sleepTicksRemaining = 0;
        }
    }

    /**
     * Not synced. This is purely a server-side "internal" flag used to prevent
     * re-rolling sleep chance while we are already searching for a roofed spot.
     *
     * - Set by CatoSleepSearchGoal.start()
     * - Cleared by CatoSleepSearchGoal.stop()
     */
    private boolean sleepSearching = false;

    public boolean isSleepSearching() { return sleepSearching; }
    public void setSleepSearching(boolean searching) { this.sleepSearching = searching; }

    /**
     * Server-side cooldown timer for sleep searching.
     * Prevents the mob from attempting a roof-search every single tick if there is no roof nearby.
     */
    private long sleepSearchCooldownUntil = 0L;

    /**
     * @return true if we are on cooldown and should not attempt to start sleep search.
     * Note: server-side only check; client should not be deciding AI.
     */
    public boolean isSleepSearchOnCooldown() {
        return !this.level().isClientSide && this.level().getGameTime() < sleepSearchCooldownUntil;
    }

    /** Starts/extends the sleep-search cooldown by N ticks (server-side). */
    public void startSleepSearchCooldown(int ticks) {
        sleepSearchCooldownUntil = this.level().getGameTime() + Math.max(0, ticks);
    }

    /**
     * Called by CatoSleepSearchGoal once it reaches a valid roofed spot.
     * This method actually turns on sleep and picks a duration.
     */
    protected void beginSleepingFromGoal() {
        // Already sleeping? Do nothing.
        if (this.isSleeping()) return;

        CatoMobSpeciesInfo info = getSpeciesInfo();

        // Safety: sleeping must still be allowed and we must still be calm
        if (!info.sleepEnabled()) return;
        if (this.getTarget() != null || this.isAggressive()) return;

        // Turn on sleeping (synced flag) and pick a random duration within [min..max]
        setSleeping(true);

        int min = Math.max(1, info.sleepMinTicks());
        int max = Math.max(min, info.sleepMaxTicks());
        int range = max - min + 1;

        this.sleepTicksRemaining = min + this.getRandom().nextInt(range);

        // Stop moving immediately
        this.getNavigation().stop();
    }

    // ================================================================
    // Construction
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
    // Synced move mode (authoritative "WALK/RUN/IDLE" state)
    // ================================================================

    /**
     * These are NOT Minecraft movement speeds; they are animation intent/state.
     * Goals set this state so the client can always pick the correct animation.
     */
    public static final int MOVE_IDLE = 0;
    public static final int MOVE_WALK = 1;
    public static final int MOVE_RUN  = 2;

    /** Synced integer representing the current movement intent */
    private static final EntityDataAccessor<Integer> DATA_MOVE_MODE =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.INT);

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

    /**
     * Hook called when a timed attack begins.
     * Default: no-op.
     * Example usage: Geckolib entity sets a synced "attacking" boolean for animation.
     */
    protected void onAttackAnimationStart(LivingEntity target) {
        // default: nothing
    }

    /**
     * Hook called when a timed attack ends/cancels (timers reach 0 or state is cleared).
     * Default: no-op.
     */
    protected void onAttackAnimationEnd() {
        // default: nothing
    }

    /**
     * Called by CatoWanderGoal when a wander starts.
     * @param running whether this wander decided to run instead of walk
     */
    protected void onWanderStart(boolean running) {
        // default: do nothing
    }

    /** Called by CatoWanderGoal when a wander stops. */
    protected void onWanderStop() {
        // default: do nothing
    }

    // ================================================================
    // Timed attack entry point (called by CatoMeleeAttackGoal)
    // ================================================================

    /**
     * Starts a timed attack:
     * - verifies target is valid
     * - checks not sleeping
     * - checks not already mid-attack
     * - checks "attackTriggerRange" (start range)
     * - sets timers and queues a target for later damage
     * - calls animation hook so subclasses can start playing attack animation
     *
     * Damage is NOT dealt here; it's dealt later in aiStep() when attackTicksUntilHit hits 0.
     */
    public boolean startTimedAttack(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        // Don't start attacks while sleeping
        if (this.isSleeping()) {
            return false;
        }

        // Already mid-swing? Don’t start a new one.
        if (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0 || this.queuedAttackTarget != null) {
            return false;
        }

        CatoMobSpeciesInfo info = getSpeciesInfo();

        // Trigger range (distance at which attack can *start*)
        double triggerRange = info.attackTriggerRange();
        if (triggerRange > 0.0D) {
            double maxTriggerDistSqr = triggerRange * triggerRange;
            if (this.distanceToSqr(target) > maxTriggerDistSqr) {
                return false;
            }
        }

        // Remember target for later damage application
        this.queuedAttackTarget = target;

        // Configure timers from species info
        this.attackAnimTicksRemaining = info.attackAnimTotalTicks();
        this.attackTicksUntilHit = info.attackHitDelayTicks();

        // Let subclass start attack animation flags, etc.
        this.onAttackAnimationStart(target);

        return true;
    }

    // ================================================================
    // Synced "visual angry" flag (client visuals only)
    // ================================================================

    /**
     * This is *visual* anger (used for overlays, faces, animation layers).
     * It mirrors server aggression state in a client-friendly synced boolean.
     */
    private static final EntityDataAccessor<Boolean> DATA_VISUALLY_ANGRY =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

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

    /** @return true if the client should show angry visuals */
    public boolean isVisuallyAngry() {
        return this.entityData.get(DATA_VISUALLY_ANGRY);
    }

    /** Server-side setter; clients read to display overlays/animations */
    protected void setVisuallyAngry(boolean angry) {
        this.entityData.set(DATA_VISUALLY_ANGRY, angry);
    }

    /**
     * Clears all timed-attack state and notifies subclasses so they can stop attack animation.
     * Called when:
     * - sleeping starts
     * - target is lost
     * - anger ends
     * - attack timers complete, etc.
     */
    protected void clearAttackState() {
        this.attackTicksUntilHit = -1;
        this.attackAnimTicksRemaining = 0;
        this.queuedAttackTarget = null;
        this.onAttackAnimationEnd();
    }

    // ================================================================
    // Home helpers
    // ================================================================

    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos homePos) { this.homePos = homePos; }

    /** Convenience so goals don't have to touch species info directly. */
    public boolean shouldStayWithinHomeRadius() { return getSpeciesInfo().stayWithinHomeRadius(); }
    public double getHomeRadius() { return getSpeciesInfo().homeRadius(); }

    // ================================================================
    // Goal registration (the AI "brain wiring")
    // ================================================================

    /**
     * This is where all AI goals get registered (goalSelector + targetSelector).
     * It uses:
     * - species info (what the mob *is*)
     * - priority profile (how goals should be ordered + what groups are enabled)
     */
    @Override
    protected void registerGoals() {
        CatoMobSpeciesInfo speciesInfo = getSpeciesInfo();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        // ------------------------------------------------------------
        // Core survival basics
        // ------------------------------------------------------------
        this.goalSelector.addGoal(prio.floatGoal, new FloatGoal(this));

        // ------------------------------------------------------------
        // Sleep system:
        // - CatoSleepGoal locks movement/rotation while sleeping
        // - CatoSleepSearchGoal optionally searches for a roofed spot if required
        // ------------------------------------------------------------
        if (prio.enableSleep && speciesInfo.sleepEnabled()) {
            this.goalSelector.addGoal(prio.sleepLock, new CatoSleepGoal(this));

            if (prio.enableSleepSearch) {
                this.goalSelector.addGoal(prio.sleepSearch, new CatoSleepSearchGoal(this));
            }
        }

        // ------------------------------------------------------------
        // Ambient look behavior (idle "life")
        // ------------------------------------------------------------
        if (prio.enableLookGoals) {
            this.goalSelector.addGoal(prio.lookAtPlayer,
                    new LookAtPlayerGoal(this, Player.class, 8.0F, 0.1F));
            this.goalSelector.addGoal(prio.randomLook, new RandomLookAroundGoal(this));
        }

        // ------------------------------------------------------------
        // Interaction: temptation (follow items)
        // Only added if subclass provided a tempt item and the profile enables it.
        // ------------------------------------------------------------
        if (prio.enableTempt && temptItem != null) {
            this.goalSelector.addGoal(prio.tempt,
                    new TemptGoal(this, 1.2D, temptItem, false));
        }

        // ------------------------------------------------------------
        // Combat setup depends on temperament
        // (passive: no attack, neutral: retaliate, hostile: target players)
        // ------------------------------------------------------------
        switch (speciesInfo.temperament()) {
            case PASSIVE -> setupPassiveGoals();
            case NEUTRAL_RETALIATE_SHORT, NEUTRAL_RETALIATE_LONG -> setupNeutralGoals();
            case HOSTILE -> setupHostileGoals();
        }

        // ------------------------------------------------------------
        // Movement goals (wander, swim, fly) depending on movement type
        // ------------------------------------------------------------
        if (prio.enableWander) {
            switch (speciesInfo.movementType()) {
                case LAND -> setupLandGoals();
                case FLYING -> setupFlyingGoals();
                case HOVERING -> setupHoveringGoals();
                case SURFACE_SWIM -> setupSurfaceSwimGoals();
                case UNDERWATER -> setupUnderwaterGoals();
            }
        }

        // ------------------------------------------------------------
        // Breeding goals (only if subclass enabled breeding)
        // ------------------------------------------------------------
        if (prio.enableBreeding && canBreed) {
            this.goalSelector.addGoal(prio.breed, new BreedGoal(this, 1.0D));
            this.goalSelector.addGoal(prio.followParent, new FollowParentGoal(this, 1.1D));
        }
    }

    /**
     * LAND movement behavior:
     * Uses the custom CatoWanderGoal so we can:
     * - honor home radius
     * - pick walk vs run
     * - sync MOVE_MODE to the client for animation correctness
     */
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

    // Placeholder default movement for other movement types (can be replaced with custom goals later)
    protected void setupFlyingGoals() { this.goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0D)); }
    protected void setupHoveringGoals() { this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.7D)); }
    protected void setupSurfaceSwimGoals() { this.goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0D)); }
    protected void setupUnderwaterGoals() { this.goalSelector.addGoal(5, new RandomSwimmingGoal(this, 1.0D, 10)); }

    // ================================================================
    // Temperament presets (combat wiring)
    // ================================================================

    /** PASSIVE: no attack goals at all */
    protected void setupPassiveGoals() {
        // no attack goals
    }

    /**
     * NEUTRAL: retaliate when hurt (handled in hurt()), then use melee goal while angry.
     * Note: target selection is handled manually in hurt() for neutral mobs.
     */
    protected void setupNeutralGoals() {
        int cooldown = getSpeciesInfo().attackCooldownTicks();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        this.goalSelector.addGoal(
                prio.meleeAttack,
                new CatoMeleeAttackGoal(this, 1.1D, true, cooldown)
        );
    }

    /**
     * HOSTILE: has target selector goals + melee attack goal.
     * - HurtByTargetGoal: fights back
     * - NearestAttackableTargetGoal: seeks players
     */
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

    /**
     * When hurt:
     * - optionally wake up immediately (species setting)
     * - for neutral/hostile temperaments, set angerTime + setTarget(attacker)
     * - this causes melee goal to run and the mob to attack for some duration
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Wake instantly on damage (only if species wants that)
        if (!this.level().isClientSide && getSpeciesInfo().wakeOnDamage()) {
            wakeUp();
        }

        boolean result = super.hurt(source, amount);

        if (!level().isClientSide && result) {
            CatoMobTemperament temperament = getSpeciesInfo().temperament();

            // If we were attacked by a living entity, we may retaliate depending on temperament.
            if (source.getEntity() instanceof LivingEntity attacker) {
                switch (temperament) {
                    case NEUTRAL_RETALIATE_SHORT -> {
                        this.angerTime = 180; // 9 seconds at 20 TPS
                        this.setTarget(attacker);
                        this.setLastHurtByMob(attacker);
                        this.setAggressive(true);
                    }
                    case NEUTRAL_RETALIATE_LONG -> {
                        this.angerTime = 400; // 20 seconds
                        this.setTarget(attacker);
                        this.setLastHurtByMob(attacker);
                        this.setAggressive(true);
                    }
                    case HOSTILE -> {
                        this.angerTime = 400; // hostile stays angry similarly
                        this.setTarget(attacker);
                        this.setLastHurtByMob(attacker);
                        this.setAggressive(true);
                    }
                    case PASSIVE -> {
                        // ignore (no retaliation)
                    }
                }
            }
        }

        return result;
    }

    // ================================================================
    // Chase hooks (called by CatoMeleeAttackGoal)
    // ================================================================

    /** Called when we start chasing a target (subclasses can set animation flags etc.) */
    protected void onChaseStart(LivingEntity target) {
        // default: do nothing
    }

    /** Called every tick while chasing (subclasses can react to moving vs standing) */
    protected void onChaseTick(LivingEntity target, boolean isMoving) {
        // default: do nothing
    }

    /** Called when the chase goal stops (subclasses can clear animation flags) */
    protected void onChaseStop() {
        // default: do nothing
    }

    // ================================================================
    // Sleep ticking (server-only behavior state machine)
    // ================================================================

    /**
     * Server-only sleep state machine:
     * - If sleeping: decrement timer and check wake conditions.
     * - If not sleeping: check if we are allowed to start sleeping, then roll chance.
     *
     * Roof requirement:
     * - If sleepRequiresRoof is true and we currently see the sky, we do NOT sleep here.
     *   Instead, CatoSleepSearchGoal is responsible for finding a roofed spot first.
     */
    protected void tickSleepServer() {
        CatoMobSpeciesInfo info = getSpeciesInfo();

        // Species doesn't support sleep at all -> force awake
        if (!info.sleepEnabled()) {
            if (isSleeping()) {
                setSleeping(false);
                sleepTicksRemaining = 0;
            }
            return;
        }

        // ----------------------------
        // WAKE LOGIC (if currently sleeping)
        // ----------------------------
        if (isSleeping()) {
            if (sleepTicksRemaining > 0) sleepTicksRemaining--;

            boolean shouldWake = false;

            // time up
            if (sleepTicksRemaining <= 0) shouldWake = true;

            // combat / aggression always wakes
            if (this.getTarget() != null || this.isAggressive()) shouldWake = true;

            // movement/navigation wakes (prevents turning/walking while sleeping)
            if (!this.getNavigation().isDone()) shouldWake = true;

            // wake toggles
            if (info.wakeOnAir() && !this.onGround()) shouldWake = true;

            if (info.wakeOnTouchingWater() && this.isInWater()) {
                // If sleeping on water surface is allowed, don't wake just for being in water
                if (!info.sleepAllowedOnWaterSurface()) {
                    shouldWake = true;
                }
            }

            // underwater wake logic (head submerged)
            if (info.wakeOnUnderwater() && this.isUnderWater()) {
                shouldWake = true;
            }

            if (info.wakeOnSunlight()) {
                // sunlight = day AND sky visible from current block pos
                if (this.level().isDay() && this.level().canSeeSky(this.blockPosition())) {
                    shouldWake = true;
                }
            }

            if (shouldWake) {
                setSleeping(false);
                sleepTicksRemaining = 0;
            }

            return;
        }

        // ----------------------------
        // START LOGIC (not sleeping)
        // ----------------------------

        // If we are already searching for a sleep spot, do NOT roll sleep chance again
        if (this.isSleepSearching()) return;

        // Only sleep when calm/idle
        if (this.getTarget() != null || this.isAggressive()) return;
        if (!this.getNavigation().isDone()) return;

        // Respect general physical constraints unless sleeping on water is allowed
        if (!this.onGround() && !info.sleepAllowedOnWaterSurface()) return;
        if (this.isInWater() && !info.sleepAllowedOnWaterSurface()) return;

        // time window
        boolean isDay = this.level().isDay();
        boolean allowedTime = (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());
        if (!allowedTime) return;

        // If roof is required and we can see sky here, we can't sleep here.
        // The search goal handles traveling to a roofed spot.
        if (info.sleepRequiresRoof()) {
            if (this.level().canSeeSky(this.blockPosition())) return;
        }

        // don't start sleeping if physically sliding/moving a little (knockback, pushes, etc.)
        if (this.getDeltaMovement().horizontalDistanceSqr() > 0.001D) return;

        // chance roll to start sleeping
        if (this.getRandom().nextFloat() < info.sleepStartChancePerTick()) {
            setSleeping(true);

            int min = Math.max(1, info.sleepMinTicks());
            int max = Math.max(min, info.sleepMaxTicks());
            int range = max - min + 1;

            sleepTicksRemaining = min + this.getRandom().nextInt(range);

            this.getNavigation().stop();
        }
    }

    // ================================================================
    // Main AI step (server-side behavior + timed attack damage)
    // ================================================================

    /**
     * Runs every tick.
     * We do most of our custom "server authoritative" state logic here:
     * - assign homePos once (if species uses home radius)
     * - handle sleep state machine
     * - handle anger countdown + calm down
     * - handle timed attack hit moment and attack anim duration
     * - update visually-angry synced flag
     * - keep looking at target while angry
     */
    @Override
    public void aiStep() {
        super.aiStep();

        // Only the server should run AI + timers.
        if (!level().isClientSide) {

            // Auto-assign home position once if this species uses a home radius
            if (this.homePos == null && this.shouldStayWithinHomeRadius()) {
                this.homePos = this.blockPosition();
            }

            // Sleep tick (may set sleeping or wake us)
            tickSleepServer();

            // If sleeping, force-clear combat state and stop doing combat-look logic
            if (this.isSleeping()) {
                clearAttackState();
                this.setTarget(null);
                this.setLastHurtByMob(null);
                this.setAggressive(false);
                return;
            }

            // 1) Anger countdown & calm-down behavior
            if (angerTime > 0) {
                angerTime--;
            } else if (getTarget() != null) {
                // Anger ended -> stop fighting and reset everything
                this.setTarget(null);
                this.setAggressive(false);
                this.setLastHurtByMob(null);
                clearAttackState();
            }

            // Mirror anger state into a client-synced "visual angry" flag (for overlay animations etc.)
            boolean angryNow = (this.angerTime > 0 && this.getTarget() != null);
            this.setVisuallyAngry(angryNow);

            // 2) If we lost our target mid-attack, cancel the attack timers
            if (this.getTarget() == null && (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0)) {
                clearAttackState();
            }

            // 3) Timed damage moment:
            // When attackTicksUntilHit hits 0, we do the actual damage check.
            if (this.attackTicksUntilHit > 0) {
                this.attackTicksUntilHit--;

                if (this.attackTicksUntilHit == 0) {
                    if (this.queuedAttackTarget != null && this.queuedAttackTarget.isAlive()) {
                        // Use species-configured hit range (separate from "trigger range")
                        double hitRange = getSpeciesInfo().attackHitRange();
                        if (hitRange <= 0.0D) hitRange = 2.0D;

                        double maxHitDistSqr = hitRange * hitRange;

                        // Only deal damage if the target is still within hit range at the hit moment
                        if (this.distanceToSqr(this.queuedAttackTarget) <= maxHitDistSqr) {
                            this.doHurtTarget(this.queuedAttackTarget);
                        }
                    }

                    // Clear queued target after hit moment (whether we hit or missed)
                    this.queuedAttackTarget = null;
                }
            }

            // 4) Attack animation duration control:
            // When this timer reaches 0, we notify subclass via onAttackAnimationEnd().
            if (this.attackAnimTicksRemaining > 0) {
                this.attackAnimTicksRemaining--;

                if (this.attackAnimTicksRemaining == 0) {
                    this.onAttackAnimationEnd();
                }
            }

            // 5) While angry and having a target, keep looking at it.
            // We respect per-mob head rotation limits (subclasses can override getMaxHeadXRot/YRot).
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

    /**
     * Convenience factory: builds AttributeSupplier from species info.
     * Called from entity registration code, e.g. EntityAttributeCreationEvent.
     */
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

    /** By default: not breedable by food. Subclasses can override. */
    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    /** By default: no offspring. Subclasses override to return their baby entity. */
    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }
}
