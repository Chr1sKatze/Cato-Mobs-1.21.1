package com.chriskatze.catomobs.entity;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Small, data-like FX definition system.
 *
 * Each mob can provide a Map<Key, Entry> similar to your ANIMATIONS map.
 * Entry may define a sound, particles, or both.
 */
public final class CatoMobFx {

    private CatoMobFx() {}

    /** Where in the lifecycle we want to fire FX. */
    public enum Key {
        // Attack start
        ATTACK_START_MELEE_NORMAL,
        ATTACK_START_MELEE_SPECIAL,
        ATTACK_START_RANGED_NORMAL,
        ATTACK_START_RANGED_SPECIAL,

        // Attack "fire/hit moment" (optional)
        ATTACK_FIRE_MELEE_NORMAL,
        ATTACK_FIRE_MELEE_SPECIAL,
        ATTACK_FIRE_RANGED_NORMAL,
        ATTACK_FIRE_RANGED_SPECIAL,

        // Death
        DEATH_START,
        DEATH_FINAL
    }

    /**
     * One FX entry that can hold:
     * - optional sound
     * - optional particles
     *
     * Use registry ids like "minecraft:entity.arrow.shoot"
     * and "minecraft:electric_spark".
     */
    public record Entry(
            @Nullable ResourceLocation soundId,
            float soundVolume,
            float soundPitch,

            @Nullable ResourceLocation particleId,
            int particleCount,
            double particleDx,
            double particleDy,
            double particleDz,
            double particleSpeed
    ) {
        public static Entry sound(ResourceLocation soundId, float volume, float pitch) {
            return new Entry(soundId, volume, pitch, null, 0, 0, 0, 0, 0);
        }

        public static Entry particles(ResourceLocation particleId, int count,
                                      double dx, double dy, double dz, double speed) {
            return new Entry(null, 0, 0, particleId, count, dx, dy, dz, speed);
        }

        public static Entry both(ResourceLocation soundId, float volume, float pitch,
                                 ResourceLocation particleId, int count,
                                 double dx, double dy, double dz, double speed) {
            return new Entry(soundId, volume, pitch, particleId, count, dx, dy, dz, speed);
        }

        /** Handy when you want an explicit "no fx" entry. */
        public static Entry none() {
            return new Entry(null, 0, 0, null, 0, 0, 0, 0, 0);
        }
    }
}
