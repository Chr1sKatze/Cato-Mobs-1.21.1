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
                    // render
                    .shadow(0.4f)
                    // fleeing
                    .flee(false, true, 4.0F, false, 20 * 30, 20 * 10, 1.35D, 20.0D)
                    .groupFlee(true,12.0D,10,false)
                    .groupFleeAllies(false, Set.of(CMEntities.PIKACHU_MALE.get())) // true(, null) = all catomobs are allies

                    // retaliation
                    .retaliation(true,20 * 15)
                    // Core Tuning
                    .core(8.0D, 1.0D, 0.30D, 16.0D, 0.08D)
                    .combat(2.0D, 4.0D, 70, 60, 30)
                    .chaseSpeed(1.60D)
                    .specialMelee(true,2.0D,4.0D,70,60,30,4.0D,false,0,0,0.50f,1)
                    .moveDuringAttackAnimation(true)
                    .attackMoveWindow(0,0)
                    .wander(1.0D, 1.35D, 0.35F, 3.0D, 32.0D)
                    .wanderAttempts(100,0.75f)
                    .surfacePreference(-0.5D, 1.5D,1.0D,0.0D)
                    .home(true, 96.0D)
                    .wanderRunDistanceThreshold(10.0D)

                    // Water (speed + "feel")
                    .waterSwimSpeedMultiplier(2.2D)
                    .waterMovement(true, 0.7D, 0.4D, 0.2D)
                    .funSwim(true,true, true,20*30,1.0f,20*10,12.0D,24)

                    // Sleep
                    .sleepWindow(true, true, false)
                    .sleepAttempts(20 * 5, 0.50f)
                    .sleepDuration(20 * 120, 20 * 240, 0.45f)
                    .sleepGrace(200, 400)
                    .sleepDesireWindow(400)
                    .sleepMemory(2, 2)

                    // Social sleeping
                    .sleepBuddies(true, 48.0D, 4, 2, 25, true,
                            Set.of(CMEntities.PIKACHU_MALE.get()))

                    // Constraints & wake rules
                    .sleepConstraints(true, false)
                    .wakeRules(true, true, true, true, true)

                    // Search behavior
                    .sleepSearch(400, 32, 1, 12, 20 * 10, 20 * 3, 2.0D, 0.0D, true, true)

                    // Rain shelter (seek roof while raining)
                    .rainShelter(true, 20 * 2, 1.0f, 28.0D, 46, 12, 1.35D, 1.00D, 20 * 5)

                    // Peek behavior (optional “step out into rain, then return”)
                    .rainShelterPeek(20 * 20, 20 * 3, 20 * 5, 2.0D, 6.0D, 16)

                    // Shuffle under roof (your “don’t stand still”)
                    .rainShelterShuffle(true, 20 * 30, 20 * 50, 16)

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
    private static final RawAnimation BLINK  = RawAnimation.begin().thenPlay("animation.pikachu.blink");
    private static final RawAnimation SURFACE_IDLE = RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_idle");
    private static final RawAnimation SURFACE_SWIM = RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_swim");
    private static final RawAnimation SLEEP  = RawAnimation.begin().thenLoop("animation.pikachu.sleep");

    // ================================================================
    // 4) GECKOLIB CONTROLLERS
    // ================================================================

    private <E extends GeoEntity> PlayState movementController(AnimationState<E> state) {
        PikachuMaleMob mob = (PikachuMaleMob) state.getAnimatable();

        if (mob.isSleeping()) {
            state.setAndContinue(SLEEP);
            return PlayState.CONTINUE;
        }

        if (mob.isAttacking()) {
            CatoAttackId id = mob.getCurrentAttackId();

            if (id == CatoAttackId.MELEE_SPECIAL) {
                state.setAndContinue(ATTACK_SPECIAL); // new RawAnimation
            } else {
                state.setAndContinue(ATTACK);
            }

            return PlayState.CONTINUE;
        }

        if (mob.isInWater()) {
            state.setAndContinue(state.isMoving() ? SURFACE_SWIM : SURFACE_IDLE);
            return PlayState.CONTINUE;
        }

        if (state.isMoving()) {
            state.setAndContinue(mob.getMoveMode() == MOVE_RUN ? RUN : WALK);
        } else {
            state.setAndContinue(IDLE);
        }

        return PlayState.CONTINUE;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::movementController));
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
