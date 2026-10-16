package dev.lume.gore.client;

import dev.lume.gore.Ballistics;
import dev.lume.gore.SprayModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A micrometre mist of blood ejected by a hard impact. Stokes drag stops it within a
 * couple of ticks inside ~0.3 blocks; it then hangs where it was thrown, drifts on a
 * random walk, and evaporates in under 1.5 seconds (alpha decays exponentially and the
 * sprite advances as it spreads).
 */
public class BloodMistParticle extends SingleQuadParticle {
	private final SpriteSet sprites;
	private final double radiusM;

	protected BloodMistParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.radiusM = SprayModel.mistRadiusM(this.random);
		this.quadSize = (float) (0.08 + 0.08 * (this.radiusM - 2e-5) / 4e-5);
		this.lifetime = 12 + this.random.nextInt(13);
		this.hasPhysics = false;
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
		// Vanilla tick() applies its own gravity and friction; this mist is driven
		// entirely by Ballistics, so super.tick() must never run (it would double-apply).
		this.age++;

		if (this.age >= this.lifetime) {
			this.remove();
			return;
		}

		Vec3 v = Ballistics.step(new Vec3(this.xd, this.yd, this.zd), this.radiusM)
				.add(this.random.nextGaussian() * 0.002,
						this.random.nextGaussian() * 0.002,
						this.random.nextGaussian() * 0.002);
		this.xd = v.x;
		this.yd = v.y;
		this.zd = v.z;
		this.move(this.xd, this.yd, this.zd);
		this.quadSize *= 1.03f; // dispersion
		this.alpha = 0.9f * (float) Math.exp(-3.0 * this.age / this.lifetime); // evaporation
		this.setSpriteFromAge(this.sprites);
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
