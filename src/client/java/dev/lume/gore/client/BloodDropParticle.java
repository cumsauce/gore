package dev.lume.gore.client;

import dev.lume.gore.Ballistics;
import dev.lume.gore.ParticleLook;
import dev.lume.gore.SprayModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A millimetre droplet of blood. Quadratic air drag brings it to a ~7 m/s terminal
 * speed, so it still flies metres instead of dropping like a snowflake. On landing it
 * sits for a tick or two and vanishes — the server-side decal is the lasting mark —
 * and it dies instantly in water, so nothing floats on the surface.
 */
public class BloodDropParticle extends SingleQuadParticle {
	private final double radiusM;
	private int landedAt = -1;

	public BloodDropParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.radiusM = SprayModel.dropRadiusM(this.random);
		this.quadSize = (float) (0.03 + 0.05 * this.radiusM / 1e-3);
		this.lifetime = 60;
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

	@Override
	public void tick() {
		// Vanilla tick() applies its own gravity and friction; this droplet is driven
		// entirely by Ballistics, so super.tick() must never run (it would double-apply).
		this.age++;

		if (this.onGround && this.landedAt < 0) {
			this.landedAt = this.age;
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

		// A droplet that meets water is gone: no floating specks on the surface.
		if (!this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).isEmpty()) {
			this.remove();
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
