package dev.lume.gore.client;

import dev.lume.gore.ParticleLook;
import dev.lume.gore.SprayModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * A micrometre mist of blood ejected by a hard impact. Stokes drag stops it within a
 * couple of ticks inside ~0.3 blocks; it then hangs where it was thrown, drifts on a
 * random walk, and evaporates over two to three seconds (alpha decays exponentially as
 * it spreads). That is deliberately far longer than a drop's ~8 ticks: drops fly and
 * land, while the cloud has to hang long enough to read as volume
 * ({@link ParticleLook#MIST_LIFETIME_FLOOR}).
 *
 * <p>Deliberately does <em>not</em> stretch along its velocity: a droplet smears because
 * it crosses its own width in a frame, whereas mist is a cloud whose droplets have
 * already been stopped by drag. Each puff is given a fixed random roll instead, so three
 * noise sprites do not read as three identical squares.
 */
public class BloodMistParticle extends SingleQuadParticle {
	/** Trace id (see ParticleTrace); 0 when tracing is off. */
	private final int traceId = ParticleTrace.nextId();
	/** Per-tick velocity retention: the throw is spent in about four ticks. */
	private static final double DRAG = 0.78;

	private final float baseSize;
	private final double driftX;
	private final double driftY;
	private final double driftZ;
	private final float spin;

	protected BloodMistParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		// One noise sprite for life: cycling sprites with age made each puff pop.
		this.quadSize = ParticleLook.quadFor((float) SprayModel.mistRadiusM(this.random));
		this.baseSize = this.quadSize;
		// The cloud has to hang: a floor of 40 ticks keeps it reading as volume between
		// two frames instead of blinking out like a dot.
		this.lifetime = ParticleLook.MIST_LIFETIME_FLOOR + this.random.nextInt(24);
		this.hasPhysics = false;
		// Fixed per-puff roll so the puffs do not all present the same face of the noise.
		this.roll = (float) (this.random.nextDouble() * Math.PI * 2.0);
		this.oRoll = this.roll;
		// The server's speed is a droplet's; the cloud only carries a fraction
		// of it, so it billows out half a block instead of shooting away.
		this.xd = vx * 0.35 + (this.random.nextDouble() - 0.5) * 0.02;
		this.yd = vy * 0.35 + (this.random.nextDouble() - 0.5) * 0.02;
		this.zd = vz * 0.35 + (this.random.nextDouble() - 0.5) * 0.02;
		this.driftX = (this.random.nextDouble() - 0.5) * 0.006;
		this.driftY = (this.random.nextDouble() - 0.5) * 0.004;
		this.driftZ = (this.random.nextDouble() - 0.5) * 0.006;
		this.spin = (float) ((this.random.nextDouble() - 0.5) * 0.04);
		// Several puffs overlap, so each is a dark translucent red: a bright
		// saturated tint with a dozen of them on screen stops reading as blood
		// and starts reading as a red filter over the camera.
		this.rCol = 0.5f;
		this.gCol = 0.035f;
		this.bCol = 0.03f;
		this.alpha = 0.0f;
	}

	@Override
	public void tick() {
		ParticleTrace.record(this.traceId, "mist", this.level.getGameTime(), this.age, this.x, this.y, this.z,
				this.xo, this.yo, this.zo, this.xd, this.yd, this.zd, this.alpha, this.quadSize);
		// Not super.tick(): vanilla gravity and friction are wrong for a cloud.
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		this.oRoll = this.roll;
		this.age++;

		if (this.age >= this.lifetime) {
			this.remove();
			return;
		}

		// A cloud of micrometre droplets moves as a body of air, not as its
		// droplets: it is carried forward by the blow and then brakes smoothly
		// (exponential, a few ticks), sinks very slowly and keeps a steady
		// drift. Per-tick random kicks made every puff shake in place; the
		// Stokes stop of a single droplet made the cloud pop into existence
		// at the end of its throw.
		this.xd = this.xd * DRAG + this.driftX * (1.0 - DRAG);
		this.yd = this.yd * DRAG + (this.driftY - 0.0015) * (1.0 - DRAG);
		this.zd = this.zd * DRAG + this.driftZ * (1.0 - DRAG);
		this.move(this.xd, this.yd, this.zd);
		this.roll += this.spin;
		float life = (float) this.age / this.lifetime;
		// Billows out fast while it still has momentum, then slowly.
		this.quadSize = this.baseSize * (1.0f + 1.6f * (1.0f - (float) Math.exp(-3.0f * life)));
		// Fade in over two ticks so a puff never blinks on at full strength.
		this.alpha = ParticleLook.alphaAt(life) * Math.min(1.0f, this.age / 2.0f);
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
