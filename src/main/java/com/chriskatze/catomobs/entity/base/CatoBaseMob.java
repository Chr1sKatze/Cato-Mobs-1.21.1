package com.chriskatze.catomobs.entity.base;

import com.chriskatze.catomobs.entity.*;
import com.chriskatze.catomobs.entity.component.BlinkComponent;
import com.chriskatze.catomobs.entity.component.WaterMovementComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.*;

/**
 * CatoBaseMob
 *
 * Base class for all CatoMobs.
 *
 * Responsibilities:
 * 1) Central place to register/configure goals (sleep, wander, combat, etc.)
 * 2) Shared timed-attack system (start anim now, apply damage later)
 * 3) Shared sleep system (attempt pacing + desire window + roof search support)
 * 4) Shared aggression/retaliation state (anger timer + visual angry flag)
 * 5) Shared home position + radius concept (used by wander and sleep search)
 * 6) Shared synced state for client visuals (sleeping, move mode, visually angry)
 *
 * Subclasses provide:
 * - species info (CatoMobSpeciesInfo) which drives ALL tuning
 * - optional animation hooks (attack start/end, wander start/stop, chase hooks)
 */
public abstract class CatoBaseMob extends Animal {

    // ================================================================
    // 0) PUBLIC CONSTANTS (synced animation intent, NOT vanilla speed)
    // ================================================================

    /** Animation intent: stand/idle */
    public static final int MOVE_IDLE = 0;
    /** Animation intent: walking locomotion */
    public static final int MOVE_WALK = 1;
    /** Animation intent: running locomotion */
    public static final int MOVE_RUN  = 2;

    // ================================================================
    // 1) SPECIES + PRIORITIES (subclass contract)
    // ================================================================

    /** Each concrete mob must provide species tuning (sleep/combat/wander/etc). */
    protected abstract CatoMobSpeciesInfo getSpeciesInfo();

    /**
     * Central goal priority profile.
     * Subclasses can override this to adjust priorities/feature flags per mob.
     */
    protected CatoGoalPriorityProfile getGoalPriorities() {
        return CatoGoalPriorityProfile.defaults();
    }

    // ================================================================
    // 2) CONFIG TOGGLES (enabled by subclass via helper methods)
    // ================================================================

    /** If set, registerGoals() may add a TemptGoal (follow the item). */
    protected Ingredient temptItem = null;

    /** If true, registerGoals() may add BreedGoal + FollowParentGoal. */
    protected boolean canBreed = false;

    // ================================================================
    // 3) VISUALS + HITBOX (species-driven)
    // ================================================================

    /** Renderer reads this (you should apply it in your GeoEntityRenderer). */
    public float getShadowRadius() {
        return Math.max(0f, getSpeciesInfo().shadowRadius());
    }

    // ================================================================
    // 4) WATER MOVEMENT COMPONENT (travel() helpers)
    // ================================================================

    @Nullable
    private WaterMovementComponent waterMovement;

    protected final WaterMovementComponent waterMovement() {
        if (waterMovement == null) {
            var wm = this.getSpeciesInfo().waterMovement();
            if (wm == null) {
                wm = CatoMobSpeciesInfo.WaterMovementConfig.disabled();
            }

            waterMovement = new WaterMovementComponent(
                    new WaterMovementComponent.Config(
                            wm.dampingEnabled(),
                            wm.verticalDamping(),
                            wm.verticalSpeedClamp(),
                            wm.dampingApplyThreshold()
                    )
            );
        }
        return waterMovement;
    }

    // ================================================================
    // 5) HOME POSITION (used by wandering / sleep search bounds)
    // ================================================================

    /**
     * If the species enables "stayWithinHomeRadius", we capture the first position as home.
     * Goals (wander/sleep search) use this as their center.
     */
    protected BlockPos homePos = null;

    public BlockPos getHomePos() { return homePos; }
    public void setHomePos(BlockPos homePos) { this.homePos = homePos; }

    /** Convenience wrappers so goals don’t have to poke species info directly. */
    public boolean shouldStayWithinHomeRadius() { return getSpeciesInfo().stayWithinHomeRadius(); }
    public double getHomeRadius() { return getSpeciesInfo().homeRadius(); }

    // ================================================================
    // 6) SYNCED DATA (client visuals)
    // ================================================================

    /** Server authoritative sleeping flag (client uses it for animations). */
    private static final EntityDataAccessor<Boolean> DATA_SLEEPING =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    /** Server authoritative movement intent (IDLE/WALK/RUN). */
    private static final EntityDataAccessor<Integer> DATA_MOVE_MODE =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.INT);

    /** Visual-only anger flag synced to client (overlays/expressions). */
    private static final EntityDataAccessor<Boolean> DATA_VISUALLY_ANGRY =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    /** Synced: is currently playing attack animation (for GeckoLib / client visuals). */
    private static final EntityDataAccessor<Boolean> DATA_ATTACKING =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    /** Debug overlay toggle (synced). */
    private static final EntityDataAccessor<Boolean> DATA_DEBUG_AI =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.BOOLEAN);

    /** Debug overlay payload (synced, newline-separated). */
    private static final EntityDataAccessor<String> DATA_DEBUG_AI_TEXT =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.STRING);

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOVE_MODE, MOVE_IDLE);
        builder.define(DATA_ATTACKING, false);
        builder.define(DATA_VISUALLY_ANGRY, false);
        builder.define(DATA_SLEEPING, false);
        builder.define(DATA_DEBUG_AI, false);
        builder.define(DATA_DEBUG_AI_TEXT, "");
    }

    // --- Synced accessors ---
    public boolean isSleeping() { return this.entityData.get(DATA_SLEEPING); }
    protected void setSleeping(boolean sleeping) { this.entityData.set(DATA_SLEEPING, sleeping); }

    public int getMoveMode() { return this.entityData.get(DATA_MOVE_MODE); }
    protected void setMoveMode(int mode) { this.entityData.set(DATA_MOVE_MODE, mode); }

    /** For animations: true while we want the attack animation to play. */
    public boolean isAttacking() { return this.entityData.get(DATA_ATTACKING); }
    protected void setAttacking(boolean attacking) { this.entityData.set(DATA_ATTACKING, attacking); }

    public boolean isVisuallyAngry() { return this.entityData.get(DATA_VISUALLY_ANGRY); }
    protected void setVisuallyAngry(boolean angry) { this.entityData.set(DATA_VISUALLY_ANGRY, angry); }

    public boolean isAiDebugEnabled() { return this.entityData.get(DATA_DEBUG_AI); }

    public void setAiDebugEnabled(boolean enabled) {
        this.entityData.set(DATA_DEBUG_AI, enabled);
        if (!enabled) { this.entityData.set(DATA_DEBUG_AI_TEXT, ""); }
    }

    public String getAiDebugText() { return this.entityData.get(DATA_DEBUG_AI_TEXT); }

    // --- Server-Side snapshot ---

    private int debugAiTickCooldown = 0;

    private void tickAiDebugServer() {
        if (!isAiDebugEnabled()) return;

        // throttle: update 5x per second (every 4 ticks)
        if (debugAiTickCooldown-- > 0) return;
        debugAiTickCooldown = 4;

        String text = buildAiDebugSnapshot();
        this.entityData.set(DATA_DEBUG_AI_TEXT, text);
    }

    private String buildAiDebugSnapshot() {
        LivingEntity t = this.getTarget();

        String targetStr = (t == null)
                ? "none"
                : (t.getType().toShortString() + " @" + t.blockPosition().toShortString());

        String navStr = "nav=" + (this.getNavigation().isInProgress() ? "IN_PROGRESS"
                : (this.getNavigation().isDone() ? "DONE" : "OTHER"));

        // ------------------------------------------------------------
        // Sleep gate snapshot (WHY search may not be firing)
        // ------------------------------------------------------------
        CatoMobSpeciesInfo info = getSpeciesInfo();

        boolean isDay = this.level().isDay();
        boolean allowedTime = (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        boolean roofedHere = info.sleepRequiresRoof() && this.isRoofed(this.blockPosition(), roofMax);

        boolean desire = hasSleepDesire();
        boolean searching = isSleepSearching();
        boolean cooldown = isSleepSearchOnCooldown();

        String top =
                "AI DEBUG" +
                        "\nstate:" +
                        " sleep=" + isSleeping() +
                        " desire=" + desire +
                        " desireTicks=" + getSleepDesireTicks() +
                        " angryTime=" + this.angerTime +
                        " aggressive=" + this.isAggressive() +
                        " visuallyAngry=" + this.isVisuallyAngry() +
                        "\nmoveMode=" + this.getMoveMode() +
                        " attacking=" + this.isAttacking() +
                        " hitIn=" + this.attackTicksUntilHit +
                        " animLeft=" + this.attackAnimTicksRemaining +
                        "\n" + navStr +
                        "\ntarget=" + targetStr;

        // Goals list (goalSelector + targetSelector)
        ArrayList<String> lines = new ArrayList<>();
        lines.add(top);
        lines.add("--------------------------------");

        lines.add("GOALS (action):");
        lines.addAll(dumpGoalsSafe(this.goalSelector));

        lines.add("--------------------------------");
        lines.add("GOALS (target):");
        lines.addAll(dumpGoalsSafe(this.targetSelector));

        // ------------------------------------------------------------
        // Sleep debugging block (expanded)
        // ------------------------------------------------------------
        lines.add("--------------------------------");
        lines.add("sleep:");
        lines.add("  enabled=" + info.sleepEnabled()
                + " requiresRoof=" + info.sleepRequiresRoof()
                + " allowedTime=" + allowedTime + " (day=" + isDay + ")"
        );
        lines.add("  desire=" + desire
                + " desireTicks=" + getSleepDesireTicks()
                + " searching=" + searching
                + " cooldown=" + cooldown
                + " roofedHere=" + roofedHere
        );
        lines.add("  target=" + (this.getTarget() != null)
                + " aggressive=" + this.isAggressive()
                + " onGround=" + this.onGround()
                + " inWater=" + this.isInWater()
                + " underWater=" + this.isUnderWater()
        );

        return String.join("\n", lines);
    }

    /**
     * Tries to dump goals without depending on one specific Mojang mapping.
     * Uses reflection as a debug-only fallback (because GoalSelector internals changed across versions).
     *
     * Output format:
     *   "[G]  6 CatoMeleeAttackGoal"
     *   "[R] 10 CatoWanderGoal"
     */
    @SuppressWarnings("unchecked")
    private List<String> dumpGoalsSafe(GoalSelector selector) {
        try {
            // 1) Best case: GoalSelector has getAvailableGoals()
            var m = selector.getClass().getMethod("getAvailableGoals");
            Object result = m.invoke(selector);
            if (result instanceof Set<?> set) {
                return dumpWrappedGoalSet(set);
            }
        } catch (Throwable ignored) { }

        try {
            // 2) Reflection fallback: find a Set field that looks like the internal "availableGoals"
            for (var f : selector.getClass().getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object val = f.get(selector);
                if (val instanceof Set<?> set && !set.isEmpty()) {
                    return dumpWrappedGoalSet(set);
                }
            }
        } catch (Throwable ignored) { }

        return List.of("(could not inspect goals on this build)");
    }

    private List<String> dumpWrappedGoalSet(Set<?> wrappedGoalSet) {
        record GoalLine(int prio, boolean running, String name) {}

        ArrayList<GoalLine> tmp = new ArrayList<>();

        for (Object w : wrappedGoalSet) {
            try {
                int prio = 0;
                boolean running = false;
                Goal goal = null;

                // priority
                try {
                    var mp = w.getClass().getMethod("getPriority");
                    prio = (int) mp.invoke(w);
                } catch (Throwable ignored) {
                    var fp = w.getClass().getDeclaredField("priority");
                    fp.setAccessible(true);
                    prio = (int) fp.get(w);
                }

                // running
                try {
                    var mr = w.getClass().getMethod("isRunning");
                    running = (boolean) mr.invoke(w);
                } catch (Throwable ignored) {
                    var fr = w.getClass().getDeclaredField("isRunning");
                    fr.setAccessible(true);
                    running = (boolean) fr.get(w);
                }

                // goal
                try {
                    var mg = w.getClass().getMethod("getGoal");
                    goal = (Goal) mg.invoke(w);
                } catch (Throwable ignored) {
                    var fg = w.getClass().getDeclaredField("goal");
                    fg.setAccessible(true);
                    goal = (Goal) fg.get(w);
                }

                String name = (goal == null) ? w.getClass().getSimpleName() : goal.getClass().getSimpleName();
                tmp.add(new GoalLine(prio, running, name));
            } catch (Throwable ignored) {
                // skip broken entries
            }
        }

        tmp.sort(Comparator.comparingInt(GoalLine::prio));

        ArrayList<String> out = new ArrayList<>();
        for (GoalLine gl : tmp) {
            out.add((gl.running ? "[G] " : "[R] ") + String.format("%2d ", gl.prio) + gl.name);
        }
        if (out.isEmpty()) out.add("(no goals found)");
        return out;
    }

// ================================================================
// 7) AGGRESSION / RETALIATION (server-side)
// ================================================================

    /**
     * How long (ticks) the mob should remain angry after being hurt.
     * While >0: keep target/aggressive state, look at target, and show visual anger.
     * When reaches 0: clear target/aggression and cancel attack state.
     */
    protected int angerTime = 0;

    /**
     * Whether this temperament category is allowed to retaliate at all.
     * (Temperament still decides the "type" of mob; species decides the numbers.)
     */
    protected boolean temperamentAllowsRetaliation(CatoMobTemperament t) {
        return t == CatoMobTemperament.NEUTRAL_RETALIATE_SHORT
                || t == CatoMobTemperament.NEUTRAL_RETALIATE_LONG
                || t == CatoMobTemperament.HOSTILE;
    }

    /** Pull the anger duration from species info (single source of truth). */
    protected int getConfiguredAngerTicks(CatoMobSpeciesInfo info) {
        if (!info.retaliateWhenAngered()) return 0;
        return Math.max(0, info.retaliationDurationTicks());
    }

    // ================================================================
    // 8) TIMED ATTACK SYSTEM (server-side)
    // ================================================================

    /**
     * Target that will receive damage when "hit moment" arrives.
     * Stored when AI starts an attack, damage applied later in aiStep().
     */
    protected LivingEntity queuedAttackTarget = null;

    /** Countdown until the hit moment. When it reaches 0, we deal damage once. */
    protected int attackTicksUntilHit = -1;

    /**
     * Countdown for the attack animation active window.
     * Prevents restarting attacks mid-swing.
     */
    protected int attackAnimTicksRemaining = 0;

    // ================================================================
    // 9) SLEEP SYSTEM STATE (server-only)
    // ================================================================

    /** Current sleep duration timer. Decrements while sleeping; when 0 we may extend or wake. */
    private int sleepTicksRemaining = 0;

    /**
     * Internal flag: "I am currently searching for a sleep spot".
     * Set/cleared by CatoSleepSearchGoal so base logic doesn’t re-roll sleep attempts.
     */
    private boolean sleepSearching = false;

    /** Cooldown timer after failed search so we don’t spam-search every tick. */
    private long sleepSearchCooldownUntil = 0L;

    /** Next tick at which we're allowed to roll a new sleep attempt (attempt-based pacing). */
    private long nextSleepAttemptTick = 0L;

    /**
     * When >0, the mob has decided it wants to sleep.
     * This keeps "desire" alive long enough for SleepSearchGoal to start/path.
     */
    private int sleepDesireTicks = 0;

    /**
     * If the allowed sleep time window flips (day<->night), we don’t insta-wake.
     * We start a small grace timer to make waking feel natural.
     */
    private int timeWindowWakeGraceTicks = 0;

    // --- Sleep desire API (used by SleepSearchGoal) ---
    public boolean isSleepSearching() { return sleepSearching; }
    public void setSleepSearching(boolean searching) { this.sleepSearching = searching; }

    public boolean wantsToSleepNow() { return sleepDesireTicks > 0; }
    protected void clearSleepDesire() { this.sleepDesireTicks = 0; }

    // --- Sleep search cooldown API (used by SleepSearchGoal) ---
    public boolean isSleepSearchOnCooldown() {
        return !this.level().isClientSide && this.level().getGameTime() < sleepSearchCooldownUntil;
    }

    public void startSleepSearchCooldown(int ticks) {
        sleepSearchCooldownUntil = this.level().getGameTime() + Math.max(0, ticks);
    }

    // ================================================================
    // 9.5) BLINK + BLINK HELPER
    // ================================================================

    @Nullable
    private BlinkComponent blink;

    public final BlinkComponent blink() {
        if (blink == null) {
            blink = new BlinkComponent(this.getRandom());
        }
        return blink;
    }

    protected boolean allowBlink() {
        // default rule: no blink while sleeping
        return !this.isSleeping();
    }

    protected final <E extends GeoEntity> PlayState blinkController(AnimationState<E> state, RawAnimation blinkAnim) {
        if (!this.level().isClientSide) return PlayState.STOP;

        var blink = this.blink();
        if (!blink.isBlinking()) return PlayState.STOP;

        if (blink.consumeBlinkJustStarted()) {
            state.getController().forceAnimationReset();
            state.getController().setAnimation(blinkAnim);
        }

        return PlayState.CONTINUE;
    }

    // ================================================================
    // 9.6) SIMPLE OVERLAY HELPER
    // ================================================================

    protected final <E extends GeoEntity> PlayState overlayController(
            AnimationState<E> state,
            boolean enabled,
            RawAnimation anim
    ) {
        if (!enabled) return PlayState.STOP;
        state.setAndContinue(anim);
        return PlayState.CONTINUE;
    }

    // ================================================================
    // 10) CONSTRUCTION + SUBCLASS CONFIG HELPERS
    // ================================================================

    protected CatoBaseMob(EntityType<? extends Animal> type, Level level) {
        super(type, level);
    }

    /** Enables temptation behavior (adds TemptGoal in registerGoals if allowed). */
    protected void setTemptItem(Ingredient ingredient) {
        this.temptItem = ingredient;
    }

    /** Enables breeding behavior (adds BreedGoal + FollowParentGoal if allowed). */
    protected void enableBreeding() {
        this.canBreed = true;
    }

    // ================================================================
    // 11) ANIMATION / BEHAVIOR HOOKS (optional overrides)
    // ================================================================

    protected void onAttackAnimationStart(LivingEntity target) { }
    protected void onAttackAnimationEnd() { }

    // How many ticks since the current attack animation started (server-side)
    private int attackAnimAgeTicks = 0;

    public int getAttackAnimAgeTicks() {
        return attackAnimAgeTicks;
    }

    /**
     * Whether navigation movement is allowed THIS TICK while an attack animation is active.
     */
    public boolean canMoveDuringCurrentAttackAnimTick() {
        if (!this.isAttacking() || this.attackAnimTicksRemaining <= 0) return true;

        CatoMobSpeciesInfo info = this.getSpeciesInfo();
        int age = Math.max(0, this.attackAnimAgeTicks);

        int startDelay = Math.max(0, info.attackMoveStartDelayTicks());
        int stopAfter = info.attackMoveStopAfterTicks(); // can be <= 0 intentionally

        if (info.moveDuringAttackAnimation()) {
            if (age < startDelay) return false;
            if (stopAfter > 0 && age >= stopAfter) return false;
            return true;
        } else {
            if (stopAfter > 0) {
                return age < stopAfter;
            }
            return false;
        }
    }

    protected void onWanderStart(boolean running) { }
    protected void onWanderStop() { }

    protected void onChaseStart(LivingEntity target) { }
    protected void onChaseTick(LivingEntity target, boolean isMoving) { }
    protected void onChaseStop() { }

    // ================================================================
    // 12) SLEEP HELPERS (start/wake)
    // ================================================================

    /** Instant wake: clears sleeping + sleep timer and enforces attempt cooldown. */
    protected void wakeUp() {
        if (isSleeping()) {
            setSleeping(false);
            sleepTicksRemaining = 0;

            // Prevent immediate re-sleep attempts right after waking.
            nextSleepAttemptTick = this.level().getGameTime() + sleepAttemptIntervalTicks();
        }
    }

    /**
     * Called by SleepSearchGoal when the mob reached a valid roofed spot.
     * This is the "commit to sleeping" entry point from goal logic.
     */
    protected void beginSleepingFromGoal() {
        if (this.isSleeping()) return;

        CatoMobSpeciesInfo info = getSpeciesInfo();
        if (!info.sleepEnabled()) return;
        if (this.getTarget() != null || this.isAggressive()) return;

        // If we start sleeping via search, the desire should be consumed.
        clearSleepDesire();

        setSleeping(true);

        // Allow immediate next "extend sleep" logic; we’re actively sleeping now.
        nextSleepAttemptTick = 0L;

        int min = Math.max(1, info.sleepMinTicks());
        int max = Math.max(min, info.sleepMaxTicks());
        int range = max - min + 1;

        this.sleepTicksRemaining = min + this.getRandom().nextInt(range);
        this.getNavigation().stop();
    }

    public boolean hasSleepDesire() {
        return sleepDesireTicks > 0;
    }

    public int getSleepDesireTicks() {
        return sleepDesireTicks;
    }

    // ================================================================
    // 13) SLEEP ATTEMPT PACING (attempt-based, not per-tick)
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

    /**
     * Rolls a sleep attempt at most once per interval.
     * If it succeeds, base logic opens a desire window so search goal can run.
     */
    protected boolean rollSleepAttempt() {
        if (this.level().isClientSide) return false;

        long now = this.level().getGameTime();
        if (now < nextSleepAttemptTick) return false;

        nextSleepAttemptTick = now + sleepAttemptIntervalTicks();
        return this.getRandom().nextFloat() < sleepAttemptChance();
    }

    // ================================================================
    // 14) TIMED ATTACK ENTRY POINT
    // ================================================================

    /**
     * Called by melee goal when we want to start an attack.
     * We don’t deal damage now: we start timers and apply damage later.
     */
    public boolean startTimedAttack(LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (this.isSleeping()) return false;

        // Don’t restart if already attacking.
        if (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0 || this.queuedAttackTarget != null) {
            return false;
        }

        CatoMobSpeciesInfo info = getSpeciesInfo();

        // Optional: attack only if within trigger range.
        double triggerRange = info.attackTriggerRange();
        if (triggerRange > 0.0D) {
            double maxTriggerDistSqr = triggerRange * triggerRange;
            if (this.distanceToSqr(target) > maxTriggerDistSqr) return false;
        }

        // Commit attack
        this.queuedAttackTarget = target;
        this.attackAnimTicksRemaining = info.attackAnimTotalTicks();
        this.attackTicksUntilHit = info.attackHitDelayTicks();

        // reset "age since attack started" when starting a new attack
        this.attackAnimAgeTicks = 0;

        this.onAttackAnimationStart(target);
        this.setAttacking(true);
        return true;
    }

    /** Clears all server attack timers and the queued target. */
    protected void clearAttackState() {
        this.attackTicksUntilHit = -1;
        this.attackAnimTicksRemaining = 0;
        this.queuedAttackTarget = null;
        this.setAttacking(false);
        this.attackAnimAgeTicks = 0;
        this.onAttackAnimationEnd();
    }

    // ================================================================
    // 14.5) MOVEMENT HOOK (shared water smoothing)
    // ================================================================

    @Override
    public void travel(Vec3 travelVector) {
        if (this.isInWater()) {
            // 1) Scale horizontal input
            double mul = this.getSpeciesInfo().waterSwimSpeedMultiplier();
            Vec3 scaled = waterMovement().scaleHorizontalInput(travelVector, mul);

            super.travel(scaled);

            // 2) Dampen bobbing when idle (only if nav done)
            if (this.getNavigation().isDone()) {
                this.setDeltaMovement(waterMovement().dampVerticalIfIdle(this.getDeltaMovement()));
            }
            return;
        }

        super.travel(travelVector);
    }

    // ================================================================
    // 15) GOAL REGISTRATION (AI wiring)
    // ================================================================

    @Override
    protected void registerGoals() {
        CatoMobSpeciesInfo speciesInfo = getSpeciesInfo();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        // Basic survival
        this.goalSelector.addGoal(prio.floatGoal, new FloatGoal(this));

        // Sleep system (lock goal + optional roof search goal)
        if (prio.enableSleep && speciesInfo.sleepEnabled()) {
            this.goalSelector.addGoal(prio.sleepLock, new CatoSleepGoal(this));

            if (prio.enableSleepSearch) {
                this.goalSelector.addGoal(prio.sleepSearch, new CatoSleepSearchGoal(this));
            }
        }

        // Look behavior
        if (prio.enableLookGoals) {
            this.goalSelector.addGoal(prio.lookAtPlayer,
                    new LookAtPlayerGoal(this, Player.class, 8.0F, 0.1F));
            this.goalSelector.addGoal(prio.randomLook, new RandomLookAroundGoal(this));
        }

        // Tempt behavior
        if (prio.enableTempt && temptItem != null) {
            this.goalSelector.addGoal(prio.tempt, new TemptGoal(this, 1.2D, temptItem, false));
        }

        // Temperament -> combat setup
        switch (speciesInfo.temperament()) {
            case PASSIVE -> setupPassiveGoals();
            case NEUTRAL_RETALIATE_SHORT, NEUTRAL_RETALIATE_LONG -> setupNeutralGoals();
            case HOSTILE -> setupHostileGoals();
        }

        // Movement type -> wander setup
        if (prio.enableWander) {
            switch (speciesInfo.movementType()) {
                case LAND -> setupLandGoals();
                case FLYING -> setupFlyingGoals();
                case HOVERING -> setupHoveringGoals();
                case SURFACE_SWIM -> setupSurfaceSwimGoals();
                case UNDERWATER -> setupUnderwaterGoals();
            }
        }

        // Breeding
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
        double chaseSpeed = getSpeciesInfo().chaseSpeedModifier();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        // Keeps retaliation target behavior more “vanilla stable”
        this.targetSelector.addGoal(prio.targetHurtBy, new CatoGatedHurtByTargetGoal(this));

        this.goalSelector.addGoal(prio.meleeAttack, new CatoMeleeAttackGoal(this, chaseSpeed, true, cooldown));
    }

    protected void setupHostileGoals() {
        int cooldown = getSpeciesInfo().attackCooldownTicks();
        double chaseSpeed = getSpeciesInfo().chaseSpeedModifier();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        this.targetSelector.addGoal(prio.targetHurtBy, new CatoGatedHurtByTargetGoal(this));
        this.targetSelector.addGoal(prio.targetNearestPlayer,
                new NearestAttackableTargetGoal<>(this, Player.class, true));

        this.goalSelector.addGoal(prio.meleeAttack, new CatoMeleeAttackGoal(this, chaseSpeed, false, cooldown));
    }

    // ================================================================
    // 16) DAMAGE HOOK -> WAKE + SET ANGER STATE (species-driven)
    // ================================================================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Wake on damage if species wants that behavior
        if (!this.level().isClientSide && getSpeciesInfo().wakeOnDamage()) {
            wakeUp();
        }

        boolean result = super.hurt(source, amount);

        // Retaliation / anger setup after taking damage (species-driven)
        if (!this.level().isClientSide && result && source.getEntity() instanceof LivingEntity attacker) {
            CatoMobSpeciesInfo info = getSpeciesInfo();

            // Temperament decides whether this "type" retaliates at all.
            boolean temperamentAllowsRetaliation =
                    info.temperament() == CatoMobTemperament.NEUTRAL_RETALIATE_SHORT
                            || info.temperament() == CatoMobTemperament.NEUTRAL_RETALIATE_LONG
                            || info.temperament() == CatoMobTemperament.HOSTILE;

            // Species decides if retaliation is enabled + how long.
            if (temperamentAllowsRetaliation && info.retaliateWhenAngered()) {
                int ticks = Math.max(0, info.retaliationDurationTicks());
                if (ticks > 0) {
                    this.angerTime = ticks;
                    this.setTarget(attacker);
                    this.setLastHurtByMob(attacker);
                    this.setAggressive(true);
                }
            }
        }

        return result;
    }

    // ================================================================
    // 17) SLEEP TICK (server-side state machine)
    // ================================================================

    /**
     * Server-only sleep state machine.
     * Called from aiStep() every tick.
     */
    protected void tickSleepServer() {
        CatoMobSpeciesInfo info = getSpeciesInfo();

        // If sleeping is disabled: force clear state.
        if (!info.sleepEnabled()) {
            clearSleepDesire();
            if (isSleeping()) {
                setSleeping(false);
                sleepTicksRemaining = 0;
                timeWindowWakeGraceTicks = 0;
            }
            return;
        }

        // ------------------------------------------------------------
        // A) CURRENTLY SLEEPING
        // ------------------------------------------------------------
        if (isSleeping()) {
            if (sleepTicksRemaining > 0) sleepTicksRemaining--;

            boolean shouldWake = false;

            // Hard wake conditions
            if (this.getTarget() != null || this.isAggressive()) shouldWake = true;
            if (!this.getNavigation().isDone()) shouldWake = true;
            if (info.wakeOnAir() && !this.onGround()) shouldWake = true;

            if (info.wakeOnTouchingWater() && this.isInWater()) {
                if (!info.sleepAllowedOnWaterSurface()) shouldWake = true;
            }

            if (info.wakeOnUnderwater() && this.isUnderWater()) shouldWake = true;

            if (info.wakeOnSunlight()) {
                if (this.level().isDay() && this.level().canSeeSky(this.blockPosition())) shouldWake = true;
            }

            // Time window flip handling (day<->night): grace nap then wake
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
                        if (timeWindowWakeGraceTicks <= 0) shouldWake = true;
                    }
                } else {
                    timeWindowWakeGraceTicks = 0;
                }
            }

            // If sleep timer ended: maybe extend, else wake.
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

            // Perform wake
            if (shouldWake) {
                setSleeping(false);
                sleepTicksRemaining = 0;
                timeWindowWakeGraceTicks = 0;
                clearSleepDesire();

                nextSleepAttemptTick = this.level().getGameTime() + sleepAttemptIntervalTicks();
            }

            return;
        }

        // ------------------------------------------------------------
        // B) NOT SLEEPING: decide/search/sleep
        // ------------------------------------------------------------

        // If search goal is already running, base logic stays out of the way.
        if (this.isSleepSearching()) return;

        // No sleeping while in combat
        if (this.getTarget() != null || this.isAggressive()) return;

        // Physical constraints
        if (!this.onGround() && !info.sleepAllowedOnWaterSurface()) return;
        if (this.isInWater() && !info.sleepAllowedOnWaterSurface()) return;
        if (this.isUnderWater() && info.sleepAllowedOnWaterSurface()) return;

        // Time window constraint
        boolean isDay = this.level().isDay();
        boolean allowedTime = (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());
        if (!allowedTime) return;

        // Single authoritative “want to sleep” decision:
        boolean wantsToSleep = (sleepDesireTicks > 0) || rollSleepAttempt();
        if (!wantsToSleep) return;

        // Keep desire alive briefly so SleepSearchGoal has time to start/path.
        int desireWindow = Math.max(1, info.sleepDesireWindowTicks());
        sleepDesireTicks = Math.max(sleepDesireTicks, desireWindow);

        // If roof required but not roofed here: wait for SleepSearchGoal to move us.
        if (info.sleepRequiresRoof()) {
            int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
            if (!this.isRoofed(this.blockPosition(), roofMax)) {
                return; // desire remains so search goal canUse() picks it up
            }
        }

        // Roof not required OR already roofed -> sleep in place
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

    // ================================================================
    // 18) ROOF HELPERS (used by sleep + sleep search goal)
    // ================================================================

    /** @return distance to first non-air, non-fluid block above pos; -1 if none within maxHeight */
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

    // ================================================================
    // 19) SLEEP SPOT MEMORY (used by SleepSearchGoal)
    // ================================================================

    // Defaults for “reasonable memory”; actual size/strikes are species-driven.
    private static final int MAX_SLEEP_SPOT_MEMORY = 6;
    private static final int MAX_SLEEP_SPOT_STRIKES = 2;

    private final Deque<SleepSpotMemory> rememberedSleepSpots = new ArrayDeque<>();

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

        // If already present: reset strikes and move to end (most-recent)
        for (var it = rememberedSleepSpots.iterator(); it.hasNext();) {
            SleepSpotMemory mem = it.next();
            if (mem.pos.equals(p)) {
                it.remove();
                mem.strikes = 0;
                rememberedSleepSpots.addLast(mem);
                return;
            }
        }

        // Enforce ring buffer size
        while (rememberedSleepSpots.size() >= max) {
            rememberedSleepSpots.removeFirst();
        }

        rememberedSleepSpots.addLast(new SleepSpotMemory(p));
    }

    /** Snapshot list: FIFO (oldest -> newest). */
    public List<SleepSpotMemory> getRememberedSleepSpots() {
        return List.copyOf(rememberedSleepSpots);
    }

    /** Adds a strike; deletes memory entry only after max strikes reached.
     *  ALSO records strikes into a global blacklist (works for non-remembered spots too).
     */
    public void strikeSleepSpot(@Nullable BlockPos pos) {
        if (this.level().isClientSide) return;
        if (pos == null) return;

        BlockPos p = pos.immutable();
        long key = p.asLong();

        // 1) Always record strike into blacklist (works for ANY spot)
        {
            byte cur = failedSleepSpotStrikes.getOrDefault(key, (byte) 0);
            int next = Math.min(SLEEP_SPOT_BLACKLIST_MAX_STRIKES, (cur & 0xFF) + 1);
            failedSleepSpotStrikes.put(key, (byte) next);
        }

        // 2) If it's in remembered list, also strike the memory entry
        for (var it = rememberedSleepSpots.iterator(); it.hasNext();) {
            SleepSpotMemory mem = it.next();
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
    // 19.5) FAILED SLEEP SPOT BLACKLIST (covers non-remembered spots too)
    // ================================================================

    private final HashMap<Long, Byte> failedSleepSpotStrikes = new HashMap<>();

    private static final int SLEEP_SPOT_BLACKLIST_MAX_STRIKES = 6;
    private static final int SLEEP_SPOT_BLACKLIST_THRESHOLD = 3;

    private static final int SLEEP_SPOT_BLACKLIST_DECAY_INTERVAL_TICKS = 200; // 10s
    private int sleepSpotBlacklistDecayTicker = 0;

    public boolean isSleepSpotBlacklisted(@Nullable BlockPos pos) {
        if (pos == null) return false;
        if (this.level().isClientSide) return false;

        byte strikes = failedSleepSpotStrikes.getOrDefault(pos.asLong(), (byte) 0);
        return strikes >= (byte) SLEEP_SPOT_BLACKLIST_THRESHOLD;
    }

    public int getSleepSpotBlacklistStrikes(@Nullable BlockPos pos) {
        if (pos == null) return 0;
        if (this.level().isClientSide) return 0;
        return failedSleepSpotStrikes.getOrDefault(pos.asLong(), (byte) 0);
    }

    public void clearSleepSpotBlacklist() {
        if (this.level().isClientSide) return;
        failedSleepSpotStrikes.clear();
    }

    private void tickSleepSpotBlacklistDecayServer() {
        if (failedSleepSpotStrikes.isEmpty()) return;

        if (++sleepSpotBlacklistDecayTicker < SLEEP_SPOT_BLACKLIST_DECAY_INTERVAL_TICKS) return;
        sleepSpotBlacklistDecayTicker = 0;

        var it = failedSleepSpotStrikes.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int v = (e.getValue() & 0xFF) - 1;
            if (v <= 0) it.remove();
            else e.setValue((byte) v);
        }
    }

    // ================================================================
    // 20) MAIN SERVER TICK (sleep + anger + timed attack damage)
    // ================================================================
    @Override
    public void aiStep() {
        super.aiStep();

        // ------------------------------------------------------------
        // Client-only cosmetics (blink)
        // ------------------------------------------------------------
        if (this.level().isClientSide) {
            blink().tick(allowBlink());
            return;
        }

        // ------------------------------------------------------------
        // Decay failed sleep-spot blacklist (slow, server-only)
        // ------------------------------------------------------------
        tickSleepSpotBlacklistDecayServer();

        // Decrement desire window (server-only)
        if (sleepDesireTicks > 0) {
            sleepDesireTicks--;
        }

        // Capture home position once if species uses home radius behavior
        if (this.homePos == null && this.shouldStayWithinHomeRadius()) {
            this.homePos = this.blockPosition();
        }

        // Sleep system
        tickSleepServer();

        // While sleeping: no combat logic should run
        if (this.isSleeping()) {
            clearAttackState();
            this.setTarget(null);
            this.setLastHurtByMob(null);
            this.setAggressive(false);
            // keep debug updating even while sleeping
            tickAiDebugServer();
            return;
        }

        // Anger countdown and cleanup
        if (angerTime > 0) {
            angerTime--;
        } else if (getTarget() != null) {
            this.setTarget(null);
            this.setAggressive(false);
            this.setLastHurtByMob(null);

            // stop leftover chase path
            this.getNavigation().stop();
            this.setMoveMode(MOVE_IDLE);

            clearAttackState();

            // NOTE: old versions used to forcibly stop HurtBy goal here.
            // If you still need that behavior, keep it behind a safe reflection helper
            // like you already do for goal dumping.
        }

        // Sync "visual angry" to clients
        boolean angryNow = (this.angerTime > 0 && this.getTarget() != null);
        this.setVisuallyAngry(angryNow);

        // If target is gone, don’t keep attack timers running
        if (this.getTarget() == null && (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0)) {
            clearAttackState();
        }

        // Deal timed hit
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

                // Consume queued target after hit attempt
                this.queuedAttackTarget = null;
            }
        }

        // Track attack animation duration
        if (this.attackAnimTicksRemaining > 0) {
            this.attackAnimTicksRemaining--;

            // increment "age since attack started" while animation is active
            this.attackAnimAgeTicks++;

            if (this.attackAnimTicksRemaining == 0) {
                // IMPORTANT: stop "attacking" flag so GeckoLib returns to idle/walk/run
                this.setAttacking(false);
                this.onAttackAnimationEnd();

                // reset age when the animation ends
                this.attackAnimAgeTicks = 0;
            }
        } else {
            // Safety: if we're not in an attack animation, age must be 0
            this.attackAnimAgeTicks = 0;
        }

        // While angry: force look-at target (clamped by max head rot)
        if (this.angerTime > 0 && this.getTarget() != null) {
            int maxYaw = this.getMaxHeadYRot();
            int maxPitch = this.getMaxHeadXRot();

            if (maxYaw != 0 || maxPitch != 0) {
                this.getLookControl().setLookAt(this.getTarget(), (float) maxYaw, (float) maxPitch);
            }
        }

        // Debug overlay snapshot
        tickAiDebugServer();
    }

    // ================================================================
    // 21) ATTRIBUTES (used by your entity registration)
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
    // 22) FOOD / BREEDING STUBS (subclasses usually override)
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
