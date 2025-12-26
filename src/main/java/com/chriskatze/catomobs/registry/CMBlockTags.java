package com.chriskatze.catomobs.registry;

import com.chriskatze.catomobs.CatoMobs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class CMBlockTags {
    private CMBlockTags() {}

    public static final TagKey<Block> SOFT_GROUND =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CatoMobs.MODID, "soft_ground"));

    public static final TagKey<Block> HARD_GROUND =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CatoMobs.MODID, "hard_ground"));
}
