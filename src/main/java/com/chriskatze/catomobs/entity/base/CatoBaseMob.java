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
import net.minecraft.world.level.pathfinder.PathType;

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
    public abstract CatoMobSpeciesInfo getSpeciesInfo();

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
    // 5.25) PATHFINDING SURFACE BIAS (water avoidance / preference)
    // ================================================================

    private boolean surfaceMalusApplied = false;

    /**
     * Applies pathfinding penalties based on SurfacePreferenceConfig.
     * This affects ROUTE CHOICE (e.g. whether it cuts through rivers),
     * not just wander target picking.
     */
    private void applySurfacePathfindingBiasOnce() {
        if (surfaceMalusApplied) return;
        surfaceMalusApplied = true;

        final CatoMobSpeciesInfo info = infoServer(); // ✅ consistent (cached when available)

        // ✅ Default: LAND mobs avoid water unless they explicitly prefer it
        if (info.movementType() == CatoMobMovementType.LAND) {
            this.setPathfindingMalus(PathType.WATER, 12.0F);
            this.setPathfindingMalus(PathType.WATER_BORDER, 6.0F);
        }

        var sp = info.surfacePreference();
        if (sp == null) return;

        double solid = sp.preferSolidSurfaceWeight();
        double water = sp.preferWaterSurfaceWeight();

        if (water > solid) {
            // explicitly water-lover => override to allow water
            this.setPathfindingMalus(PathType.WATER, 0.0F);
            this.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
        }
    }

    // ================================================================
    // 5.5) EXIT WATER URGE (server-side)
    // ================================================================
    private boolean exitWaterRequested = false;
    private long nextExitWaterRequestAllowedTick = 0L;   // rate-limit spam
    private long exitWaterScheduledTick = 0L;            // spread load across ticks

    public void requestExitWater() {
        if (this.level().isClientSide) return;

        long now = nowServer();

        // Rate limit: don't allow repeated requests too often
        if (now < nextExitWaterRequestAllowedTick) return;
        nextExitWaterRequestAllowedTick = now + 20; // 1s cooldown per mob (tune)

        this.exitWaterRequested = true;

        // Spread scans across time: schedule 0..3 ticks later based on entity id
        // (prevents dozens of mobs scanning same tick)
        this.exitWaterScheduledTick = now + (this.getId() & 3);
    }

    private void tickExitWaterUrgencyServer() {
        if (!exitWaterRequested) return;

        final long now = nowServer();
        if (now < exitWaterScheduledTick) return;

        // consume request immediately (one-shot)
        exitWaterRequested = false;

        // Only for LAND mobs (use cached per-tick species info when available)
        final CatoMobSpeciesInfo info = infoServer();
        if (info.movementType() != CatoMobMovementType.LAND) return;

        // Don't fight important states
        if (this.isSleeping()) return;
        if (this.isFleeing()) return;
        if (this.getTarget() != null || this.isAggressive() || this.angerTime > 0) return;

        // Already out -> nothing
        if (!this.isInWater()) return;

        // Ultra-cheap “am I basically already at shore?” probe first
        BlockPos near = WaterExitFinder.findDryStandableNear(this, 3);
        if (near != null) {
            nudgeToExitOnce(near);
            return;
        }

        // Real search (bounded)
        BlockPos exit = WaterExitFinder.findNearestDryStandableBounded(this, 16);
        if (exit == null) return;

        nudgeToExitOnce(exit);
    }

    private void nudgeToExitOnce(BlockPos exit) {
        this.getNavigation().stop();
        this.getNavigation().moveTo(
                exit.getX() + 0.5D,
                exit.getY(),
                exit.getZ() + 0.5D,
                1.1D
        );
        this.setMoveMode(MOVE_WALK);
    }

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

    /** Synced: which attack type is currently active (normal/special, melee/ranged). */
    private static final EntityDataAccessor<Integer> DATA_ATTACK_ID =
            SynchedEntityData.defineId(CatoBaseMob.class, EntityDataSerializers.INT);

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
        builder.define(DATA_ATTACK_ID, -1);
        builder.define(DATA_VISUALLY_ANGRY, false);
        builder.define(DATA_SLEEPING, false);
        builder.define(DATA_DEBUG_AI, false);
        builder.define(DATA_DEBUG_AI_TEXT, "");
    }

    // --- Synced accessors ---
    public boolean isSleeping() { return this.entityData.get(DATA_SLEEPING); }
    protected void setSleeping(boolean sleeping) {
        if (sleeping == this.entityData.get(DATA_SLEEPING)) return;
        this.entityData.set(DATA_SLEEPING, sleeping);
    }

    public int getMoveMode() { return this.entityData.get(DATA_MOVE_MODE); }
    protected void setMoveMode(int mode) {
        if (mode == this.entityData.get(DATA_MOVE_MODE)) return;
        this.entityData.set(DATA_MOVE_MODE, mode);
    }

    /** For animations: true while we want the attack animation to play. */
    public boolean isAttacking() { return this.entityData.get(DATA_ATTACKING); }
    protected void setAttacking(boolean attacking) {
        if (attacking == this.entityData.get(DATA_ATTACKING)) return;
        this.entityData.set(DATA_ATTACKING, attacking);
    }

    public int getAttackIdSynced() {
        return this.entityData.get(DATA_ATTACK_ID);
    }

    protected void setAttackIdSynced(@Nullable CatoAttackId id) {
        this.entityData.set(DATA_ATTACK_ID, id == null ? -1 : id.ordinal());
    }

    @Nullable
    public CatoAttackId getCurrentAttackId() {
        int v = getAttackIdSynced();
        if (v < 0) return null;

        CatoAttackId[] values = CatoAttackId.values();
        if (v >= values.length) return null;

        return values[v];
    }

    public boolean isVisuallyAngry() { return this.entityData.get(DATA_VISUALLY_ANGRY); }
    protected void setVisuallyAngry(boolean angry) {
        if (angry == this.entityData.get(DATA_VISUALLY_ANGRY)) return;
        this.entityData.set(DATA_VISUALLY_ANGRY, angry);
    }

    public boolean isAiDebugEnabled() { return this.entityData.get(DATA_DEBUG_AI); }

    public void setAiDebugEnabled(boolean enabled) {
        if (enabled == this.entityData.get(DATA_DEBUG_AI)) return;

        this.entityData.set(DATA_DEBUG_AI, enabled);

        // Clear text once when disabling
        if (!enabled) {
            if (!"".equals(this.entityData.get(DATA_DEBUG_AI_TEXT))) {
                this.entityData.set(DATA_DEBUG_AI_TEXT, "");
            }
        }
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
        String prev = this.entityData.get(DATA_DEBUG_AI_TEXT);
        if (!text.equals(prev)) {
            this.entityData.set(DATA_DEBUG_AI_TEXT, text);
        }
    }

    private String buildAiDebugSnapshot() {
        final Level level = this.level();
        final BlockPos pos = posServer();

        LivingEntity t = this.getTarget();

        String targetStr = (t == null)
                ? "none"
                : (t.getType().toShortString() + " @" + t.blockPosition().toShortString());

        String navStr = "nav=" + (this.getNavigation().isInProgress() ? "IN_PROGRESS"
                : (this.getNavigation().isDone() ? "DONE" : "OTHER"));

        CatoMobSpeciesInfo info = getSpeciesInfo();

        boolean isDay = level.isDay();
        boolean allowedTime = (isDay && info.sleepAtDay()) || (!isDay && info.sleepAtNight());

        int roofMax = Math.max(1, info.sleepSearchCeilingScanMaxBlocks());
        boolean roofedHere = info.sleepRequiresRoof() && this.isRoofed(pos, roofMax);

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

        ArrayList<String> lines = new ArrayList<>();
        lines.add(top);
        lines.add("--------------------------------");

        lines.add("GOALS (action):");
        lines.addAll(dumpGoalsSafe(this.goalSelector));

        lines.add("--------------------------------");
        lines.add("GOALS (target):");
        lines.addAll(dumpGoalsSafe(this.targetSelector));

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

    // ================================================================
    // 7.5) FLEE STATE (server-side)
    // ================================================================
    private int fleeTicksRemaining = 0;
    private long fleeCooldownUntil = 0L;
    private LivingEntity fleeThreat = null;

    public boolean isFleeing() {
        return !this.level().isClientSide && fleeTicksRemaining > 0;
    }

    public LivingEntity getFleeThreat() {
        return fleeThreat;
    }

    private boolean isFleeOnCooldown() {
        return nowServer() < fleeCooldownUntil;
    }

    private void startFlee(LivingEntity threat) {
        startFlee(threat, false);
    }

    private void startFlee(LivingEntity threat, boolean bypassCooldown) {
        if (this.level().isClientSide) return;

        CatoMobSpeciesInfo info = getSpeciesInfo();
        if (!info.fleeEnabled()) return;
        if (info.fleeDurationTicks() <= 0) return;

        if (!bypassCooldown && isFleeOnCooldown()) return;

        this.fleeThreat = threat;
        this.fleeTicksRemaining = info.fleeDurationTicks();

        // Flee overrides combat/retaliation while active
        this.angerTime = 0;
        this.setTarget(null);
        this.setAggressive(false);
        this.setLastHurtByMob(null);
        clearAttackState();

        this.getNavigation().stop();
    }

    private void tickFleeLowHealthServer() {
        if (this.level().isClientSide) return;

        final CatoMobSpeciesInfo info = infoServer();
        if (!info.fleeEnabled() || !info.fleeOnLowHealth()) return;

        if (isFleeing()) return;
        if (isFleeOnCooldown()) return;

        if (this.getHealth() > info.fleeLowHealthThreshold()) return;

        LivingEntity threat = this.getLastHurtByMob();
        if (threat == null) threat = this.getTarget();

        if (threat != null) {
            startFlee(threat, false);
            triggerGroupFlee(threat);
        }
    }

    private void triggerGroupFlee(LivingEntity threat) {
        if (this.level().isClientSide) return;

        final CatoMobSpeciesInfo info = infoServer();
        if (!info.groupFleeEnabled()) return;

        double r = Math.max(0.0D, info.groupFleeRadius());
        int max = Math.max(0, info.groupFleeMaxAllies());
        if (r <= 0.0D || max <= 0) return;

        var box = this.getBoundingBox().inflate(r, 4.0D, r);

        final boolean anyCato = info.groupFleeAnyCatoMobAllies();
        final var allowedTypes = info.groupFleeAllyTypes(); // may be empty

        // Collect candidates
        List<? extends LivingEntity> candidates;
        if (anyCato) {
            candidates = this.level().getEntitiesOfClass(
                    CatoBaseMob.class,
                    box,
                    e -> e != this && e.isAlive()
            );
        } else if (allowedTypes != null && !allowedTypes.isEmpty()) {
            candidates = this.level().getEntitiesOfClass(
                    CatoBaseMob.class,
                    box,
                    e -> e != this && e.isAlive() && allowedTypes.contains(e.getType())
            );
        } else {
            // No ally definition -> don't spread at all
            return;
        }

        int triggered = 0;

        for (var entity : candidates) {
            if (triggered >= max) break;
            if (!(entity instanceof CatoBaseMob ally)) continue;

            // LOS requirement: ally must be able to see the hurt mob (THIS)
            if (!ally.hasLineOfSight(this)) continue;

            if (ally.isFleeing()) continue;

            boolean bypassCooldown = info.groupFleeBypassCooldown();

            // optional: ensure ally can actually flee at all
            if (!ally.infoServer().fleeEnabled()) continue;

            ally.startFleeFromAlly(threat, bypassCooldown);
            triggered++;
        }
    }

    void startFleeFromAlly(LivingEntity threat, boolean bypassCooldown) {
        startFlee(threat, bypassCooldown);
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

    public boolean isSleepSearchOnCooldown() {
        if (this.level().isClientSide) return false;
        return nowServer() < sleepSearchCooldownUntil;
    }

    public void startSleepSearchCooldown(int ticks) {
        // use cached tick time when available
        sleepSearchCooldownUntil = nowServer() + Math.max(0, ticks);
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

        int age = Math.max(0, this.attackAnimAgeTicks);

        int startDelay = Math.max(0, this.currentAttackMoveStartDelay);
        int stopAfter = this.currentAttackMoveStopAfter;

        if (this.currentAttackMoveDuringAnim) {
            if (age < startDelay) return false;
            if (stopAfter > 0 && age >= stopAfter) return false;
            return true;
        } else {
            if (stopAfter > 0) return age < stopAfter;
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
            nextSleepAttemptTick = nowServer() + sleepAttemptIntervalTicks();
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

        // use cached tick time when available
        long now = nowServer();
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
    public boolean startTimedAttack(LivingEntity target, CatoAttackId id) {
        if (target == null || !target.isAlive()) return false;
        if (this.isSleeping()) return false;

        // Don’t restart if already attacking
        if (this.attackTicksUntilHit > 0 || this.attackAnimTicksRemaining > 0 || this.queuedAttackTarget != null) {
            return false;
        }

        final CatoMobSpeciesInfo info = infoServer();

        // Pick config depending on id
        final double triggerRange;
        final double hitRange;
        final int animTotal;
        final int hitDelay;
        final double damage;
        final boolean moveDuring;
        final int moveStart;
        final int moveStop;

        switch (id) {
            case MELEE_SPECIAL -> {
                if (!info.meleeSpecialEnabled()) return false;

                triggerRange = info.meleeSpecialTriggerRange();
                hitRange     = info.meleeSpecialHitRange();
                animTotal    = info.meleeSpecialAnimTotalTicks();
                hitDelay     = info.meleeSpecialHitDelayTicks();
                damage       = info.meleeSpecialDamage();
                moveDuring   = info.meleeSpecialMoveDuringAttackAnimation();
                moveStart    = info.meleeSpecialMoveStartDelayTicks();
                moveStop     = info.meleeSpecialMoveStopAfterTicks();
            }
            case MELEE_NORMAL -> {
                triggerRange = info.attackTriggerRange();
                hitRange     = info.attackHitRange();
                animTotal    = info.attackAnimTotalTicks();
                hitDelay     = info.attackHitDelayTicks();
                damage       = info.attackDamage(); // normal melee uses base attack damage
                moveDuring   = info.moveDuringAttackAnimation();
                moveStart    = info.attackMoveStartDelayTicks();
                moveStop     = info.attackMoveStopAfterTicks();
            }
            default -> {
                // You haven't implemented ranged yet
                return false;
            }
        }

        // Trigger range gate
        if (triggerRange > 0.0D) {
            double maxTriggerDistSqr = triggerRange * triggerRange;
            if (this.distanceToSqr(target) > maxTriggerDistSqr) return false;
        }

        // Commit attack id (server + synced for client animation)
        this.currentAttackId = id;
        this.setAttackIdSynced(id);

        this.currentAttackDamage = Math.max(0.0D, damage);

        double hr = (hitRange <= 0.0D) ? 2.0D : hitRange;
        this.currentAttackHitRangeSqr = hr * hr;

        this.currentAttackMoveDuringAnim = moveDuring;

        int start = Math.max(0, moveStart);
        int stop = moveStop;
        if (stop > 0 && stop < start) stop = start;

        this.currentAttackMoveStartDelay = start;
        this.currentAttackMoveStopAfter = stop;

        this.queuedAttackTarget = target;

        int total = Math.max(1, animTotal);
        int delay = Math.max(0, hitDelay);
        if (delay >= total) delay = total - 1; // keep hit inside anim window

        this.attackAnimTicksRemaining = total;
        this.attackTicksUntilHit = delay;

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

        this.currentAttackId = null;
        this.setAttackIdSynced(null);

        this.currentAttackDamage = 0.0D;
        this.currentAttackHitRangeSqr = 4.0D;
        this.currentAttackMoveDuringAnim = false;
        this.currentAttackMoveStartDelay = 0;
        this.currentAttackMoveStopAfter = 0;

        this.setAttacking(false);
        this.attackAnimAgeTicks = 0;
        this.onAttackAnimationEnd();
    }

    // Which attack is currently running
    @Nullable
    protected CatoAttackId currentAttackId = null;

    // Cached parameters for the currently running attack
    protected double currentAttackDamage = 0.0D;
    protected double currentAttackHitRangeSqr = 4.0D; // default 2 blocks
    protected boolean currentAttackMoveDuringAnim = false;
    protected int currentAttackMoveStartDelay = 0;
    protected int currentAttackMoveStopAfter = 0;

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

        // ------------------------------------------------------------
        // Flee / panic (should override combat + wander)
        // ------------------------------------------------------------
        // This goal should internally decide:
        // - fleeOnHurt -> start fleeing immediately when hit
        // - fleeOnLowHealth -> start fleeing once HP <= threshold
        // - duration + cooldown
        // - override retaliation for NEUTRAL/HOSTILE by clearing target + anger while fleeing
        if (prio.enableFlee && speciesInfo.fleeEnabled()) {
            this.goalSelector.addGoal(prio.flee, new CatoFleeGoal(this));
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
            case NEUTRAL -> setupNeutralGoals();
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

        // Rain shelter: centralized priority (no computed collisions)
        if (info.rainShelterEnabled()) {
            this.goalSelector.addGoal(prio.rainShelter, new CatoRainShelterGoal(this));
        }

        // Fun swim (optional, species-controlled)
        if (info.funSwimEnabled()) {
            this.goalSelector.addGoal(prio.funSwim, new CatoFunSwimGoal(this));
        }

        // Normal wander (fallback idle behavior)
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

        // Retaliation target selection (GATED so it won't fire during flee)
        this.targetSelector.addGoal(prio.targetHurtBy, new CatoGatedHurtByTargetGoal(this));

        // Melee attack goal (you should also gate canUse() inside the goal if fleeing — see note below)
        this.goalSelector.addGoal(prio.meleeAttack, new CatoMeleeAttackGoal(this, chaseSpeed, true, cooldown));
    }

    protected void setupHostileGoals() {
        int cooldown = getSpeciesInfo().attackCooldownTicks();
        double chaseSpeed = getSpeciesInfo().chaseSpeedModifier();
        CatoGoalPriorityProfile prio = getGoalPriorities();

        // Retaliation target selection (species/temperament-gated)
        this.targetSelector.addGoal(prio.targetHurtBy, new CatoGatedHurtByTargetGoal(this));

        // Nearest-player target selection (hostile-gated; later you can also gate by flee)
        this.targetSelector.addGoal(
                prio.targetNearestPlayer,
                new CatoGatedNearestPlayerTargetGoal(this)
        );

        // Main melee chase/attack
        this.goalSelector.addGoal(prio.meleeAttack, new CatoMeleeAttackGoal(this, chaseSpeed, false, cooldown));
    }

    // ================================================================
    // 16) DAMAGE HOOK -> WAKE + SET ANGER STATE (species-driven)
    // ================================================================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Wake sleeping mobs immediately if damage is allowed to wake them
        if (!this.level().isClientSide && getSpeciesInfo().wakeOnDamage()) {
            wakeUp();
        }

        boolean result = super.hurt(source, amount);

        if (!this.level().isClientSide && result && source.getEntity() instanceof LivingEntity attacker) {
            CatoMobSpeciesInfo info = getSpeciesInfo();

            // ------------------------------------------------------------
            // 1) FLEE OVERRIDES EVERYTHING
            //    IMPORTANT FIX: bypass cooldown when fleeing is triggered by being hurt
            // ------------------------------------------------------------
            if (info.fleeEnabled() && info.fleeOnHurt()) {
                startFlee(attacker, true);      // bypass cooldown on hurt
                triggerGroupFlee(attacker);     // <-- ADD THIS LINE (step 3.3)
                return result;                  // IMPORTANT: do NOT retaliate
            }

            // ------------------------------------------------------------
            // 2) RETALIATION (neutral / hostile only)
            // ------------------------------------------------------------
            if (info.retaliateWhenAngered()
                    && info.retaliationDurationTicks() > 0
                    && (info.temperament() == CatoMobTemperament.NEUTRAL
                    || info.temperament() == CatoMobTemperament.HOSTILE)) {

                this.angerTime = info.retaliationDurationTicks();
                this.setTarget(attacker);
                this.setLastHurtByMob(attacker);
                this.setAggressive(true);
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
        final CatoMobSpeciesInfo info = infoServer();
        final Level level = this.level();
        final var rng = this.getRandom();

        // ✅ tick-local cached position/time (safe fallback if cache not set)
        final BlockPos pos = posServer();
        final long now = nowServer();

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
                if (level.isDay() && level.canSeeSky(pos)) shouldWake = true;
            }

            // Time window flip handling (day<->night): grace nap then wake
            boolean isDayNow = level.isDay();
            boolean allowedTimeNow = (isDayNow && info.sleepAtDay()) || (!isDayNow && info.sleepAtNight());

            if (!shouldWake) {
                if (!allowedTimeNow) {
                    if (timeWindowWakeGraceTicks <= 0) {
                        int gMin = Math.max(1, info.sleepTimeWindowWakeGraceMinTicks());
                        int gMax = Math.max(gMin, info.sleepTimeWindowWakeGraceMaxTicks());
                        timeWindowWakeGraceTicks = gMin + rng.nextInt(gMax - gMin + 1);
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

                if (rng.nextFloat() < c) {
                    int min = Math.max(1, info.sleepMinTicks());
                    int max = Math.max(min, info.sleepMaxTicks());
                    sleepTicksRemaining = min + rng.nextInt(max - min + 1);
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

                // ✅ use cached now (safe fallback)
                nextSleepAttemptTick = now + sleepAttemptIntervalTicks();
            }

            return;
        }

        // ------------------------------------------------------------
        // B) NOT SLEEPING: decide/search/sleep
        // ------------------------------------------------------------

        if (this.isSleepSearching()) return;
        if (this.getTarget() != null || this.isAggressive()) return;

        // Physical constraints
        if (!this.onGround() && !info.sleepAllowedOnWaterSurface()) return;
        if (this.isInWater() && !info.sleepAllowedOnWaterSurface()) return;
        if (this.isUnderWater() && info.sleepAllowedOnWaterSurface()) return;

        // Time window constraint
        boolean isDay = level.isDay();
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
            // ✅ use cached pos
            if (!this.isRoofed(pos, roofMax)) {
                return;
            }
        }

        // Sleep in place
        this.getNavigation().stop();
        this.setDeltaMovement(this.getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));

        setSleeping(true);
        sleepDesireTicks = 0;
        nextSleepAttemptTick = 0L;
        timeWindowWakeGraceTicks = 0;

        int min = Math.max(1, info.sleepMinTicks());
        int max = Math.max(min, info.sleepMaxTicks());
        sleepTicksRemaining = min + rng.nextInt(max - min + 1);
    }

    // ================================================================
    // 18) ROOF HELPERS (used by sleep + sleep search goal)
    // ================================================================

    /** @return distance to first non-air, non-fluid block above pos; -1 if none within maxHeight */
    protected int roofDistance(BlockPos pos, int maxHeight) {
        final Level level = this.level();
        final int max = Math.max(0, maxHeight);
        if (max <= 0) return -1;

        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();

        // Reuse one mutable to avoid allocating BlockPos every dy
        final BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();

        for (int dy = 1; dy <= max; dy++) {
            check.set(x, y + dy, z);

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
    private static final int SLEEP_SPOT_BLACKLIST_MAX_ENTRIES = 64;
    private static final int SLEEP_SPOT_BLACKLIST_TRIM_BATCH = 8;

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
            // Only trim when adding a *new* key would overflow
            if (!failedSleepSpotStrikes.containsKey(key) && failedSleepSpotStrikes.size() >= SLEEP_SPOT_BLACKLIST_MAX_ENTRIES) {
                trimSleepSpotBlacklistIfNeeded();
            }

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

    private void trimSleepSpotBlacklistIfNeeded() {
        // ensure we have at least ONE free slot for an incoming new key
        int target = SLEEP_SPOT_BLACKLIST_MAX_ENTRIES - 1;

        if (failedSleepSpotStrikes.size() <= target) return;

        int needToRemove = failedSleepSpotStrikes.size() - target;
        int toRemove = Math.min(
                Math.max(needToRemove, 1),
                Math.min(SLEEP_SPOT_BLACKLIST_TRIM_BATCH, failedSleepSpotStrikes.size())
        );

        var it = failedSleepSpotStrikes.keySet().iterator();
        for (int i = 0; i < toRemove && it.hasNext(); i++) {
            it.next();
            it.remove();
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
    // 20) MAIN SERVER TICK (sleep + flee + anger + timed attack damage)
    // ================================================================
    @Override
    public void aiStep() {
        if (!this.level().isClientSide && !surfaceMalusApplied) {
            applySurfacePathfindingBiasOnce();
        }

        super.aiStep();

        if (this.level().isClientSide) {
            blink().tick(allowBlink());
            return;
        }

        // start cache (server-only)
        this.serverTickNow = this.level().getGameTime();
        this.serverTickPos = this.blockPosition();
        this.serverTickInfo = getSpeciesInfo();     // cache once
        this.serverTickCacheValid = true;

        try {
            final CatoMobSpeciesInfo info = infoServer();
            final long now = this.serverTickNow;
            final BlockPos pos = this.serverTickPos;

            tickSleepSpotBlacklistDecayServer();

            if (sleepDesireTicks > 0) sleepDesireTicks--;

            if (this.homePos == null && info.stayWithinHomeRadius()) {
                this.homePos = pos;
            }

            tickSleepServer();

            if (this.isSleeping()) {
                clearAttackState();
                this.setTarget(null);
                this.setLastHurtByMob(null);
                this.setAggressive(false);
                tickAiDebugServer();
                return;
            }

            tickExitWaterUrgencyServer();
            tickFleeLowHealthServer();

            if (fleeTicksRemaining > 0) {
                fleeTicksRemaining--;
                this.setMoveMode(this.getNavigation().isInProgress() ? MOVE_RUN : MOVE_IDLE);

                if (fleeTicksRemaining <= 0) {
                    fleeCooldownUntil = now + Math.max(0, info.fleeCooldownTicks());
                    fleeThreat = null;

                    this.angerTime = 0;
                    this.setTarget(null);
                    this.setAggressive(false);
                    this.setLastHurtByMob(null);
                    clearAttackState();

                    this.getNavigation().stop();
                    this.setMoveMode(MOVE_IDLE);
                }
            }

            if (angerTime > 0) {
                angerTime--;
            } else if (getTarget() != null) {
                if (info.temperament() != CatoMobTemperament.HOSTILE) {
                    this.setTarget(null);
                    this.setAggressive(false);
                    this.setLastHurtByMob(null);

                    this.getNavigation().stop();
                    this.setMoveMode(MOVE_IDLE);

                    clearAttackState();
                    stopHurtByGoalsSafe();
                }
            }

            boolean angryNow = (this.angerTime > 0 && this.getTarget() != null);
            this.setVisuallyAngry(angryNow);

            // ============================================================
            // ✅ TIMED ATTACK SYSTEM (fixed: delay==0 + proper cleanup)
            // ============================================================

            // If we lost our target mid-attack, cancel cleanly
            if (this.getTarget() == null && (this.attackTicksUntilHit >= 0 || this.attackAnimTicksRemaining > 0)) {
                clearAttackState();
            }

            // ---------------------------
            // Timed hit (supports delay==0)
            // ---------------------------
            if (this.attackTicksUntilHit >= 0) {
                if (this.attackTicksUntilHit == 0) {
                    // Time to apply hit now
                    if (this.queuedAttackTarget != null && this.queuedAttackTarget.isAlive()) {
                        if (this.distanceToSqr(this.queuedAttackTarget) <= this.currentAttackHitRangeSqr) {
                            this.queuedAttackTarget.hurt(
                                    this.damageSources().mobAttack(this),
                                    (float) this.currentAttackDamage
                            );
                        }
                    }

                    // consume hit
                    this.queuedAttackTarget = null;
                    this.attackTicksUntilHit = -1;
                } else {
                    this.attackTicksUntilHit--;
                }
            }

            // ---------------------------
            // Animation lifetime (end = clearAttackState)
            // ---------------------------
            if (this.attackAnimTicksRemaining > 0) {
                this.attackAnimTicksRemaining--;
                this.attackAnimAgeTicks++;

                if (this.attackAnimTicksRemaining == 0) {
                    // IMPORTANT: clears synced attack id + cached params + attacking flag
                    clearAttackState();
                }
            } else {
                this.attackAnimAgeTicks = 0;
            }

            // ============================================================
            // Look at target if angry
            // ============================================================
            if (this.angerTime > 0 && this.getTarget() != null) {
                int maxYaw = this.getMaxHeadYRot();
                int maxPitch = this.getMaxHeadXRot();
                if (maxYaw != 0 || maxPitch != 0) {
                    this.getLookControl().setLookAt(this.getTarget(), (float) maxYaw, (float) maxPitch);
                }
            }

            tickAiDebugServer();
        } finally {
            this.serverTickCacheValid = false;
            this.serverTickInfo = null;
        }
    }

    /**
     * Forcibly stops any running CatoGatedHurtByTargetGoal entries in targetSelector.
     *
     * Why reflection?
     * GoalSelector internals and wrapper classes vary across versions/mappings.
     * We try:
     *  1) selector.getAvailableGoals() if present
     *  2) otherwise: scan Set fields that look like the internal wrapped-goal set
     */
    @SuppressWarnings("unchecked")
    private void stopHurtByGoalsSafe() {
        try {
            // 1) Preferred: public getAvailableGoals()
            var m = this.targetSelector.getClass().getMethod("getAvailableGoals");
            Object result = m.invoke(this.targetSelector);

            if (result instanceof Set<?> set) {
                stopHurtByInWrappedSet(set);
                return;
            }
        } catch (Throwable ignored) { }

        try {
            // 2) Fallback: find a Set field that likely holds wrapped goals
            for (var f : this.targetSelector.getClass().getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(f.getType())) continue;

                f.setAccessible(true);
                Object val = f.get(this.targetSelector);

                if (val instanceof Set<?> set && !set.isEmpty()) {
                    stopHurtByInWrappedSet(set);
                    return;
                }
            }
        } catch (Throwable ignored) { }
    }

    private void stopHurtByInWrappedSet(Set<?> wrappedGoalSet) {
        for (Object wrapped : wrappedGoalSet) {
            try {
                Goal goal = extractGoalFromWrapped(wrapped);
                if (goal instanceof CatoGatedHurtByTargetGoal) {
                    // stop the wrapper if possible (better), else stop the goal itself
                    if (!tryStopWrapped(wrapped)) {
                        goal.stop();
                    }
                }
            } catch (Throwable ignored) {
                // skip bad entries; this is best-effort
            }
        }
    }

    @Nullable
    private Goal extractGoalFromWrapped(Object wrapped) {
        // Common wrapper API: getGoal()
        try {
            var mg = wrapped.getClass().getMethod("getGoal");
            Object g = mg.invoke(wrapped);
            if (g instanceof Goal goal) return goal;
        } catch (Throwable ignored) { }

        // Common wrapper field: goal
        try {
            var fg = wrapped.getClass().getDeclaredField("goal");
            fg.setAccessible(true);
            Object g = fg.get(wrapped);
            if (g instanceof Goal goal) return goal;
        } catch (Throwable ignored) { }

        return null;
    }

    private boolean tryStopWrapped(Object wrapped) {
        // Some wrappers expose stop()
        try {
            var ms = wrapped.getClass().getMethod("stop");
            ms.invoke(wrapped);
            return true;
        } catch (Throwable ignored) { }

        // Or a "stop" field is unlikely; we keep it simple.
        return false;
    }

    // ================================================================
    // 20.5) TICK-LOCAL CACHE (server-only; performance)
    // ================================================================
    private long serverTickNow = 0L;
    private BlockPos serverTickPos = BlockPos.ZERO;
    private boolean serverTickCacheValid = false;

    // cached species info for this server tick
    @Nullable
    private CatoMobSpeciesInfo serverTickInfo = null;

    /** Cached server tick time. Safe fallback if called outside aiStep. */
    protected final long nowServer() {
        return serverTickCacheValid ? serverTickNow : this.level().getGameTime();
    }

    /** Cached server tick position. Safe fallback if called outside aiStep. */
    protected final BlockPos posServer() {
        return serverTickCacheValid ? serverTickPos : this.blockPosition();
    }

    /** Cached server tick species info. Safe fallback if called outside aiStep. */
    protected final CatoMobSpeciesInfo infoServer() {
        // Only trust cached value during the server aiStep() window
        if (serverTickCacheValid && serverTickInfo != null) {
            return serverTickInfo;
        }
        return getSpeciesInfo();
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
