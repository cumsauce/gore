package dev.lume.gore.client;

import dev.lume.gore.Ballistics;
import dev.lume.gore.ParticleLook;
import dev.lume.gore.SprayModel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * A millimetre droplet of blood. Quadratic air drag brings it to a ~7 m/s terminal
 * speed, so it still flies metres instead of dropping like a snowflake. While it flies
 * it is drawn as a streak aligned to its own velocity — the eye reads motion blur, not
 * a dot. On landing it kicks two or three micro-droplets sideways, sits a tick or two
 * and vanishes: the server-side decal is the lasting mark. It dies instantly in water,
 * so nothing floats on the surface.
 */
public class BloodDropParticle extends SingleQuadParticle {
	/** Trace id (see ParticleTrace); 0 when tracing is off. */
	private final int traceId = ParticleTrace.nextId();
	private final SpriteSet sprites;
	private final double radiusM;
	/** Splash offspring: they inherit the physics but must not splash again. */
	private final boolean micro;
	private final TextureAtlasSprite baseSprite;
	private final TextureAtlasSprite streakSprite;
	private int landedAt = -1;
	private boolean streaking;

	/** Set by the provider, so droplets can be launched from a payload handler. */
	private static SpriteSet providerSprites;

	/** Guided flight: launch point, launch velocity and flight time, or null for a free drop. */
	private Vec3 flightFrom;
	private Vec3 flightV0;
	private int flightTicks;

	public BloodDropParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		this(level, x, y, z, vx, vy, vz, sprites, false);
	}

	private BloodDropParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites, boolean micro) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.sprites = sprites;
		this.micro = micro;
		this.baseSprite = this.sprite;
		this.streakSprite = streakSpriteOf(sprites);
		// A splash droplet is a splinter of the one that landed: smallest radius in the
		// band, so drag stops it within a hand's breadth instead of sending it away.
		this.radiusM = micro ? ParticleLook.MIN_DROP_RADIUS_M : SprayModel.dropRadiusM(this.random);
		// Drawn larger than life on purpose: at a true millimetre a droplet is
		// sub-pixel at any useful range, so the quad carries the physical radius
		// up into something the eye can follow as it crosses the frame.
		this.quadSize = micro ? 0.02f : (float) (0.045 + 0.075 * this.radiusM / 1e-3);
		this.lifetime = micro ? 14 + this.random.nextInt(12) : 60;
		this.hasPhysics = true;
		this.xd = vx + (this.random.nextDouble() - 0.5) * 0.04;
		this.yd = vy + (this.random.nextDouble() - 0.5) * 0.04;
		this.zd = vz + (this.random.nextDouble() - 0.5) * 0.04;
		// Size, colour and opacity all come from the physical radius: a big droplet is
		// a dark opaque slab, a fleck is pale and see-through. See ParticleLook.
		int look = ParticleLook.look((float) this.radiusM);
		this.rCol = ARGB.red(look) / 255f;
		this.gCol = ARGB.green(look) / 255f;
		this.bCol = ARGB.blue(look) / 255f;
		this.alpha = ARGB.alpha(look) / 255f;
	}

	/**
	 * The blood_drop sheet is round, streak, fleck. {@code SpriteSet.get(i, j)} resolves
	 * to {@code sprites.get(i * (n - 1) / j)}, so with three sprites {@code get(1, 2)}
	 * is the streak — the one to draw while a droplet is in fast flight.
	 */
	private static TextureAtlasSprite streakSpriteOf(SpriteSet set) {
		return set.get(1, 2);
	}

	/**
	 * Point the quad's long axis along the velocity as it crosses the screen and swap to
	 * the streak sprite when the droplet is moving fast enough to smear. The roll is
	 * applied about the view axis after the billboard faces the camera, so the flight
	 * direction has to be projected onto the camera's left/up plane; a droplet coming
	 * straight at the viewer has no screen direction and stays a round bead.
	 */
	protected void orientToVelocity(Camera camera, float partialTick) {
		float speed = (float) Math.sqrt(this.xd * this.xd + this.yd * this.yd + this.zd * this.zd);
		if (speed < 1.0e-4f) {
			return;
		}
		Vector3fc left = camera.leftVector();
		Vector3fc up = camera.upVector();
		float l = (float) this.xd * left.x() + (float) this.yd * left.y() + (float) this.zd * left.z();
		float u = (float) this.xd * up.x() + (float) this.yd * up.y() + (float) this.zd * up.z();
		float projected = (float) Math.sqrt(l * l + u * u);
		if (projected < 0.25f * speed) {
			this.roll = 0.0f;
			this.oRoll = 0.0f;
			this.setSprite(this.baseSprite);
			return;
		}
		// The streak's long axis is +Y in the quad, so roll it onto the flight direction.
		float roll = (float) Math.atan2(u, l) - (float) (Math.PI * 0.5);
		this.roll = roll;
		this.oRoll = roll;
		// Hysteresis, so a droplet near the threshold does not flicker between sprites.
		float stretch = ParticleLook.stretch(speed);
		if (this.streaking ? stretch < 1.25f : stretch > 1.5f) {
			this.streaking = !this.streaking;
		}
		this.setSprite(this.streaking ? this.streakSprite : this.baseSprite);
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
		this.orientToVelocity(camera, partialTick);
		super.extract(state, camera, partialTick);
	}

	@Override
	public void tick() {
		ParticleTrace.record(this.traceId, this.flightFrom != null ? "flight" : this.micro ? "micro" : "drop", this.level.getGameTime(), this.age, this.x, this.y, this.z,
				this.xo, this.yo, this.zo, this.xd, this.yd, this.zd, this.alpha, this.quadSize);
		// Vanilla tick() applies its own gravity and friction; this droplet is driven
		// entirely by Ballistics, so super.tick() must never run (it would double-apply).
		// super.tick() is what normally advances the interpolation origin; without
		// this, every frame is drawn between the spawn point and the current
		// position, and the particle flickers along its whole path.
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;

		if (this.flightFrom != null) {
			this.tickFlight();
			return;
		}
		this.age++;

		if (this.onGround && this.landedAt < 0) {
			this.landedAt = this.age;
			if (!this.micro) {
				this.splash();
			}
		}

		if (this.age >= this.lifetime || (this.onGround && this.age > this.landedAt + 1)) {
			this.remove();
			return;
		}

		Vec3 v = Ballistics.step(new Vec3(this.xd, this.yd, this.zd), this.radiusM);
		this.xd = v.x;
		this.yd = v.y;
		this.zd = v.z;
		this.move(this.xd, this.yd, this.zd);

		// Particle.move zeroes a velocity component the world blocked. A drop
		// that meets a wall or ceiling sticks (the decal is its mark) instead of
		// sliding down the face as a streak.
		if ((v.x != 0.0 && this.xd == 0.0) || (v.z != 0.0 && this.zd == 0.0) || (v.y > 0.0 && this.yd == 0.0)) {
			this.remove();
			return;
		}

		// A droplet that meets water is gone: no floating specks on the surface.
		if (!this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).isEmpty()) {
			this.remove();
		}
	}

	/**
	 * Flies one visible droplet from the wound to the stain it becomes. The
	 * path is an exact gravity arc that reaches {@code to} in {@code ticks},
	 * the tick the server stamped as the stain's birth, so the mark appears
	 * the moment the droplet arrives — the spray and the splatter are the same
	 * blood. A bigger stain was a bigger drop.
	 */
	public static void fly(ClientLevel level, Vec3 from, Vec3 to, int ticks, float stainSize) {
		if (providerSprites == null) {
			return;
		}

		BloodDropParticle drop = new BloodDropParticle(level, from.x, from.y, from.z, 0, 0, 0, providerSprites, false);
		int t = Math.max(1, ticks);
		drop.flightFrom = from;
		drop.flightTicks = t;
		drop.flightV0 = Ballistics.arcVelocity(from, to, t);
		drop.xd = drop.flightV0.x;
		drop.yd = drop.flightV0.y;
		drop.zd = drop.flightV0.z;
		drop.hasPhysics = false;
		drop.lifetime = t + 1;
		drop.quadSize = 0.04f + 0.11f * stainSize;
		drop.alpha = 1.0f;
		Minecraft.getInstance().particleEngine.add(drop);
	}

	private void tickFlight() {
		this.age++;
		double t = Math.min(this.age, this.flightTicks);
		Vec3 p = Ballistics.arcPoint(this.flightFrom, this.flightV0, t);
		// Velocity only steers the streak orientation; the position is exact.
		this.xd = this.flightV0.x;
		this.yd = this.flightV0.y - Ballistics.GRAVITY * t;
		this.zd = this.flightV0.z;
		this.setPos(p.x, p.y, p.z);

		if (this.age >= this.flightTicks) {
			this.splash();
			this.remove();
		}
	}

	/**
	 * A droplet that hits a surface does not simply stop — it throws off a few
	 * micro-droplets that carry the impact sideways. That scatter is what separates a
	 * splash from a sticker.
	 */
	private void splash() {
		ParticleEngine engine = Minecraft.getInstance().particleEngine;
		int count = 1 + this.random.nextInt(2);
		for (int i = 0; i < count; i++) {
			double angle = this.random.nextDouble() * Math.PI * 2.0;
			double speed = 0.02 + this.random.nextDouble() * 0.05;
			engine.add(new BloodDropParticle(this.level, this.x, this.y, this.z,
					Math.cos(angle) * speed,
					0.02 + this.random.nextDouble() * 0.05,
					Math.sin(angle) * speed,
					this.sprites, true));
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
			providerSprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz, RandomSource random) {
			return new BloodDropParticle(level, x, y, z, vx, vy, vz, this.sprites);
		}
	}
}
