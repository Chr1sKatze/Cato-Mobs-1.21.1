package com.chriskatze.catomobs.registry;

import com.chriskatze.catomobs.CatoMobs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CMItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CatoMobs.MODID);

    // Pikachu (male) spawn egg
    public static final DeferredHolder<Item, SpawnEggItem> PIKACHU_MALE_SPAWN_EGG =
            ITEMS.register("pikachu_male_spawn_egg", () ->
                    new SpawnEggItem(
                            CMEntities.PIKACHU_MALE.get(),
                            0xFFF176, // base color
                            0xFBC02D, // spots color
                            new Item.Properties()
                    )
            );
}
