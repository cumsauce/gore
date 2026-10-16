package dev.lume.gore.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/** A droplet of blood that falls and briefly sits on whatever it lands on. */
public class BloodDropParticle extends SingleQuadParticle {
	public BloodDropParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.quadSize = 0.04f + this.random.nextFloat() * 0.04f;
		this.lifetime = 25 + this.random.nextInt(25);
		this.gravity = 0.85f;
		this.friction = 0.98f;
		this.hasPhysics = true;
		this.xd = vx + (this.random.nextDouble() - 0.5) * 0.04;
		this.yd = vy + (this.random.nextDouble() - 0.5) * 0.04;
		this.zd = vz + (this.random.nextDouble() - 0.5) * 0.04;
		this.rCol = 1.0f;
		this.gCol = 1.0f;
		this.bCol = 1.0f;
		this.alpha = 1.0f;
	}

	@Override
	public void tick() {
		super.tick();

		if (this.onGround) {
			this.xd = 0.0;
			this.zd = 0.0;
		}

		float fadeStart = this.lifetime * 0.75f;

		if (this.age > fadeStart) {
			this.alpha = 1.0f - (this.age - fadeStart) / (this.lifetime - fadeStart);
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
			return new BloodDropParticle(level, x, y, z, vx, vy, vz, this.sprites);
		}
	}
}
