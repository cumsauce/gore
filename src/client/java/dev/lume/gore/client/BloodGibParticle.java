package dev.lume.gore.client;

import dev.lume.gore.Ballistics;
import dev.lume.gore.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A chunk of flesh thrown by a kill. It tumbles along an exact gravity arc to
 * the floor smear the server placed for it, landing on the smear's born tick,
 * then skids along the smear's direction (the streak is the mark it leaves),
 * shedding a few droplets, and lies there for a while before it shrinks away.
 */
public class BloodGibParticle extends SingleQuadParticle {
	/** Trace id (see ParticleTrace); 0 when tracing is off. */
	private final int traceId = ParticleTrace.nextId();
	/** Ticks a landed chunk skids before it stops. */
	private static final int SKID_TICKS = 6;
	/** Ticks it lies still, then {@link #SHRINK_TICKS} to shrink away. */
	private static final int REST_TICKS = 160;
	private static final int SHRINK_TICKS = 20;

	/** Set by the provider, so gibs can be launched from a payload handler. */
	private static SpriteSet providerSprites;

	private final float spin;
	private final float baseSize;
	private Vec3 flightFrom;
	private Vec3 flightV0;
	private int flightTicks;
	private Vec3 landing;
	private Vec3 skid;
	private int landedAt = -1;

	protected BloodGibParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.hasPhysics = false;
		this.quadSize = 0.16f + this.random.nextFloat() * 0.12f;
		this.baseSize = this.quadSize;
		this.spin = (float) ((this.random.nextDouble() - 0.5) * 0.9);
		this.roll = (float) (this.random.nextDouble() * Math.PI * 2.0);
		this.oRoll = this.roll;
		this.rCol = 1.0f;
		this.gCol = 1.0f;
		this.bCol = 1.0f;
		this.alpha = 1.0f;
		this.lifetime = 2000;
	}

	/**
	 * Throws a chunk from {@code from} to land on {@code smear} in
	 * {@code ticks}, then skid the way the smear points.
	 */
	public static void fly(ClientLevel level, Vec3 from, Decal smear, int ticks) {
		if (providerSprites == null) {
			return;
		}

		BloodGibParticle gib = new BloodGibParticle(level, from.x, from.y, from.z, providerSprites);
		int t = Math.max(1, ticks);
		Direction face = Direction.from3DDataValue(smear.face());
		double rad = Math.toRadians(smear.rot());
		gib.flightFrom = from;
		gib.flightTicks = t;
		// The quad spans ±quadSize about its centre: lift it that far so the
		// chunk sits on the floor instead of half inside it.
		gib.landing = Decal.surfacePoint(smear).add(0.0, gib.quadSize, 0.0);
		gib.flightV0 = Ballistics.arcVelocity(from, gib.landing, t);
		gib.skid = Decal.axis1(face).scale(Math.cos(rad)).add(Decal.axis2(face).scale(Math.sin(rad)))
				.scale(smear.size() * 0.5 / SKID_TICKS);
		gib.xd = gib.flightV0.x;
		gib.yd = gib.flightV0.y;
		gib.zd = gib.flightV0.z;
		gib.lifetime = t + SKID_TICKS + REST_TICKS + SHRINK_TICKS;
		Minecraft.getInstance().particleEngine.add(gib);
	}

	@Override
	public void tick() {
		ParticleTrace.record(this.traceId, "gib", this.level.getGameTime(), this.age, this.x, this.y, this.z,
				this.xo, this.yo, this.zo, this.xd, this.yd, this.zd, this.alpha, this.quadSize);
		// Driven by Ballistics, never by vanilla physics; see BloodDropParticle.
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		this.oRoll = this.roll;
		this.age++;

		if (this.age >= this.lifetime || this.flightFrom == null) {
			this.remove();
			return;
		}

		if (this.age <= this.flightTicks) {
			Vec3 p = Ballistics.arcPoint(this.flightFrom, this.flightV0, this.age);
			this.xd = this.flightV0.x;
			this.yd = this.flightV0.y - Ballistics.GRAVITY * this.age;
			this.zd = this.flightV0.z;
			this.roll += this.spin;
			this.setPos(p.x, p.y, p.z);

			if (this.age == this.flightTicks) {
				this.landedAt = this.age;
				this.splat();
			}
			return;
		}

		int since = this.age - this.landedAt;

		if (since <= SKID_TICKS) {
			// Friction: fast at first, stopping by the end of the skid. The
			// weights sum to SKID_TICKS so it ends on the smear's tail.
			double step = 2.0 * (SKID_TICKS + 1 - since) / (SKID_TICKS + 1);
			this.xd = this.skid.x * step;
			this.yd = 0.0;
			this.zd = this.skid.z * step;
			this.roll += this.spin * 0.3f * (float) step;
			this.setPos(this.x + this.xd, this.landing.y, this.z + this.zd);
			return;
		}

		this.xd = 0.0;
		this.yd = 0.0;
		this.zd = 0.0;
		int left = this.lifetime - this.age;
		if (left < SHRINK_TICKS) {
			this.quadSize = this.baseSize * left / (float) SHRINK_TICKS;
		}
	}

	/** Landing throws a few droplets up and out: the chunk is wet. */
	private void splat() {
		if (BloodDropParticle.sprites() == null) {
			return;
		}
		ParticleEngine engine = Minecraft.getInstance().particleEngine;
		int count = 3 + this.random.nextInt(3);
		for (int i = 0; i < count; i++) {
			double angle = this.random.nextDouble() * Math.PI * 2.0;
			double speed = 0.04 + this.random.nextDouble() * 0.08;
			engine.add(new BloodDropParticle(this.level, this.x, this.y, this.z,
					Math.cos(angle) * speed, 0.06 + this.random.nextDouble() * 0.08, Math.sin(angle) * speed,
					BloodDropParticle.sprites()));
		}
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.bySprite(this.sprite);
	}

	public static class Provider implements ParticleProvider<SimpleParticleType> {
		public Provider(SpriteSet sprites) {
			providerSprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
				double vx, double vy, double vz, RandomSource random) {
			// Gibs only fly to server-placed smears (see fly); a bare particle
			// command has nowhere to land them.
			return null;
		}
	}
}
