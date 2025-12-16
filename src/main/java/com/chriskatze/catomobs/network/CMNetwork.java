package com.chriskatze.catomobs.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CMNetwork {
    private CMNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ToggleAiDebugPayload.TYPE,
                ToggleAiDebugPayload.STREAM_CODEC,
                ToggleAiDebugPayload::handle
        );
    }
}