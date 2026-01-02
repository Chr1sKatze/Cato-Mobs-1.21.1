package com.chriskatze.catomobs.registry;

import com.chriskatze.catomobs.CatoMobs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CMSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, CatoMobs.MODID);

    // ------------------------------------------------------------
    // register
    // ------------------------------------------------------------
    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUND_EVENTS.register(path,
                () -> SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath(CatoMobs.MODID, path)
                ));
    }

    // ------------------------------------------------------------
    // Sounds (keep them organized by prefix)
    // ------------------------------------------------------------

    // Shared / generic
    public static final DeferredHolder<SoundEvent, SoundEvent> PLACEHOLDER_SOUND =
            register("generic/placeholder_sound");

    private CMSounds() {}
}