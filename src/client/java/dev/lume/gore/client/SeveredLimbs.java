package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.Dismemberment;
import dev.lume.gore.Limb;
import dev.lume.gore.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Client side of a limb coming off: the limb itself flies away as a copy of
 * the creature's own model part, with its texture, tumbling under gravity,
 * bouncing and coming to rest on the ground while it bleeds; and the stump it
 * left keeps dripping for a few seconds.
 *
 * <p>The flying limb is not an entity: nothing interacts with it, so it lives
 * in this list, ticks on the client and is drawn with the rest of the frame
 * from {@code COLLECT_SUBMITS}, like the wall blood.
 */
public final class SeveredLimbs {
	/** Ticks a severed limb lies on the ground before it shrinks away. */
	private static final int LIFETIME = 600;
	private static final int SHRINK = 30;
	/** Ticks a stump keeps dripping. */
	private static final int STUMP_BLEED = 160;
	private static final double GRAVITY = 0.06;
	private static final int MAX_LIMBS = 48;

	private static final List<Flying> FLYING = new ArrayList<>();
	/** Entity id → (limb ordinal → tick the stump stops bleeding). */
	private static final Map<Integer, Map<Integer, Long>> STUMPS = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private static final class Flying {
		final ModelPart part;
		final Identifier texture;
		final Vec3 centre;
		Vec3 pos;
		Vec3 prev;
		Vec3 vel;
		final Quaternionf rot = new Quaternionf();
		final Quaternionf prevRot = new Quaternionf();
		final Vec3 spinAxis;
		float spin;
		int age;
		boolean resting;

		Flying(ModelPart part, Identifier texture, Vec3 centre, Vec3 pos, Vec3 vel, Vec3 spinAxis, float spin) {
			this.part = part;
			this.texture = texture;
			this.centre = centre;
			this.pos = pos;
			this.prev = pos;
			this.vel = vel;
			this.spinAxis = spinAxis;
			this.spin = spin;
		}
	}

	private SeveredLimbs() {
	}

	/** A limb just came off {@code entityId}: throw it, and start the stump bleeding. */
	public static void sever(ClientLevel level, int entityId, Limb limb, Vec3 dir) {
		Entity entity = level.getEntity(entityId);
		if (!(entity instanceof LivingEntity living)) {
			return;
		}

		STUMPS.computeIfAbsent(entityId, id -> new HashMap<>())
				.put(limb.ordinal(), level.getGameTime() + STUMP_BLEED);

		EntityRenderer<? super LivingEntity, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(living);
		if (!(renderer instanceof LivingEntityRenderer<?, ?, ?> livingRenderer)) {
			return;
		}

		@SuppressWarnings("unchecked")
		LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?> typed =
				(LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?>) livingRenderer;
		ModelPart source = LimbRender.part(typed.getModel(), limb);
		if (source == null) {
			return;
		}

		Identifier texture = typed.getTextureLocation(typed.createRenderState(living, 1.0f));
		ModelPart copy = copy(source);
		Vec3 centre = centre(copy);
		Vec3 from = Dismemberment.joint(living, limb);
		Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
		Vec3 out = flat.lengthSqr() < 1.0e-4 ? new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5) : flat;
		Vec3 vel = out.normalize().scale(0.18 + RANDOM.nextDouble() * 0.12)
				.add(0.0, 0.22 + RANDOM.nextDouble() * 0.12, 0.0);
		Vec3 axis = new Vec3(RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.5).normalize();
		Flying flying = new Flying(copy, texture, centre, from, vel, axis, 0.35f + RANDOM.nextFloat() * 0.3f);
		flying.rot.rotateY((float) Math.toRadians(-living.yBodyRot));
		flying.prevRot.set(flying.rot);
		FLYING.add(flying);
		while (FLYING.size() > MAX_LIMBS) {
			FLYING.remove(0);
		}

		// The cut sprays as it parts.
		for (int i = 0; i < 6; i++) {
			Vec3 v = vel.scale(0.6).add((RANDOM.nextDouble() - 0.5) * 0.15, RANDOM.nextDouble() * 0.12, (RANDOM.nextDouble() - 0.5) * 0.15);
			spawnDrop(level, from, v);
		}
	}

	/** A deep copy of the part's geometry, in its rest pose, so the live model can keep animating. */
	private static ModelPart copy(ModelPart part) {
		ModelPartAccessor acc = (ModelPartAccessor) (Object) part;
		Map<String, ModelPart> children = new HashMap<>();
		acc.gore$children().forEach((name, child) -> {
			ModelPart c = copy(child);
			c.setInitialPose(child.getInitialPose());
			c.resetPose();
			children.put(name, c);
		});
		return new ModelPart(acc.gore$cubes(), children);
	}

	/** The middle of the part's cubes, in model units, so it tumbles about its own centre. */
	private static Vec3 centre(ModelPart part) {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).gore$cubes()) {
			minX = Math.min(minX, cube.minX);
			minY = Math.min(minY, cube.minY);
			minZ = Math.min(minZ, cube.minZ);
			maxX = Math.max(maxX, cube.maxX);
			maxY = Math.max(maxY, cube.maxY);
			maxZ = Math.max(maxZ, cube.maxZ);
		}
		return minX > maxX ? Vec3.ZERO : new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
	}

	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			FLYING.clear();
			STUMPS.clear();
			return;
		}

		for (Iterator<Flying> it = FLYING.iterator(); it.hasNext(); ) {
			Flying f = it.next();
			f.age++;
			f.prev = f.pos;
			f.prevRot.set(f.rot);
			if (f.age > LIFETIME) {
				it.remove();
				continue;
			}
			if (!f.resting) {
				step(level, f);
			}
		}

		long now = level.getGameTime();
		for (Iterator<Map.Entry<Integer, Map<Integer, Long>>> it = STUMPS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Map<Integer, Long>> e = it.next();
			Entity entity = level.getEntity(e.getKey());
			e.getValue().values().removeIf(until -> until <= now);
			if (!(entity instanceof LivingEntity living) || e.getValue().isEmpty()) {
				it.remove();
				continue;
			}
			if (now % 3 != 0) {
				continue;
			}
			for (int ordinal : e.getValue().keySet()) {
				Vec3 at = Dismemberment.joint(living, Limb.values()[ordinal]);
				Vec3 out = at.subtract(living.position().add(0.0, living.getBbHeight() * 0.5, 0.0));
				Vec3 v = out.lengthSqr() < 1.0e-4 ? Vec3.ZERO : out.normalize().scale(0.05);
				spawnDrop(level, at, v.add((RANDOM.nextDouble() - 0.5) * 0.03, 0.0, (RANDOM.nextDouble() - 0.5) * 0.03));
			}
		}
	}

	/** One tick of flight: gravity, drag, a bounce off whatever it meets, rest when it is spent. */
	private static void step(ClientLevel level, Flying f) {
		Vec3 vel = f.vel.add(0.0, -GRAVITY, 0.0).scale(0.98);
		AABB box = new AABB(f.pos, f.pos).inflate(0.08);
		Vec3 moved = Entity.collideBoundingBox((Entity) null, vel, box, level, List.of());
		boolean hitY = moved.y != vel.y;
		boolean hitX = moved.x != vel.x;
		boolean hitZ = moved.z != vel.z;
		f.pos = f.pos.add(moved);
		f.rot.rotateAxis(f.spin, (float) f.spinAxis.x, (float) f.spinAxis.y, (float) f.spinAxis.z);

		if (hitX) {
			vel = new Vec3(-vel.x * 0.25, vel.y, vel.z);
		}
		if (hitZ) {
			vel = new Vec3(vel.x, vel.y, -vel.z * 0.25);
		}
		if (hitY) {
			// Wet meat does not bounce much: it slaps down and skids.
			boolean landing = vel.y < 0.0;
			if (landing && vel.y < -0.12) {
				for (int i = 0; i < 3; i++) {
					spawnDrop(level, f.pos, new Vec3((RANDOM.nextDouble() - 0.5) * 0.12, 0.05 + RANDOM.nextDouble() * 0.06,
							(RANDOM.nextDouble() - 0.5) * 0.12));
				}
			}
			double bounce = -vel.y * 0.25;
			vel = new Vec3(vel.x * 0.6, Math.abs(bounce) < 0.05 ? 0.0 : bounce, vel.z * 0.6);
			f.spin *= 0.5f;
			if (landing && vel.y == 0.0 && vel.horizontalDistance() < 0.01) {
				f.resting = true;
				vel = Vec3.ZERO;
			}
		}

		// A trail of drops while it tumbles, thinning out as it goes.
		if (!f.resting && f.age < 30 && f.age % 2 == 0) {
			spawnDrop(level, f.pos, f.vel.scale(0.3));
		}
		f.vel = vel;
	}

	private static void spawnDrop(ClientLevel level, Vec3 at, Vec3 v) {
		if (BloodDropParticle.sprites() == null) {
			return;
		}
		Minecraft.getInstance().particleEngine.add(new BloodDropParticle(level, at.x, at.y, at.z, v.x, v.y, v.z,
				BloodDropParticle.sprites()));
	}

	public static void render(LevelRenderContext context) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || FLYING.isEmpty()) {
			return;
		}

		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack pose = context.poseStack();

		for (Flying f : FLYING) {
			Vec3 p = f.prev.lerp(f.pos, partial);
			BlockPos lightPos = BlockPos.containing(p);
			int light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos),
					level.getBrightness(LightLayer.SKY, lightPos));
			int left = LIFETIME - f.age;
			float scale = left < SHRINK ? Math.max(0.0f, (left - partial) / SHRINK) : 1.0f;

			pose.pushPose();
			pose.translate(p.x - camera.x, p.y - camera.y, p.z - camera.z);
			pose.rotate(new Quaternionf(f.prevRot).slerp(f.rot, partial));
			// Model space is upside down and mirrored, as LivingEntityRenderer draws it.
			pose.scale(-scale, -scale, scale);
			pose.translate(-f.centre.x / 16.0, -f.centre.y / 16.0, -f.centre.z / 16.0);
			context.submitNodeCollector().submitModelPart(f.part, pose, RenderTypes.entityCutout(f.texture),
					light, OverlayTexture.NO_OVERLAY, null);
			pose.popPose();
		}
	}

	public static void clear() {
		FLYING.clear();
		STUMPS.clear();
	}
}
