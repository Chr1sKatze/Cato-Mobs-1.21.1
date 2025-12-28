package com.chriskatze.catomobs.client.model;

import com.chriskatze.catomobs.CatoMobs;
import com.chriskatze.catomobs.entity.PikachuMaleMob;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * GeckoLib model class for {@link PikachuMaleMob}.
 *
 * What this class does:
 * - Tells GeckoLib which model/animation/texture files to use via a base name ("pikachu_male").
 * - Disables GeckoLib's built-in head tracking (we do our own).
 * - In setCustomAnimations(), drives a dedicated "head_ai" bone with yaw/pitch coming from
 *   Minecraft's look data (netHeadYaw/headPitch), but clamped to limits provided by the entity.
 *
 * Key idea:
 * - The Bedrock-style rig has a separate bone that is meant to be rotated by "AI look" code.
 * - That means your animations can still animate the "real head" however you want,
 *   and this AI bone adds an extra (additive) rotation on top.
 *
 * Files expected by DefaultedEntityGeoModel:
 * - geo/pikachu_male.geo.json
 * - animations/pikachu_male.animation.json
 * - textures/entity/pikachu_male.png
 * (Exact paths depend on GeckoLib conventions; the important part is the base name.)
 */
public class PikachuMaleModel extends DefaultedEntityGeoModel<PikachuMaleMob> {

    public PikachuMaleModel() {
        /**
         * ResourceLocation base name:
         * - namespace = your mod id
         * - path      = "pikachu_male"
         *
         * The second parameter is important:
         * - false = disable GeckoLib auto head tracking
         *   (because we implement our own head rotation logic below)
         */
        super(ResourceLocation.fromNamespaceAndPath(CatoMobs.MODID, "pikachu_male"), false);
    }

    /**
     * Called every frame (client-side) so we can apply custom bone transforms.
     *
     * @param animatable The entity instance being rendered.
     * @param instanceId GeckoLib instance id (used internally for animation caching).
     * @param state      Animation state for this render tick, also stores model data tickets.
     *
     * Our custom logic:
     * 1) Fetch the "head_ai" bone from the model.
     * 2) If sleeping -> zero out the AI head rotation completely (prevents "looking around" in sleep).
     * 3) Ask the entity for max yaw/pitch (your overrides can return 0 to disable head turning).
     * 4) Read Minecraft's head yaw/pitch from ENTITY_MODEL_DATA.
     * 5) Clamp yaw/pitch to the allowed limits.
     * 6) Convert degrees -> radians (GeoBone rotations expect radians) and apply to the bone.
     */
    @Override
    public void setCustomAnimations(PikachuMaleMob animatable,
                                    long instanceId,
                                    AnimationState<PikachuMaleMob> state) {
        super.setCustomAnimations(animatable, instanceId, state);

        GeoBone headAi = this.getAnimationProcessor().getBone("head_ai");
        if (headAi == null) return;

        // ✅ HARD GATE: never apply procedural head look during sleep OR attacks
        // (prevents ranged anim “spin” issues caused by additive bone mixing / stale rotations)
        if (animatable.isSleeping() || animatable.isAttacking()) {
            headAi.setRotX(0);
            headAi.setRotY(0);
            headAi.setRotZ(0);
            return;
        }

        int maxYaw = animatable.getMaxHeadYRot();
        int maxPitch = animatable.getMaxHeadXRot();

        if (maxYaw == 0 && maxPitch == 0) {
            headAi.setRotX(0);
            headAi.setRotY(0);
            headAi.setRotZ(0);
            return;
        }

        var data = state.getData(DataTickets.ENTITY_MODEL_DATA);

        float yawDeg = clamp(data.netHeadYaw(), -maxYaw, maxYaw);
        float pitchDeg = clamp(data.headPitch(), -maxPitch, maxPitch);

        headAi.setRotY(yawDeg * ((float) Math.PI / 180F));
        headAi.setRotX(pitchDeg * ((float) Math.PI / 180F));

        // ✅ Always force roll to 0 to avoid any animation/blend garbage
        headAi.setRotZ(0);
    }

    /**
     * Simple clamp helper for floats.
     * Ensures v stays within [min, max].
     */
    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
