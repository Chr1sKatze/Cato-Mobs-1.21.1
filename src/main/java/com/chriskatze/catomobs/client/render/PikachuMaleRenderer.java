package com.chriskatze.catomobs.client.render;

import com.chriskatze.catomobs.client.model.PikachuMaleModel;
import com.chriskatze.catomobs.entity.PikachuMaleMob;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Client-side renderer for {@link PikachuMaleMob} using GeckoLib's {@link GeoEntityRenderer}.
 *
 * What this class does:
 * - Connects the entity (PikachuMaleMob) to its GeoModel (PikachuMaleModel).
 * - Applies a scale transform so the rendered model is smaller than "1.0".
 * - Uses a different scale for babies vs adults.
 * - Keeps the shadow radius consistent with the visual scale.
 *
 * NOTE:
 * - This runs ONLY on the client.
 * - Scaling here affects the rendered model and shadow only.
 *   It does NOT change hitbox size, movement, attack range, etc. (those are server-side / entity-side).
 */
public class PikachuMaleRenderer extends GeoEntityRenderer<PikachuMaleMob> {

    // ------------------------------------------------------------
    // Rendering scale configuration
    // ------------------------------------------------------------

    /** Visual scale factor for adult Pikachu. (1.0 = normal model size) */
    public static final float MODEL_SCALE = 0.60f;

    /** Visual scale factor for baby Pikachu. */
    public static final float BABY_SCALE = 0.40f;

    /**
     * Renderer constructor.
     *
     * @param ctx The renderer context provided by Minecraft when registering renderers.
     *
     * We pass our GeckoLib model implementation to the base class.
     * shadowRadius is also initialized here, but we will update it dynamically in render()
     * so it matches adult/baby scaling.
     */
    public PikachuMaleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PikachuMaleModel());

        // Initial shadow size (will be overwritten each render call depending on baby/adult)
        this.shadowRadius = 0.3f * MODEL_SCALE;
    }

    /**
     * Called each frame to render the entity.
     *
     * Parameters:
     * - entityYaw / partialTicks: used for smooth interpolation (handled by base class).
     * - poseStack: transformation stack; we can scale/rotate/translate before rendering.
     * - bufferSource / packedLight: rendering buffers and lighting information.
     *
     * Our custom behavior here:
     * 1) Choose a scale based on entity age (baby vs adult).
     * 2) Apply that scale to the pose stack so the whole model shrinks/grows.
     * 3) Adjust the shadowRadius so the shadow matches the model size.
     * 4) Delegate actual model rendering to GeoEntityRenderer via super.render().
     */
    @Override
    public void render(PikachuMaleMob entity,
                       float entityYaw,
                       float partialTicks,
                       PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight) {

        // Choose the correct visual scale based on age.
        float scale = entity.isBaby() ? BABY_SCALE : MODEL_SCALE;

        // Apply scale to the model. (All subsequent rendering uses this transform.)
        poseStack.scale(scale, scale, scale);

        // Keep the shadow visually consistent with the scaled model.
        this.shadowRadius = 0.3f * scale;

        // Render using GeckoLib's renderer (handles bones, animations, textures, etc.)
        super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
    }
}
