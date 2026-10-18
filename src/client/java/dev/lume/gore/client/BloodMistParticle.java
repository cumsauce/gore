package dev.lume.gore.client;

import dev.lume.gore.Ballistics;
import dev.lume.gore.ParticleLook;
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
 *
 * <p>Deliberately does <em>not</em> stretch along its velocity: a droplet smears because
 * it crosses its own width in a frame, whereas mist is a cloud whose droplets have
 * already been stopped by drag. Each puff is given a fixed random roll instead, so three
 * noise sprites do not read as three identical squares.
 */
public class BloodMistParticle extends SingleQuadParticle {
	private final SpriteSet sprites;
	private final double radiusM;

	protected BloodMistParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.radiusM = SprayModel.mistRadiusM(this.random);
		this.quadSize = ParticleLook.quadFor((float) this.radiusM);
		// The cloud has to hang: a floor of 40 ticks keeps it reading as volume between
		// two frames instead of blinking out like a dot.
		this.lifetime = ParticleLook.MIST_LIFETIME_FLOOR + this.random.nextInt(24);
		this.hasPhysics = false;
		// Fixed per-puff roll so the puffs do not all present the same face of the noise.
		this.roll = (float) (this.random.nextDouble() * Math.PI * 2.0);
		this.oRoll = this.roll;
		this.xd = vx + (this.random.nextDouble() - 0.5) * 0.06;
		this.yd = vy + (this.random.nextDouble() - 0.5) * 0.06;
		this.zd = vz + (this.random.nextDouble() - 0.5) * 0.06;
		// Several puffs overlap, so each is a dark translucent red: a bright
		// saturated tint with a dozen of them on screen stops reading as blood
		// and starts reading as a red filter over the camera.
		this.rCol = 0.5f;
		this.gCol = 0.035f;
		this.bCol = 0.03f;
		this.alpha = ParticleLook.MIST_PEAK_ALPHA;
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
		this.quadSize *= 1.015f; // dispersion
		this.alpha = ParticleLook.alphaAt((float) this.age / this.lifetime); // evaporation
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
