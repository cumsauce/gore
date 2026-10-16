package dev.lume.gore.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/** A short-lived airborne puff of blood ejected by a hard impact. */
public class BloodMistParticle extends SingleQuadParticle {
	private final SpriteSet sprites;

	protected BloodMistParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.quadSize = 0.10f + this.random.nextFloat() * 0.10f;
		this.lifetime = 8 + this.random.nextInt(10);
		this.gravity = 0.25f;
		this.friction = 0.90f;
		this.xd = vx + (this.random.nextDouble() - 0.5) * 0.06;
		this.yd = vy + (this.random.nextDouble() - 0.5) * 0.06;
		this.zd = vz + (this.random.nextDouble() - 0.5) * 0.06;
		this.rCol = 0.62f;
		this.gCol = 0.075f;
		this.bCol = 0.07f;
		this.alpha = 0.9f;
	}

	@Override
	public void tick() {
		super.tick();
		this.setSpriteFromAge(this.sprites);
		this.quadSize *= 1.01f;

		if (this.lifetime > 0) {
			this.alpha = 0.9f * (1.0f - (float) this.age / (float) this.lifetime);
		}
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.bySprite(this.sprite);
	}

	public static class Provider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz, RandomSource random) {
			return new BloodMistParticle(level, x, y, z, vx, vy, vz, this.sprites);
		}
	}
}
