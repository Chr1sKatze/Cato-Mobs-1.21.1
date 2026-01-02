package com.chriskatze.catomobs.registry;

import com.chriskatze.catomobs.CatoMobs;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CMParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, CatoMobs.MODID);

    // Shared / generic
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> PLACEHOLDER_PARTICLE =
            PARTICLES.register("generic/placeholder_particle", () -> new SimpleParticleType(false));
}
