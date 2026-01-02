package com.chriskatze.catomobs.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;
import org.jetbrains.annotations.Nullable;

public class PlaceholderParticle extends TextureSheetParticle {

    protected PlaceholderParticle(ClientLevel level, double x, double y, double z,
                                  double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, xd, yd, zd);

        this.friction = 0.86F;
        this.gravity = 0.0F;

        // size + lifetime
        this.quadSize = 0.14F + this.random.nextFloat() * 0.08F;
        this.lifetime = 10 + this.random.nextInt(8);

        // yellow-ish (RGB 0..1)
        this.rCol = 1.0F;
        this.gCol = 0.95F;
        this.bCol = 0.25F;

        // subtle drift
        this.xd *= 0.25;
        this.yd *= 0.15;
        this.zd *= 0.25;

        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();

        // fade out near end
        float lifeFrac = (float) this.age / (float) this.lifetime;
        this.alpha = 1.0F - lifeFrac;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    // Provider
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd) {
            return new PlaceholderParticle(level, x, y, z, xd, yd, zd, sprites);
        }
    }
}
