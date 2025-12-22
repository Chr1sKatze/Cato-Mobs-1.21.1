package com.chriskatze.catomobs.client.render;

import com.chriskatze.catomobs.client.model.PikachuMaleModel;
import com.chriskatze.catomobs.entity.PikachuMaleMob;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
     * Base shadow radius for the ADULT (before baby scaling).
     * This comes from species info so it’s data-driven per mob.
     */
    private final float baseShadowRadius;

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

        // Take the baseline from the species info (intended for adult visuals).
        this.baseShadowRadius = PikachuMaleMob.SPECIES_INFO.shadowRadius();

        // Initial shadow size (will be overwritten each render call depending on baby/adult)
        this.shadowRadius = this.baseShadowRadius;
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
        // baseShadowRadius is for adult (MODEL_SCALE), so scale it proportionally.
        float shadowScale = (MODEL_SCALE <= 0f) ? 1f : (scale / MODEL_SCALE);
        this.shadowRadius = this.baseShadowRadius * shadowScale;

        // Render using GeckoLib's renderer (handles bones, animations, textures, etc.)
        super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);

        // Draw AI debug overlay (client-only)
        renderAiDebug(entity, poseStack, bufferSource, packedLight);
    }

    private void renderAiDebug(PikachuMaleMob entity,
                               PoseStack poseStack,
                               MultiBufferSource bufferSource,
                               int packedLight) {

        if (!entity.isAiDebugEnabled()) return;

        String text = entity.getAiDebugText();
        if (text == null || text.isBlank()) return;

        Font font = Minecraft.getInstance().font;

        // Lift above head
        float y = entity.getBbHeight() + 0.55f;

        poseStack.pushPose();
        poseStack.translate(0.0D, y, 0.0D);

        // Face camera
        var cam = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        poseStack.mulPose(cam);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        // flip upright without negative scale (no culling)
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));

        // Scale down to name-tag style
        float scale = 0.025F;
        poseStack.scale(scale, scale, scale);

        String[] lines = text.split("\n");

        // Simple layout: stack upwards
        int lineHeight = font.lineHeight;
        int totalHeight = lines.length * lineHeight;
        int yStart = -totalHeight;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Color convention from snapshot builder:
            // [G] = active (green), [R] = inactive (red)
            ChatFormatting color = ChatFormatting.WHITE;
            if (line.startsWith("[G]")) color = ChatFormatting.GREEN;
            else if (line.startsWith("[R]")) color = ChatFormatting.RED;

            MutableComponent comp = Component.literal(line).withStyle(color);

            float x = -font.width(comp) / 2.0f;
            float yy = yStart + i * lineHeight;

            // draw
            font.drawInBatch(
                    comp,
                    x,
                    yy,
                    0xFFFFFFFF,
                    false,
                    poseStack.last().pose(),
                    bufferSource,
                    Font.DisplayMode.NORMAL,
                    0,
                    LightTexture.FULL_BRIGHT
            );
        }

        poseStack.popPose();
    }
}
