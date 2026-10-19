package dev.lume.gore.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * An arterial jet: an invisible-ish emitter at a wound that keeps throwing
 * droplets for a fraction of a second, along the velocity the server handed it.
 *
 * <p>A burst of droplets is a splash; a jet is a wound under pressure. The
 * difference on screen is time — the stream has to keep arriving for long enough
 * to read as a stream — so this particle owns no visible body of its own worth
 * looking at, only a small fading mist puff so the origin is not bare. Each tick
 * it spawns one or two real {@link BloodDropParticle}s, which fly and land with
 * the same ballistics as every other droplet.
 *
 * <p>The velocity passed to {@link #createParticle} is the jet's direction and
 * initial speed, not a position offset: {@code sendParticles(..., count = 0,
 * dx, dy, dz, speed)} hands the client the raw vector, which the server sets
 * from the impact direction.
 */
public class BloodJetParticle extends SingleQuadParticle {
	/** Emission cone radius, in blocks of lateral velocity per unit of speed. */
	private static final double SPREAD = 0.16;

	private final SpriteSet dropSprites;
	private final Vec3 dir;

	protected BloodJetParticle(ClientLevel level, double x, double y, double z,
			double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.dropSprites = sprites;
		Vec3 d = new Vec3(vx, vy, vz);
		if (d.lengthSqr() < 1.0E-8) {
			d = new Vec3(0.0, -1.0, 0.0);
		}
		this.dir = d.normalize();
		// A few ticks: long enough for the stream to read, short enough that a
		// mob does not keep bleeding into the air forever.
		this.lifetime = 9 + this.random.nextInt(6);
		this.hasPhysics = false;
		this.quadSize = 0.09f;
		this.roll = (float) (this.random.nextDouble() * Math.PI * 2.0);
		this.oRoll = this.roll;
		// The puff is only there to hide the abrupt origin of the stream.
		this.rCol = 0.45f;
		this.gCol = 0.03f;
		this.bCol = 0.025f;
		this.alpha = 0.5f;
	}

	@Override
	public void tick() {
		// Not super.tick(): this emitter has no vanilla physics and must not be
		// dragged by gravity or friction.
		// super.tick() is what normally advances the interpolation origin; without
		// this, every frame is drawn between the spawn point and the current
		// position, and the particle flickers along its whole path.
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		this.age++;

		if (this.age >= this.lifetime) {
			this.remove();
			return;
		}

		float life = this.age / (float) this.lifetime;
		// The pressure behind the jet drops as the wound empties.
		double speed = (0.25 + this.random.nextDouble() * 0.15) * (1.0 - 0.65 * life);

		// Two axes spanning the plane perpendicular to the jet.
		Vec3 helper = Math.abs(this.dir.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
		Vec3 u = this.dir.cross(helper).normalize();
		Vec3 v = this.dir.cross(u).normalize();

		// A pulse every other tick: a stream, not a hose.
		int drops = this.age % 2 == 0 ? 1 : 0;
		ParticleEngine engine = Minecraft.getInstance().particleEngine;

		for (int i = 0; i < drops; i++) {
			double angle = this.random.nextDouble() * Math.PI * 2.0;
			double radius = SPREAD * this.random.nextDouble();
			Vec3 velocity = this.dir.scale(speed)
					.add(u.scale(Math.cos(angle) * radius))
					.add(v.scale(Math.sin(angle) * radius));
			engine.add(new BloodDropParticle(this.level, this.x, this.y, this.z,
					velocity.x, velocity.y, velocity.z, this.dropSprites));
		}

		this.alpha = 0.5f * (1.0f - life);
		this.quadSize *= 1.04f;
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
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
				double vx, double vy, double vz, RandomSource random) {
			return new BloodJetParticle(level, x, y, z, vx, vy, vz, this.sprites);
		}
	}
}
