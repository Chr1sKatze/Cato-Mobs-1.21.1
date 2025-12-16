package com.chriskatze.catomobs.client;

import com.chriskatze.catomobs.CatoMobs;
import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import com.chriskatze.catomobs.network.ToggleAiDebugPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(
        modid = CatoMobs.MODID,
        value = Dist.CLIENT
)
public final class ClientAiDebugToggle {

    private static final KeyMapping TOGGLE =
            new KeyMapping("key.catomobs.toggle_ai_debug", GLFW.GLFW_KEY_B, "key.categories.catomobs");

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        while (TOGGLE.consumeClick()) {
            Entity target = getLookedAtEntity(mc);
            if (target instanceof CatoBaseMob mob) {
                PacketDistributor.sendToServer(new ToggleAiDebugPayload(mob.getId()));
            }
        }
    }

    private static Entity getLookedAtEntity(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult ehr) {
            return ehr.getEntity();
        }
        return null;
    }
}
