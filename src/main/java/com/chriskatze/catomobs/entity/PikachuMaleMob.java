package com.chriskatze.catomobs.entity;

import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import com.chriskatze.catomobs.registry.CMEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.*;

import java.util.Set;

/**
 * PikachuMaleMob
 *
 * Concrete mob implementation using CatoBaseMob + GeckoLib.
 *
 * This class is intentionally:
 * - declarative (species config)
 * - cosmetic / animation-focused
 * - light on AI logic (handled by base + goals)
 */
public class PikachuMaleMob extends CatoBaseMob implements GeoEntity {

    // ================================================================
    // 1) SPECIES CONFIGURATION
    // ================================================================

    public static final CatoMobSpeciesInfo SPECIES_INFO =
            CatoMobSpeciesInfoBuilder.create()
                    .identity(CatoMobMovementType.LAND, CatoMobTemperament.NEUTRAL, CatoMobSizeCategory.SMALL)

                    // GEMERAL SETTINGS
                    .core(8.0D, 0.3D, 16.0D, 0.08D)
                    .shadow(0.4f)
                    .home(true, 96.0D)
                    .surfacePreference(-0.5D, 1.5D,1.0D,0.0D)

                    // COMBAT BEHAVIOR
                    .retaliation(true,20 * 15)
                    .flee(false, true, 4.0F, false, 20 * 30, 20 * 10, 1.35D, 20.0D)
                    .groupFlee(true,12.0D,10,false)
                    .groupFleeAllies(false, Set.of(CMEntities.PIKACHU_MALE.get())) // true(, null) = all catomobs are allies

                    // FIGHT
                    .combat(2.0D, 2.0D, 4.00, 120, 60,30,true,0,0)
                    .specialMelee(true,2.0D,4.0D,120,60,30,4.0D,true,0,0,0.50f,1,false)
                    .chaseSpeed(1.60D)

                    // WANDERING AROUND BEHAVIOR
                    .wander(1.0D, 1.35D, 0.35F, 3.0D, 32.0D)
                    .wanderAttempts(100,0.75f)
                    .wanderRunDistanceThreshold(10.0D)

                    // SWIMMING FOR FUN
                    .funSwim(true,true, true,20*30,1.0f,20*10,12.0D,24)

                    // WATER BEHAVIOR
                    .waterSwimSpeedMultiplier(2.2D)
                    .waterMovement(true, 0.7D, 0.4D, 0.2D)

                    // SLEEP BEHAVIOR
                    .sleepWindow(true, true, false)
                    .sleepAttempts(20 * 5, 0.50f)
                    .sleepDuration(20 * 120, 20 * 240, 0.45f)
                    .sleepGrace(200, 400)
                    .sleepDesireWindow(400)
                    .sleepMemory(2, 2)
                    .sleepConstraints(true, false)
                    .wakeRules(true, true, true, true, true)
                    .sleepBuddies(true, 48.0D, 4, 2, 25, true,
                            Set.of(CMEntities.PIKACHU_MALE.get()))

                    // SLEEP SPOT SEARCHING
                    .sleepSearch(400, 32, 1, 12, 20 * 10, 20 * 3, 2.0D, 0.0D, true, true)

                    // SEEK SHELTER FROM RAIN
                    .rainShelter(true, 20 * 2, 1.0f, 28.0D, 46, 12, 1.35D, 1.00D, 20 * 5)
                    .rainShelterPeek(20 * 20, 20 * 3, 20 * 5, 2.0D, 6.0D, 16) // have a quick peek into the rain
                    .rainShelterShuffle(true, 20 * 30, 20 * 50, 16) // move around under roof

                    .build();

    @Override
    public CatoMobSpeciesInfo getSpeciesInfo() {
        return SPECIES_INFO;
    }

    // ================================================================
    // 2) HEAD ROTATION LIMITS (USED BY MODEL + LOOK CONTROL)
    // ================================================================

    public static final boolean HEAD_TURN_WHILE_RUNNING = true;
    public static final boolean HEAD_TURN_WHILE_ATTACKING = true;

    public static final int BASE_MAX_HEAD_PITCH = 20;
    public static final int BASE_MAX_HEAD_YAW = 30;

    @Override
    public int getMaxHeadXRot() {
        if (this.isSleeping()) return 0;
        if (isAttacking() && !HEAD_TURN_WHILE_ATTACKING) return 0;
        if (this.getMoveMode() == MOVE_RUN && !HEAD_TURN_WHILE_RUNNING) return 0;
        return BASE_MAX_HEAD_PITCH;
    }

    @Override
    public int getMaxHeadYRot() {
        if (this.isSleeping()) return 0;
        if (isAttacking() && !HEAD_TURN_WHILE_ATTACKING) return 0;
        if (this.getMoveMode() == MOVE_RUN && !HEAD_TURN_WHILE_RUNNING) return 0;
        return BASE_MAX_HEAD_YAW;
    }

    // ================================================================
    // 3) GECKOLIB ANIMATION DEFINITIONS
    // ================================================================

    private static final RawAnimation IDLE   = RawAnimation.begin().thenLoop("animation.pikachu.ground_idle");
    private static final RawAnimation WALK   = RawAnimation.begin().thenLoop("animation.pikachu.ground_walk");
    private static final RawAnimation RUN    = RawAnimation.begin().thenLoop("animation.pikachu.ground_run");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.pikachu.physical");
    private static final RawAnimation ATTACK_SPECIAL = RawAnimation.begin().thenPlay("animation.pikachu.volttackle");
    private static final RawAnimation ANGRY  = RawAnimation.begin().thenLoop("animation.pikachu.angry");
    private static final RawAnimation BATTLE_IDLE = RawAnimation.begin().thenLoop("animation.pikachu.battle_idle");
    private static final RawAnimation BLINK  = RawAnimation.begin().thenPlay("animation.pikachu.blink");
    private static final RawAnimation SURFACE_IDLE = RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_idle");
    private static final RawAnimation SURFACE_SWIM = RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_swim");
    private static final RawAnimation SLEEP  = RawAnimation.begin().thenLoop("animation.pikachu.sleep");

    // ================================================================
    // 4) GECKOLIB CONTROLLERS
    // ================================================================

    // Client-side visual smoothing: keep RUN playing briefly to avoid edge flicker
    private int runAnimHoldTicks = 0;
    private static final int RUN_ANIM_HOLD_TICKS = 8; // tweak 6..12

    private <E extends GeoEntity> PlayState movementController(AnimationState<E> state) {
        PikachuMaleMob mob = (PikachuMaleMob) state.getAnimatable();

        // ------------------------------------------------------------
        // Sleeping overrides everything
        // ------------------------------------------------------------
        if (mob.isSleeping()) {
            runAnimHoldTicks = 0;
            state.setAndContinue(SLEEP);
            return PlayState.CONTINUE;
        }

        // ------------------------------------------------------------
        // Attacking (normal vs special)
        // ------------------------------------------------------------
        if (mob.isAttacking()) {
            runAnimHoldTicks = 0;
            CatoAttackId id = mob.getCurrentAttackId();

            if (id == CatoAttackId.MELEE_SPECIAL) {
                state.setAndContinue(ATTACK_SPECIAL);
            } else {
                state.setAndContinue(ATTACK);
            }

            return PlayState.CONTINUE;
        }

        // ------------------------------------------------------------
        // Water movement
        // ------------------------------------------------------------
        if (mob.isInWater()) {
            runAnimHoldTicks = 0;
            state.setAndContinue(state.isMoving() ? SURFACE_SWIM : SURFACE_IDLE);
            return PlayState.CONTINUE;
        }

        // ------------------------------------------------------------
        // Ground movement / idle
        // ------------------------------------------------------------
        if (state.isMoving()) {

            // If we are truly in RUN mode, refresh the hold timer.
            if (mob.getMoveMode() == MOVE_RUN) {
                runAnimHoldTicks = RUN_ANIM_HOLD_TICKS;
                state.setAndContinue(RUN);
            } else {
                // Not in RUN mode (probably WALK), but if we *recently* were running,
                // keep RUN for a few ticks to avoid "almost-run" blending jitter.
                if (runAnimHoldTicks > 0) {
                    runAnimHoldTicks--;
                    state.setAndContinue(RUN);
                } else {
                    state.setAndContinue(WALK);
                }
            }

        } else {
            // Not moving → clear hold so next run starts clean
            runAnimHoldTicks = 0;

            state.setAndContinue(
                    (mob.isVisuallyAngry() && mob.hasCombatTarget())
                            ? BATTLE_IDLE
                            : IDLE
            );
        }

        return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, this::movementController));
        controllers.add(new AnimationController<>(this, "angry", 0, s -> this.overlayController(s, this.isVisuallyAngry(), ANGRY)));
        controllers.add(new AnimationController<>(this, "blink", 0, s -> this.blinkController(s, BLINK)));
    }

    // ================================================================
    // 5) LIFECYCLE & HOOKS
    // ================================================================

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    public PikachuMaleMob(EntityType<? extends CatoBaseMob> type, Level level) {
        super(type, level);
    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob parent) {
        return CMEntities.PIKACHU_MALE.get().create(level);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
