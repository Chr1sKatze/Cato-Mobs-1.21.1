package com.chriskatze.catomobs.entity;

import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import com.chriskatze.catomobs.registry.CMEntities;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.Set;

/**
 *
 * Purpose:
 * A concrete mob implementation using your shared base class (CatoBaseMob)
 * + GeckoLib animations.
 *
 * Responsibilities in THIS class:
 * - provide Pikachu's species info (stats + movement + sleep settings)
 * - optional movement "feel" tweaks (water input scaling + bobbing damping)
 * - head rotation limits (used by your GeckoLib model head_ai bone driver)
 * - client-only blink state (cosmetic overlay)
 * - synced "attacking" flag for GeckoLib attack animation selection
 * - GeckoLib controllers (main locomotion, angry overlay, blink overlay)
 *
 * Responsibilities NOT in this class:
 * - AI goals, anger timers, timed attack hit logic, sleep ticking -> handled by CatoBaseMob and goals
 * - actual rendering/model selection -> handled by renderer/model classes
 */
public class PikachuMaleMob extends CatoBaseMob implements GeoEntity {

    /**
     * CatoBaseMob calls getSpeciesInfo() for:
     * - attributes (health/damage/speed/range/gravity)
     * - goal setup tuning (wander settings, attack cooldown, sleep settings, etc.)
     */
    @Override
    protected CatoMobSpeciesInfo getSpeciesInfo() {
        return SPECIES_INFO;
    }

    // --------------------------------------------------------------
    // Species info (movement, temperament, size, stats & attack params)
    // --------------------------------------------------------------
    /**
     * One big configuration object that defines this mob's behavior and tuning.
     * Your base/goal code reads values from this instead of hardcoding behavior.
     *
     * Examples:
     * - movementType = LAND (so base sets up land wander goals)
     * - temperament = NEUTRAL_RETALIATE_SHORT (so it fights back briefly when hurt)
     * - sleepEnabled + sleepRequiresRoof + chance/timers (sleep system behavior)
     * - wander speeds + run chance + radii (wander goal behavior)
     * - attack trigger/hit ranges + timing (timed attack system)
     */
    public static final CatoMobSpeciesInfo SPECIES_INFO = new CatoMobSpeciesInfo(
            CatoMobMovementType.LAND,
            CatoMobTemperament.NEUTRAL_RETALIATE_SHORT,
            CatoMobSizeCategory.SMALL,
            8.0D,
            1.0D,
            0.30D,
            16.0D,
            0.08D,
            3.0D,
            3.0D,
            70,
            60,
            30,
            1.0D,
            1.35D,
            0.25F,
            3.0D,
            32.0D,
            true,
            96.0D,
            16.0D,
            1.8D,
            true,
            true,
            false,
            20 * 5,
            0.80f,
            0.45f,
            20 * 90,
            20 * 150,
            100,
            200,
            2,
            2,
            true,
            32,
            4,
            1,
            3,
            true,
            Set.of(CMEntities.PIKACHU_MALE.get()),
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            300,
            32,
            1,
            12,
            20 * 6,
            20 * 3,
            2.0D,
            1.0D,
            true,
            true,
            400
    );

    // ================================================================
    // HEAD TRACKING CONFIG — EASY TUNING SECTION
    // ================================================================
    /**
     * These toggles control whether the mob is allowed to rotate its head
     * while in certain "busy" states. Your GeckoLib model reads the head limits
     * via getMaxHeadXRot/getMaxHeadYRot and turns off rotation when those return 0.
     */
    public static final boolean HEAD_TURN_WHILE_RUNNING = true;
    public static final boolean HEAD_TURN_WHILE_ATTACKING = true;

    /** Base maximum look pitch (up/down) in degrees. */
    public static final int BASE_MAX_HEAD_PITCH = 20;

    /** Base maximum look yaw (left/right) in degrees. */
    public static final int BASE_MAX_HEAD_YAW = 30;

    // ================================================================
    // WATER MOVEMENT CONFIG — BOBBING CONTROL
    // ================================================================
    /**
     * Optional "water smoothing":
     * - scales horizontal swim input by species multiplier
     * - dampens small vertical velocity when idle to reduce bobbing
     *
     * This is applied in travel(), not via AI goals.
     */
    public static final boolean WATER_DAMPING_ENABLED = true;
    public static final double WATER_VERTICAL_DAMPING = 0.7;
    public static final double WATER_VERTICAL_SPEED_CLAMP = 0.4;
    public static final double WATER_DAMPING_APPLY_THRESHOLD = 0.2;

    // --------------------------------------------------------------
    // Helper
    // --------------------------------------------------------------
    /**
     * Convenience wrapper used by head rotation gating.
     * Note: "attacking" is a synced flag used mainly for client animation selection.
     */
    public boolean isAttackingClient() {
        return this.isAttacking();
    }

    /**
     * Vanilla look control uses getMaxHeadXRot/getMaxHeadYRot as limits.
     * Your GeckoLib head_ai bone driver ALSO uses these values to clamp rotations.
     *
     * Returning 0 effectively disables head rotation.
     */
    @Override
    public int getMaxHeadXRot() {
        // Sleeping MUST have zero head rotation (prevents additive head pitch during sleep anim)
        if (this.isSleeping()) return 0;

        // Optional: disable head turning while attacking/running.
        if (isAttackingClient() && !HEAD_TURN_WHILE_ATTACKING) return 0;
        if (this.getMoveMode() == CatoBaseMob.MOVE_RUN && !HEAD_TURN_WHILE_RUNNING) return 0;

        return BASE_MAX_HEAD_PITCH;
    }

    /**
     * Same as getMaxHeadXRot, but for yaw (left/right).
     */
    @Override
    public int getMaxHeadYRot() {
        // Sleeping MUST have zero head rotation (prevents additive head yaw during sleep anim)
        if (this.isSleeping()) return 0;

        if (isAttackingClient() && !HEAD_TURN_WHILE_ATTACKING) return 0;
        if (this.getMoveMode() == CatoBaseMob.MOVE_RUN && !HEAD_TURN_WHILE_RUNNING) return 0;

        return BASE_MAX_HEAD_YAW;
    }

    /**
     * travel() is called by the engine to apply movement each tick.
     * We override it to apply special water behavior:
     * - scale horizontal input with waterSwimSpeedMultiplier
     * - optionally damp small vertical bobbing when idle (navigation done)
     *
     * Important:
     * This does not replace AI/pathing; it only changes how movement is applied.
     */
    @Override
    public void travel(Vec3 travelVector) {
        if (this.isInWater()) {
            // If the player/AI provides X/Z input, we can scale it.
            boolean hasMoveInput = travelVector.x != 0.0D || travelVector.z != 0.0D;

            Vec3 scaledInput = travelVector;
            if (hasMoveInput) {
                double mul = this.getSpeciesInfo().waterSwimSpeedMultiplier();
                if (mul > 0.0D && mul != 1.0D) {
                    // Only scale horizontal input (X/Z), keep Y as-is.
                    scaledInput = new Vec3(travelVector.x * mul, travelVector.y, travelVector.z * mul);
                }
            }

            // Let vanilla handle actual swimming movement with our modified input.
            super.travel(scaledInput);

            // When idle (no active navigation), damp small vertical motion to reduce bobbing.
            if (WATER_DAMPING_ENABLED && this.getNavigation().isDone()) {
                Vec3 motion = this.getDeltaMovement();
                double vy = motion.y;

                // Only damp if we are already moving slowly vertically (so we don't fight real jumps).
                if (Math.abs(vy) < WATER_DAMPING_APPLY_THRESHOLD) {
                    double dampedY = vy * WATER_VERTICAL_DAMPING;
                    double clampedY = Mth.clamp(dampedY, -WATER_VERTICAL_SPEED_CLAMP, WATER_VERTICAL_SPEED_CLAMP);
                    this.setDeltaMovement(motion.x, clampedY, motion.z);
                }
            }

            return;
        }

        // Not in water -> normal movement.
        super.travel(travelVector);
    }

    // --------------------------------------------------------------
    // Random blinking state (client-only)
    // --------------------------------------------------------------
    /**
     * Cosmetic blink overlay:
     * - runs only on the client (see aiStep())
     * - uses a random cooldown, then plays blink for a few ticks
     * - GeckoLib blink controller reads this state to trigger the animation
     */
    private int blinkCooldown = 0;
    private int blinkTicksRemaining = 0;
    private boolean blinking = false;
    private boolean blinkJustStarted = false;

    /** Picks a new random delay until the next blink (roughly 1–3 seconds). */
    private void resetBlinkCooldown() {
        this.blinkCooldown = 20 + this.getRandom().nextInt(40);
    }

    /** Starts a blink "clip" that lasts a few ticks. */
    private void startBlink() {
        this.blinking = true;
        this.blinkJustStarted = true;
        this.blinkTicksRemaining = 6;
        resetBlinkCooldown();
    }

    /**
     * Client tick logic for blinking:
     * - if blinking, count down the remaining blink time
     * - if not blinking, count down to next blink
     */
    private void tickBlinkClient() {
        if (blinking) {
            if (blinkTicksRemaining > 0) blinkTicksRemaining--;
            if (blinkTicksRemaining <= 0) blinking = false;
        } else {
            if (blinkCooldown > 0) blinkCooldown--;
            if (blinkCooldown <= 0) startBlink();
        }
    }

    /** Used by GeckoLib controller to know whether blink overlay should be active. */
    public boolean isBlinking() {
        return blinking;
    }

    /**
     * One-shot flag:
     * GeckoLib controller consumes this to force-reset and replay blink animation exactly once.
     */
    public boolean consumeBlinkJustStarted() {
        if (blinkJustStarted) {
            blinkJustStarted = false;
            return true;
        }
        return false;
    }

    // --------------------------------------------------------------
    // Synced attack state (used for animation selection)
    // --------------------------------------------------------------
    /**
     * This synced flag is separate from the server-side timed-attack logic in CatoBaseMob.
     * - Server sets it true at attack animation start, false when attack ends.
     * - Client reads it to play ATTACK animation in movementController.
     */
    private static final EntityDataAccessor<Boolean> DATA_ATTACKING =
            SynchedEntityData.defineId(PikachuMaleMob.class, EntityDataSerializers.BOOLEAN);

    /** Read the synced attacking flag. */
    private boolean isAttacking() { return this.entityData.get(DATA_ATTACKING); }

    /** Write the synced attacking flag (server-side only in your hooks). */
    private void setAttacking(boolean attacking) { this.entityData.set(DATA_ATTACKING, attacking); }

    // --------------------------------------------------------------
    // GeckoLib animation cache (required by GeoEntity)
    // --------------------------------------------------------------
    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    public PikachuMaleMob(EntityType<? extends CatoBaseMob> type, Level level) {
        super(type, level);
        // Initialize blinking random timer.
        this.resetBlinkCooldown();
    }

    /**
     * Register synced entity data fields.
     * CatoBaseMob already defines:
     * - MOVE_MODE, VISUALLY_ANGRY, SLEEPING, etc.
     * This adds Pikachu's DATA_ATTACKING flag.
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ATTACKING, false);
    }

    // --------------------------------------------------------------
    // GeckoLib animation definitions (string names match your .animation.json)
    // --------------------------------------------------------------
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.pikachu.ground_idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.pikachu.ground_walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.pikachu.ground_run");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.pikachu.physical");
    private static final RawAnimation ANGRY = RawAnimation.begin().thenLoop("animation.pikachu.angry");
    private static final RawAnimation BLINK = RawAnimation.begin().thenPlay("animation.pikachu.blink");
    private static final RawAnimation SURFACE_IDLE = RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_idle");
    private static final RawAnimation SURFACE_SWIM = RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_swim");
    private static final RawAnimation SLEEP = RawAnimation.begin().thenLoop("animation.pikachu.sleep");

    /**
     * MAIN locomotion controller:
     * Priority order:
     * 1) sleep animation overrides everything
     * 2) attack animation overrides locomotion
     * 3) water locomotion (idle vs swim)
     * 4) ground locomotion based on state.isMoving() and MOVE_MODE (walk/run)
     *
     * Key design:
     * - MOVE_MODE is authoritative and goal-driven (wander/attack goals set it).
     * - state.isMoving() comes from GeckoLib/vanilla movement detection.
     */
    private <E extends GeoEntity> PlayState movementController(AnimationState<E> state) {
        PikachuMaleMob mob = (PikachuMaleMob) state.getAnimatable();

        // ------------------------------------------------------------
        // Hard state overrides
        // ------------------------------------------------------------

        // Sleeping always wins (no other animations should play).
        if (mob.isSleeping()) {
            state.setAndContinue(SLEEP);
            return PlayState.CONTINUE;
        }

        // Attacking overrides walk/run/idle (plays a one-shot anim).
        if (mob.isAttacking()) {
            state.setAndContinue(ATTACK);
            return PlayState.CONTINUE;
        }

        boolean moving = state.isMoving();
        boolean inWater = mob.isInWater();

        // ------------------------------------------------------------
        // Water movement
        // ------------------------------------------------------------
        if (inWater) {
            // If GeckoLib thinks we're moving -> swim loop, else idle float loop.
            state.setAndContinue(moving ? SURFACE_SWIM : SURFACE_IDLE);
            return PlayState.CONTINUE;
        }

        // ------------------------------------------------------------
        // Ground animation selection (AUTHORITATIVE: goal-driven MOVE_MODE)
        // ------------------------------------------------------------
        if (moving) {
            // MOVE_MODE is synced from server and set by goals like CatoWanderGoal / CatoMeleeAttackGoal.
            int mode = mob.getMoveMode();
            boolean runAnim = (mode == CatoBaseMob.MOVE_RUN);

            state.setAndContinue(runAnim ? RUN : WALK);
        } else {
            state.setAndContinue(IDLE);
        }

        return PlayState.CONTINUE;
    }

    /**
     * Overlay controller:
     * Plays a looping "angry" overlay animation when the base class says
     * the mob is visually angry (anger timer active + has target).
     *
     * Returns STOP when not angry so GeckoLib stops this controller.
     */
    private <E extends GeoEntity> PlayState angryOverlayController(AnimationState<E> state) {
        PikachuMaleMob mob = (PikachuMaleMob) state.getAnimatable();

        if (mob.isVisuallyAngry()) {
            state.setAndContinue(ANGRY);
            return PlayState.CONTINUE;
        }

        return PlayState.STOP;
    }

    /**
     * Overlay controller:
     * Plays a short blink animation when isBlinking() is true.
     *
     * The consumeBlinkJustStarted() mechanism ensures the blink anim is force-reset
     * at the moment it starts (so it always plays from frame 0).
     */
    private <E extends GeoEntity> PlayState blinkController(AnimationState<E> state) {
        PikachuMaleMob mob = (PikachuMaleMob) state.getAnimatable();

        if (!mob.isBlinking()) return PlayState.STOP;

        var controller = state.getController();
        if (mob.consumeBlinkJustStarted()) {
            controller.forceAnimationReset();
            controller.setAnimation(BLINK);
        }

        return PlayState.CONTINUE;
    }

    /**
     * Registers all GeckoLib controllers for this entity.
     *
     * Notes:
     * - "main" has a small transition time (5) to smooth between idle/walk/run.
     * - overlays run with 0 transition (instant start/stop).
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::movementController));
        controllers.add(new AnimationController<>(this, "angry", 0, this::angryOverlayController));
        controllers.add(new AnimationController<>(this, "blink", 0, this::blinkController));
    }

    /**
     * Called each tick.
     * CatoBaseMob.aiStep() handles server-side logic (anger, sleep, attacks, etc).
     *
     * Here we only do client-side cosmetic ticking (blink).
     */
    @Override
    public void aiStep() {
        super.aiStep();

        // Blink state is client-only cosmetic, so only tick it on the client.
        if (this.level().isClientSide) {
            this.tickBlinkClient();
        }

        // Sleep ticking is handled in CatoBaseMob.aiStep() (server-side)
    }

    /**
     * Hook from CatoBaseMob timed-attack system:
     * called when the server starts an attack animation.
     *
     * We set a synced flag so the client can switch to ATTACK animation immediately.
     */
    @Override
    protected void onAttackAnimationStart(LivingEntity target) {
        if (!this.level().isClientSide) this.setAttacking(true);
    }

    /**
     * Hook from CatoBaseMob timed-attack system:
     * called when the server decides the attack animation ended.
     *
     * Clears the synced attacking flag.
     */
    @Override
    protected void onAttackAnimationEnd() {
        if (!this.level().isClientSide) this.setAttacking(false);
    }

    /**
     * Breeding hook.
     * You currently allow the entity factory creation here.
     * (Breeding enable/food checks are controlled in CatoBaseMob/subclass config.)
     */
    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob parent) {
        return CMEntities.PIKACHU_MALE.get().create(level);
    }

    /**
     * GeoEntity requirement: returns the per-entity animation cache.
     */
    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
