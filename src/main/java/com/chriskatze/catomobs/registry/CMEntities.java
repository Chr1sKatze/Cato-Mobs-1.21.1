package com.chriskatze.catomobs.registry;

import com.chriskatze.catomobs.CatoMobs;
import com.chriskatze.catomobs.entity.PikachuMaleMob;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CMEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CatoMobs.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<PikachuMaleMob>> PIKACHU_MALE =
            ENTITY_TYPES.register("pikachu_male", () ->
                    EntityType.Builder.of(PikachuMaleMob::new, MobCategory.CREATURE)
                            .sized(0.6f, 0.9f)
                            .build(CatoMobs.MODID + ":pikachu_male"));
}