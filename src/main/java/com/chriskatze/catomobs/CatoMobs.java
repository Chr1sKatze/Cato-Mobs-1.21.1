package com.chriskatze.catomobs;

import com.chriskatze.catomobs.client.render.PikachuMaleRenderer;
import com.chriskatze.catomobs.entity.PikachuMaleMob;
import com.chriskatze.catomobs.entity.base.CatoBaseMob;
import com.chriskatze.catomobs.registry.CMEntities;
import com.chriskatze.catomobs.registry.CMItems;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;

/**
 * CatoMobs (main mod class)
 *
 * This is the entry point for the NeoForge mod (annotated with @Mod).
 *
 * Responsibilities:
 * - define the MODID constant ("catomobs")
 * - hook mod lifecycle events (common + client)
 * - register deferred registers (entities, items)
 * - register attributes for entity types
 * - add items (spawn eggs etc.) to creative tabs
 * - register client-only stuff (renderers)
 *
 * What this class does NOT do:
 * - define entity/item classes (see entity/ and registry/)
 * - implement AI, attributes, animations (handled elsewhere)
 */
@Mod(CatoMobs.MODID)
public class CatoMobs {

    /** Must match the namespace used in resources: assets/catomobs/... and data/catomobs/... */
    public static final String MODID = "catomobs";

    /** Shared logger for the whole mod. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Mod constructor.
     *
     * NeoForge calls this once when the mod is loaded.
     * The IEventBus here is the *mod event bus* (lifecycle events, registrations, etc.).
     */
    public CatoMobs(IEventBus modEventBus) {

        // ------------------------------------------------------------
        // Mod lifecycle listeners (common)
        // ------------------------------------------------------------
        modEventBus.addListener(this::commonSetup);

        // ------------------------------------------------------------
        // Deferred registers
        // ------------------------------------------------------------
        // These register your EntityTypes and Items to the game registry at the correct time.
        CMEntities.ENTITY_TYPES.register(modEventBus);
        CMItems.ITEMS.register(modEventBus);

        // ------------------------------------------------------------
        // Additional mod event listeners
        // ------------------------------------------------------------
        // Register attributes for entities (health, speed, damage, follow range, etc.)
        modEventBus.addListener(this::registerAttributes);

        // Add items into creative tabs (spawn eggs, etc.)
        modEventBus.addListener(this::addCreativeTabContents);
    }

    /**
     * Common setup event (runs on both dedicated server and client).
     * Use this for things that are not client-only and not registry-related.
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[CatoMobs] Common setup complete.");
    }

    /**
     * EntityAttributeCreationEvent:
     * This is where you attach an AttributeSupplier to each EntityType.
     *
     * Without this, mobs can spawn with missing/default attributes and behave incorrectly.
     *
     * Here you use your shared factory:
     * - CatoBaseMob.createAttributesFor(speciesInfo)
     * so each mob’s species config determines health/speed/damage/etc.
     */
    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(
                // Entity type key
                CMEntities.PIKACHU_MALE.get(),
                // Build the attribute set from Pikachu's species info
                CatoBaseMob.createAttributesFor(PikachuMaleMob.SPECIES_INFO).build()
        );
        LOGGER.info("[CatoMobs] Registered attributes for Pikachu Male (species-based).");
    }

    /**
     * BuildCreativeModeTabContentsEvent:
     * Called when creative mode tab contents are being built.
     *
     * Here you add your spawn egg to the vanilla Spawn Eggs tab.
     */
    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // Only add to the Spawn Eggs tab (not all tabs)
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(CMItems.PIKACHU_MALE_SPAWN_EGG.get());
        }
    }

    /**
     * Client-only event subscriber class.
     *
     * @EventBusSubscriber registers this class to the *MOD event bus* on the CLIENT side only.
     * This prevents server crashes from client-only classes like renderers/Minecraft.
     */
    @EventBusSubscriber(modid = CatoMobs.MODID, value = Dist.CLIENT)
    public static class ClientModEvents {

        /**
         * Client setup event:
         * Runs only on the client.
         *
         * Typical use cases:
         * - keybinds
         * - screens/menus
         * - client-only setup logging
         */
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Safe here because this class is CLIENT-only.
            LOGGER.info("[CatoMobs] Client setup for user: {}", Minecraft.getInstance().getUser().getName());
        }

        /**
         * RegisterRenderers event:
         * This is where you bind EntityTypes to their client renderers.
         *
         * Without this, the entity may render as missing/no model.
         */
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(
                    // Entity type
                    CMEntities.PIKACHU_MALE.get(),
                    // Renderer factory (constructs a new renderer instance)
                    PikachuMaleRenderer::new
            );
        }
    }
}
