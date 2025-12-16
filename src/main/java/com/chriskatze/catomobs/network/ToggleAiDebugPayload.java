package com.chriskatze.catomobs.network;

import com.chriskatze.catomobs.CatoMobs;
import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ToggleAiDebugPayload(int entityId) implements CustomPacketPayload {

    public static final Type<ToggleAiDebugPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CatoMobs.MODID, "toggle_ai_debug"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAiDebugPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeInt(p.entityId()),
                    buf -> new ToggleAiDebugPayload(buf.readInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToggleAiDebugPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = ctx.player();
            if (player == null) return;

            var level = player.level();
            var e = level.getEntity(payload.entityId());
            if (!(e instanceof CatoBaseMob mob)) return;

            mob.setAiDebugEnabled(!mob.isAiDebugEnabled());
        });
    }
}
