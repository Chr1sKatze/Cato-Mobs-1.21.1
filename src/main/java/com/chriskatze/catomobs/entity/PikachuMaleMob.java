package com.chriskatze.catomobs.entity;

import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import com.chriskatze.catomobs.registry.CMEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * defines the behavior, animation, and interactions for this mob.
 */

public class PikachuMaleMob extends CatoBaseMob implements GeoEntity {

    // ================================================================
    // 1) SPECIES CONFIGURATION
    // ================================================================
    @Override
    public CatoMobSpeciesInfo getSpeciesInfo() {
        return SPECIES_INFO;
    }

    public static final CatoMobSpeciesInfo SPECIES_INFO =
            CatoMobSpeciesInfoBuilder.create()
                    .identity(CatoMobMovementType.HOVERING, CatoMobTemperament.NEUTRAL, CatoMobSizeCategory.SMALL)

                    // GENERAL SETTINGS
                    .core(16.0D, 0.3D,0.40D, 48.0D, 0.08D)
                    .shadow(0.4f)
                    .home(true, 76.0D)
                    .surfacePreference(-0.5D, 1.5D, 1.0D, 0.0D)

                    .hover(2.8, 0.06, 0.06, 12.0, true, 0.20, 80, 1.00)

                    // COMBAT BEHAVIOR
                    .retaliation(true, 20 * 30)
                    .flee(false, false, 4.0F, false, 20 * 30, 20 * 10, 1.35D, 20.0D)
                    .groupFlee(false, 12.0D, 10, false)
                    .groupFleeAllies(false, Set.of(CMEntities.PIKACHU_MALE.get())) // true(, null) = all catomobs are allies

                    // COMBAT STYLE
                    .onlyUseRanged(false)
                    .rangedUnlessClose(true, 10.0D, 6.0D)
                    .onlyUseMelee(false)

                    // FIGHT
                    .melee(1.0D, 2.0D, 4.00, 70, 60, 30, true, 0, 0)
                    .specialMelee(true, 2.0D, 4.0D, 70, 60, 30, 2.0D,
                            true, 0, 0, 1.0f, 1, false)
                    .ranged(true, 12.0D, 70, 60, 30, 1.00, 1.0f, 0.0f,
                            true,0,0, CatoMobSpeciesInfo.RangedDelivery.PROJECTILE)
                    .specialRanged(true, 14.0D, 70, 60, 30, 2.0D,3.0f,0.0f,
                            true,0,0, CatoMobSpeciesInfo.RangedDelivery.PROJECTILE, 1.0f, 2, false)

                    .chaseSpeed(1.0D)

                    // WANDERING AROUND BEHAVIOR
                    .wander(1.0D, 1.35D, 0.35F, 3.0D, 32.0D)
                    .wanderAttempts(100, 0.75f)
                    .wanderRunDistanceThreshold(10.0D)

                    // SWIMMING FOR FUN
                    .funSwim(true, true, true, 20 * 30, 1.0f, 20 * 10, 12.0D, 24)

                    // SLEEP BEHAVIOR
                    .sleepWindow(true, true, false)
                    .sleepAttempts(20 * 5, 0.50f)
                    .sleepDuration(20 * 120, 20 * 240, 0.45f)
                    .sleepGrace(200, 400)
                    .sleepDesireWindow(400)
                    .sleepMemory(2, 2)
                    .sleepConstraints(true, false)
                    .wakeRules(true, true, true, true, true)
                    .sleepBuddies(true, 32.0D, 4, 2, 25, true,
                            Set.of(CMEntities.PIKACHU_MALE.get()))

                    // SLEEP SPOT SEARCHING
                    .sleepSearch(3, 3, 1, 12, 20 * 15, 20 * 3, 24.0D, 0.0D, true, true)

                    // SEEK SHELTER FROM RAIN
                    .rainShelter(true, 20 * 2, 1.0f, 32.0D, 3, 16, 1.35D, 1.00D, 20 * 5)
                    .rainShelterPeek(20 * 20, 20 * 3, 20 * 5, 2.0D, 6.0D, 16)
                    .rainShelterShuffle(true, 20 * 30, 20 * 50, 16)

                    .build();

    // ================================================================
    // 2) HEAD ROTATION LIMITS (USED BY MODEL + LOOK CONTROL)
    // ================================================================
    private static final HeadRotationConfig HEAD_ROTATION_CONFIG =
            new HeadRotationConfig(20, 30, true, true);

    @Override
    public int getMaxHeadXRot() {
        return HEAD_ROTATION_CONFIG.getMaxHeadXRot(this.isSleeping(), this.isAttacking(), this.getMoveMode() == MOVE_RUN);
    }

    @Override
    public int getMaxHeadYRot() {
        return HEAD_ROTATION_CONFIG.getMaxHeadYRot(this.isSleeping(), this.isAttacking(), this.getMoveMode() == MOVE_RUN);
    }

    // ================================================================
    // 3) GECKOLIB ANIMATION DEFINITIONS
    // ================================================================
    private static final Map<String, RawAnimation> ANIMATIONS = new HashMap<>();

    static {
        ANIMATIONS.put("idle", RawAnimation.begin().thenLoop("animation.pikachu.ground_idle"));
        ANIMATIONS.put("walk", RawAnimation.begin().thenLoop("animation.pikachu.ground_walk"));
        ANIMATIONS.put("run", RawAnimation.begin().thenLoop("animation.pikachu.ground_run"));
        ANIMATIONS.put("melee_normal", RawAnimation.begin().thenPlay("animation.pikachu.melee"));
        ANIMATIONS.put("melee_special", RawAnimation.begin().thenPlay("animation.pikachu.melee_special"));
        ANIMATIONS.put("ranged_normal", RawAnimation.begin().thenPlay("animation.pikachu.ranged"));
        ANIMATIONS.put("ranged_special", RawAnimation.begin().thenPlay("animation.pikachu.ranged"));
        ANIMATIONS.put("angry", RawAnimation.begin().thenLoop("animation.pikachu.angry"));
        ANIMATIONS.put("battle_idle", RawAnimation.begin().thenLoop("animation.pikachu.battle_idle"));
        ANIMATIONS.put("blink", RawAnimation.begin().thenPlay("animation.pikachu.blink"));
        ANIMATIONS.put("surface_idle", RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_idle"));
        ANIMATIONS.put("surface_swim", RawAnimation.begin().thenLoop("animation.pikachu.surfacewater_swim"));
        ANIMATIONS.put("sleep", RawAnimation.begin().thenLoop("animation.pikachu.sleep"));
    }

    // ================================================================
    // 3.5) FX DEFINITIONS (sounds + particles)
    // ================================================================
    @Override
    protected Map<CatoMobFx.Key, CatoMobFx.Entry> getFxMap() {
        return FX;
    }

    private static ResourceLocation rl(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static final Map<CatoMobFx.Key, CatoMobFx.Entry> FX = new HashMap<>();

    static {
        // --- Attack START ---
        FX.put(CatoMobFx.Key.ATTACK_START_MELEE_NORMAL,
                CatoMobFx.Entry.both(
                        rl("catomobs","generic/placeholder_sound"), 0.9f, 1.2f,
                        rl("catomobs","generic/placeholder_particle"), 8,
                        0.2, 0.2, 0.2, 0.02
                ));

        FX.put(CatoMobFx.Key.ATTACK_START_MELEE_SPECIAL,
                CatoMobFx.Entry.both(
                        rl("catomobs","generic/placeholder_sound"), 0.6f, 1.8f,
                        rl("catomobs","generic/placeholder_particle"), 18,
                        0.35, 0.35, 0.35, 0.08
                ));

        FX.put(CatoMobFx.Key.ATTACK_START_RANGED_NORMAL,
                CatoMobFx.Entry.both(
                        rl("catomobs","generic/placeholder_sound"), 0.9f, 1.4f,
                        rl("catomobs","generic/placeholder_particle"), 8,
                        0.25, 0.25, 0.25, 0.03
                ));

        FX.put(CatoMobFx.Key.ATTACK_START_RANGED_SPECIAL,
                CatoMobFx.Entry.both(
                        rl("catomobs","generic/placeholder_sound"), 0.9f, 1.2f,
                        rl("catomobs","generic/placeholder_particle"), 22,
                        0.45, 0.45, 0.45, 0.10
                ));

        // --- Attack FIRE (exact hit/fire tick) ---
        FX.put(CatoMobFx.Key.ATTACK_FIRE_RANGED_NORMAL,
                CatoMobFx.Entry.particles(
                        rl("catomobs","generic/placeholder_particle"), 10,
                        0.15, 0.15, 0.15, 0.06
                ));

        FX.put(CatoMobFx.Key.ATTACK_FIRE_RANGED_SPECIAL,
                CatoMobFx.Entry.particles(
                        rl("catomobs","generic/placeholder_particle"), 14,
                        0.18, 0.18, 0.18, 0.08
                ));

        FX.put(CatoMobFx.Key.ATTACK_FIRE_MELEE_NORMAL,
                CatoMobFx.Entry.particles(
                        rl("catomobs","generic/placeholder_particle"), 8,
                        0.18, 0.18, 0.18, 0.03
                ));

        FX.put(CatoMobFx.Key.ATTACK_FIRE_MELEE_SPECIAL,
                CatoMobFx.Entry.particles(
                        rl("catomobs","generic/placeholder_particle"), 14,
                        0.25, 0.25, 0.25, 0.08
                ));

        // --- Death ---
        FX.put(CatoMobFx.Key.DEATH_START,
                CatoMobFx.Entry.both(
                        rl("catomobs","generic/placeholder_sound"), 0.7f, 1.6f,
                        rl("catomobs","generic/placeholder_particle"), 25,
                        0.5, 0.6, 0.5, 0.12
                ));

        FX.put(CatoMobFx.Key.DEATH_FINAL,
                CatoMobFx.Entry.particles(
                        rl("catomobs","generic/placeholder_particle"), 16,
                        0.35, 0.25, 0.35, 0.02
                ));
    }

    // ================================================================
    // 4) Animation Controller Setup (modified)
    // ================================================================
    /**
     * Registers the animation controllers using the helper class.
     */
    private final AnimationControllerHelper animationControllerHelper = new AnimationControllerHelper();

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Use the helper class to handle controller logic
        AnimationControllerHelper.registerControllers(controllers, this, ANIMATIONS, new AnimationControllerHelper.AnimationStateFunction() {

            @Override
            public <E extends GeoEntity> PlayState movementController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations) {
                return animationControllerHelper.movementController(state, mob, animations);
            }

            @Override
            public <E extends GeoEntity> PlayState overlayController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations, String animationKey) {
                return animationControllerHelper.overlayController(state, mob, animations, animationKey);
            }

            @Override
            public <E extends GeoEntity> PlayState blinkController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations) {
                return animationControllerHelper.blinkController(state, mob, animations);
            }
        });
    }

    // ================================================================
    // 5) LIFECYCLE & HOOKS
    // ================================================================
    /**
     * The cache for handling animation instances.
     */
    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);

    /**
     * Constructor for the PikachuMaleMob entity.
     */
    public PikachuMaleMob(EntityType<? extends CatoBaseMob> type, Level level) {
        super(type, level);
    }

    /**
     * Handles breeding and offspring creation for PikachuMaleMob.
     */
    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob parent) {
        return CMEntities.PIKACHU_MALE.get().create(level);
    }

    /**
     * Returns the animation instance cache for this mob.
     */
    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
