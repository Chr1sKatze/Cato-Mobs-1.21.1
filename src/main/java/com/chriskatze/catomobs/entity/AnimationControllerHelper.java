package com.chriskatze.catomobs.entity;

import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.*;
import java.util.Map;

/**
 * Helper class that manages animation controllers for mobs.
 * It handles different animation states like movement, attack, overlay, and blink.
 */
public class AnimationControllerHelper {

    // ================================================================
    // Client-side visual smoothing: keep RUN playing briefly to avoid edge flicker
    // ================================================================
    private static final int RUN_ANIM_HOLD_TICKS = 8; // tweak between 6 and 12 ticks
    private int runAnimHoldTicks = 0;  // Counter for holding the run animation briefly
    private boolean wasAttackingClient = false;  // Tracks whether the mob was previously attacking (Non-static)

    // ================================================================
    // Registering Animation Controllers
    // ================================================================
    /**
     * Registers animation controllers for the mob.
     * It handles movement, overlay, and blink controllers using the provided animations.
     *
     * @param controllers The controller registrar to which controllers will be added.
     * @param mob The mob entity to which the controllers are being applied.
     * @param animations A map of animation names to their corresponding animation definitions.
     * @param stateFunction The state function providing the specific controller logic for the mob.
     */
    public static <E extends GeoEntity> void registerControllers(
            AnimatableManager.ControllerRegistrar controllers, E mob, Map<String, RawAnimation> animations,
            AnimationStateFunction stateFunction
    ) {
        // Register the main controller that handles movement
        controllers.add(new AnimationController<>(mob, "main", 3, state -> stateFunction.movementController(state, mob, animations)));

        // Register the angry overlay controller (if the mob is angry)
        controllers.add(new AnimationController<>(mob, "angry", 0, state -> stateFunction.overlayController(state, mob, animations, "angry")));

        // Register the blink controller (if the mob is blinking)
        controllers.add(new AnimationController<>(mob, "blink", 0, state -> stateFunction.blinkController(state, mob, animations)));
    }

    // ================================================================
    // Movement Controller (Handles movement animations)
    // ================================================================
    /**
     * Handles the movement animations based on the mob's state.
     * The movement type can vary based on whether the mob is running, walking, attacking, or swimming.
     *
     * @param state The animation state.
     * @param mob The mob entity.
     * @param animations A map of animations to use.
     * @param <E> The type of GeoEntity (mob).
     * @return The current play state of the animation.
     */
    public <E extends GeoEntity> PlayState movementController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations) {
        if (mob instanceof CatoBaseMob baseMob) {
            // Clear the attacking flag once the mob is no longer attacking
            if (!baseMob.isAttacking()) {
                wasAttackingClient = false;
            }

            // ================================================================
            // Handle sleeping state: overrides any other movement
            // ================================================================
            if (baseMob.isSleeping()) {
                state.setAndContinue(animations.get("sleep"));
                return PlayState.CONTINUE;
            }

            // ================================================================
            // Handle attacking state: select animation based on attack type
            // ================================================================
            if (baseMob.isAttacking()) {
                CatoAttackId attackId = baseMob.getCurrentAttackId();
                if (attackId == CatoAttackId.MELEE_SPECIAL) {
                    state.setAndContinue(animations.get("melee_special"));
                } else if (attackId == CatoAttackId.RANGED_NORMAL) {
                    state.setAndContinue(animations.get("ranged_normal"));
                } else if (attackId == CatoAttackId.RANGED_SPECIAL) {
                    state.setAndContinue(animations.get("ranged_special"));
                } else {
                    state.setAndContinue(animations.get("melee_normal"));
                }
                return PlayState.CONTINUE;
            }

            // ================================================================
            // Handle water movement (swim animations)
            // ================================================================
            if (baseMob.isInWater()) {
                state.setAndContinue(state.isMoving() ? animations.get("surface_swim") : animations.get("surface_idle"));
                return PlayState.CONTINUE;
            }

            // ================================================================
            // Handle ground movement or idle state
            // ================================================================
            if (state.isMoving()) {
                if (baseMob.getMoveMode() == baseMob.MOVE_RUN) {  // Run mode animation
                    state.setAndContinue(animations.get("run"));
                } else {
                    state.setAndContinue(animations.get("walk"));
                }
            } else {
                // Idle animation: If the mob is angry and has a combat target, use "battle_idle"
                state.setAndContinue(baseMob.isVisuallyAngry() && baseMob.hasCombatTarget() ? animations.get("battle_idle") : animations.get("idle"));
            }

            return PlayState.CONTINUE;
        }
        return PlayState.CONTINUE;
    }

    // ================================================================
    // Overlay Controller (Handles visual overlays like anger)
    // ================================================================
    /**
     * Handles the overlay animation (e.g., when the mob is angry).
     *
     * @param state The animation state.
     * @param mob The mob entity.
     * @param animations A map of animations to use.
     * @param animationKey The key for the specific overlay animation.
     * @param <E> The type of GeoEntity (mob).
     * @return The current play state of the animation.
     */
    public <E extends GeoEntity> PlayState overlayController(
            AnimationState<E> state, E mob, Map<String, RawAnimation> animations, String animationKey
    ) {
        if (mob instanceof CatoBaseMob baseMob && baseMob.isVisuallyAngry()) {
            state.setAndContinue(animations.get(animationKey));
        }
        return PlayState.CONTINUE;
    }

    // ================================================================
    // Blink Controller (Handles blinking animations)
    // ================================================================
    /**
     * Handles the blink animation for the mob.
     *
     * @param state The animation state.
     * @param mob The mob entity.
     * @param animations A map of animations to use.
     * @param <E> The type of GeoEntity (mob).
     * @return The current play state of the animation.
     */
    public <E extends GeoEntity> PlayState blinkController(
            AnimationState<E> state, E mob, Map<String, RawAnimation> animations
    ) {
        if (mob instanceof CatoBaseMob baseMob && baseMob.blink().isBlinking()) {
            state.setAndContinue(animations.get("blink"));
        }
        return PlayState.CONTINUE;
    }

    // ================================================================
    // Interface for Mob-Specific Animation Logic
    // ================================================================
    /**
     * Interface that defines the methods for mob-specific animation logic.
     * Each mob can implement its own behavior for movement, overlay, and blink animations.
     */
    public interface AnimationStateFunction {
        /**
         * Handles the movement animation logic.
         *
         * @param <E> The type of GeoEntity (mob).
         * @param state The animation state.
         * @param mob The mob entity.
         * @param animations A map of animations to use.
         * @return The current play state of the animation.
         */
        <E extends GeoEntity> PlayState movementController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations);

        /**
         * Handles the overlay animation logic.
         *
         * @param <E> The type of GeoEntity (mob).
         * @param state The animation state.
         * @param mob The mob entity.
         * @param animations A map of animations to use.
         * @param animationKey The key for the specific overlay animation.
         * @return The current play state of the animation.
         */
        <E extends GeoEntity> PlayState overlayController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations, String animationKey);

        /**
         * Handles the blink animation logic.
         *
         * @param <E> The type of GeoEntity (mob).
         * @param state The animation state.
         * @param mob The mob entity.
         * @param animations A map of animations to use.
         * @return The current play state of the animation.
         */
        <E extends GeoEntity> PlayState blinkController(AnimationState<E> state, E mob, Map<String, RawAnimation> animations);
    }
}
