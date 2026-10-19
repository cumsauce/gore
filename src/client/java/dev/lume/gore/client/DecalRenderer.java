package dev.lume.gore.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.lume.gore.BloodField;
import dev.lume.gore.Decal;
import dev.lume.gore.DecalFrame;
import dev.lume.gore.DecalMerge;
import dev.lume.gore.Flow;
import dev.lume.gore.Gore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Renders wall blood as one baked liquid surface per block face.
 *
 * <p>Decals are not drawn as individual quads any more. Each decal contributes
 * metaball kernels ({@link BloodField}) to every block face it reaches on its
 * plane; each such face owns a {@link BloodField#RES}² slot of one shared
 * dynamic texture, baked from the summed field and drawn as a single quad that
 * exactly covers the face. Overlapping stains therefore fuse like liquid
 * instead of stacking coplanar quads that z-fight, a pool spreads over the
 * spatter it reaches, and blood flows across block seams on one wall.
 *
 * <p>A face is re-baked every tick while something on it moves (a drip
 * running, a pool spreading) and every couple of seconds otherwise, for the
 * drying and fading clocks.
 *
 * <p>Registered on {@code LevelRenderEvents.COLLECT_SUBMITS}, the point where
 * the frame's geometry is baked; submitting from a draw event is a frame late.
 * The vanilla custom-geometry path is inert on 26.3, so each face goes through
 * {@code submitModelPart} like the other stains.
 */
public final class DecalRenderer {
	private static final Identifier TEXTURE_ID = Gore.id("dynamic/blood_field");
	private static final int RES = BloodField.RES;
	private static final int SLOTS_PER_ROW = 32;
	private static final int SIZE = RES * SLOTS_PER_ROW;
	private static final int MAX_SLOTS = SLOTS_PER_ROW * SLOTS_PER_ROW;
	/** Lift off the block face, in blocks. One quad per face, so nothing is coplanar with it but the wall. */
	private static final float WORLD_OFFSET = 0.004f;
	/** Ticks between re-bakes of a face whose blood is only drying. */
	private static final long SLOW_REBAKE = 40L;
	/** Upper bound on faces baked per frame, so a big sync never stalls a frame. */
	private static final int BAKES_PER_FRAME = 96;

	/** Drips whose falling drop has already been shed; one break-off per drip. */
	private static final Set<Decal> DRIPPED = new HashSet<>();

	private static DynamicTexture texture;
	private static RenderType renderType;
	private static final ModelPart[] SLOT_PARTS = new ModelPart[MAX_SLOTS];

	private static final Map<FaceKey, Face> FACES = new HashMap<>();
	private static final Deque<Integer> FREE_SLOTS = new ArrayDeque<>();
	private static int indexedVersion = -1;
	private static ClientLevel indexedLevel;
	private static final int[] PIXELS = new int[RES * RES];

	private record FaceKey(int x, int y, int z, int face) {
	}

	private static final class Face {
		final FaceKey key;
		final List<Decal> decals = new ArrayList<>();
		int slot = -1;
		boolean visible;
		long nextBake = Long.MIN_VALUE;

		Face(FaceKey key) {
			this.key = key;
		}
	}

	private DecalRenderer() {
	}

	/** True for wall drips (atlas cell 14), which flow as they age. */
	public static boolean isDrip(Decal d) {
		return DecalMerge.isDrip(d);
	}

	/** Forgets completed drips and every baked face; called on disconnect. */
	public static void clearDrips() {
		DRIPPED.clear();
		releaseAll();
		indexedVersion = -1;
		indexedLevel = null;
	}

	private static void init() {
		if (texture != null) {
			return;
		}

		texture = new DynamicTexture(() -> "gore blood field", SIZE, SIZE, true);
		texture.getPixels().fillRect(0, 0, SIZE, SIZE, 0);
		texture.upload();
		Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
		renderType = RenderTypes.entityTranslucent(TEXTURE_ID);

		for (int i = 0; i < MAX_SLOTS; i++) {
			FREE_SLOTS.add(i);
		}
	}

	private static ModelPart slotPart(int slot) {
		ModelPart part = SLOT_PARTS[slot];

		if (part == null) {
			int sx = slot % SLOTS_PER_ROW;
			int sy = slot / SLOTS_PER_ROW;
			// A RES×RES front face whose texels map 1:1 onto the slot: model x
			// grows with texture u, model y with texture v (ModelPart.Cube NORTH).
			ModelPart.Cube cube = new ModelPart.Cube(sx * RES - 1, sy * RES - 1,
					-RES / 2.0f, -RES / 2.0f, 0.0f, RES, RES, 1.0f,
					0.0f, 0.0f, 0.0f, false, SIZE, SIZE, Set.of(Direction.NORTH));
			part = new ModelPart(List.of(cube), Map.of());
			SLOT_PARTS[slot] = part;
		}

		return part;
	}

	public static void render(LevelRenderContext context) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return;
		}

		init();

		if (indexedVersion != DecalManager.version() || indexedLevel != level) {
			reindex(level);
		}

		if (FACES.isEmpty()) {
			return;
		}

		long now = level.getGameTime();
		shedDrips(level, now);
		bakeDue(level, now);

		PoseStack poseStack = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poseStack.pushPose();
		poseStack.translate(-camera.x, -camera.y, -camera.z);

		for (Face face : FACES.values()) {
			if (face.visible && face.slot >= 0) {
				submit(context, poseStack, face, level);
			}
		}

		poseStack.popPose();
	}

	/** Rebuilds which faces each decal reaches; keeps the slots of faces that survive. */
	private static void reindex(ClientLevel level) {
		List<Decal> decals = DecalManager.snapshot();
		long now = level.getGameTime();
		Map<FaceKey, Face> next = new HashMap<>();

		for (Decal decal : decals) {
			Direction dir = Direction.from3DDataValue(decal.face());
			Vec3 a1 = Decal.axis1(dir);
			Vec3 a2 = Decal.axis2(dir);
			int along = BloodField.planeCoord(decal.x(), decal.y(), decal.z(), new Vec3(dir.getAxis() == Direction.Axis.X ? 1 : 0,
					dir.getAxis() == Direction.Axis.Y ? 1 : 0, dir.getAxis() == Direction.Axis.Z ? 1 : 0));
			double minU = Double.MAX_VALUE;
			double maxU = -Double.MAX_VALUE;
			double minV = Double.MAX_VALUE;
			double maxV = -Double.MAX_VALUE;

			// Extent at full growth, so a running drip is indexed into the face it will reach.
			for (BloodField.Kernel k : BloodField.kernels(settled(decal), now)) {
				minU = Math.min(minU, k.minU());
				maxU = Math.max(maxU, k.maxU());
				minV = Math.min(minV, k.minV());
				maxV = Math.max(maxV, k.maxV());
			}

			if (minU > maxU) {
				continue;
			}

			for (int i = (int) Math.round(minU); i <= (int) Math.round(maxU); i++) {
				for (int j = (int) Math.round(minV); j <= (int) Math.round(maxV); j++) {
					BlockPos pos = blockAt(dir, a1, a2, i, j, along);
					FaceKey key = new FaceKey(pos.getX(), pos.getY(), pos.getZ(), decal.face());
					next.computeIfAbsent(key, Face::new).decals.add(decal);
				}
			}
		}

		// Carry slots over for faces that still exist; free the rest.
		for (Map.Entry<FaceKey, Face> entry : FACES.entrySet()) {
			Face old = entry.getValue();
			Face kept = next.get(entry.getKey());

			if (kept != null && old.slot >= 0) {
				kept.slot = old.slot;
				kept.visible = old.visible;
			} else if (old.slot >= 0) {
				FREE_SLOTS.add(old.slot);
			}
		}

		FACES.clear();
		FACES.putAll(next);

		for (Face face : FACES.values()) {
			face.nextBake = Long.MIN_VALUE;
		}

		DRIPPED.retainAll(new HashSet<>(decals));
		indexedVersion = DecalManager.version();
		indexedLevel = level;
	}

	/** The decal as it will look once fully grown, for indexing its reach. */
	private static Decal settled(Decal d) {
		return d.born() == 0L ? d : new Decal(d.x(), d.y(), d.z(), d.face(), d.u(), d.v(), d.size(), d.rot(),
				d.variant(), d.born() - Flow.T_TICKS - 1);
	}

	/** The block whose face sits at plane coordinates {@code (i, j)} on the plane {@code along}. */
	private static BlockPos blockAt(Direction dir, Vec3 a1, Vec3 a2, int i, int j, int along) {
		Vec3 p = a1.scale(i).add(a2.scale(j));
		int x = (int) Math.round(p.x);
		int y = (int) Math.round(p.y);
		int z = (int) Math.round(p.z);

		switch (dir.getAxis()) {
			case X -> x = along;
			case Y -> y = along;
			case Z -> z = along;
		}

		return new BlockPos(x, y, z);
	}

	private static void bakeDue(ClientLevel level, long now) {
		int budget = BAKES_PER_FRAME;
		boolean uploaded = false;
		NativeImage image = texture.getPixels();

		for (Face face : FACES.values()) {
			if (now < face.nextBake || budget <= 0) {
				continue;
			}

			budget--;
			boolean animating = false;
			List<BloodField.Kernel> kernels = new ArrayList<>();

			for (Decal decal : face.decals) {
				kernels.addAll(BloodField.kernels(decal, now));
				animating |= BloodField.animating(decal, now);
			}

			face.nextBake = now + (animating ? 1L : SLOW_REBAKE);
			Direction dir = Direction.from3DDataValue(face.key.face());
			int pu = BloodField.planeCoord(face.key.x(), face.key.y(), face.key.z(), Decal.axis1(dir));
			int pv = BloodField.planeCoord(face.key.x(), face.key.y(), face.key.z(), Decal.axis2(dir));
			boolean any = hasSurface(level, face.key, dir) && BloodField.bake(kernels, pu, pv, PIXELS);

			if (!any) {
				face.visible = false;
				continue;
			}

			if (face.slot < 0) {
				Integer slot = FREE_SLOTS.poll();

				if (slot == null) {
					continue;
				}

				face.slot = slot;
			}

			int ox = (face.slot % SLOTS_PER_ROW) * RES;
			int oy = (face.slot / SLOTS_PER_ROW) * RES;

			for (int j = 0; j < RES; j++) {
				for (int i = 0; i < RES; i++) {
					image.setPixel(ox + i, oy + j, PIXELS[j * RES + i]);
				}
			}

			face.visible = true;
			uploaded = true;
		}

		if (uploaded) {
			texture.upload();
		}
	}

	/** Blood only shows on a real face: a block that is there, not buried behind another. */
	private static boolean hasSurface(ClientLevel level, FaceKey key, Direction dir) {
		BlockPos pos = new BlockPos(key.x(), key.y(), key.z());
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !level.getBlockState(pos.relative(dir)).isSolidRender();
	}

	private static void shedDrips(ClientLevel level, long now) {
		for (Face face : FACES.values()) {
			for (Decal decal : face.decals) {
				if (!isDrip(decal) || decal.born() == 0L) {
					continue;
				}

				float f = Flow.length(now - decal.born());

				if (f >= Flow.BREAK_OFF && DRIPPED.add(decal)) {
					Direction dir = Direction.from3DDataValue(decal.face());
					Vec3 anchor = Vec3.atCenterOf(new BlockPos(decal.x(), decal.y(), decal.z()))
							.add(new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ()).scale(0.52))
							.add(Decal.axis1(dir).scale(decal.u()))
							.add(Decal.axis2(dir).scale(decal.v()));
					Vec3 bead = anchor.subtract(DecalFrame.tail(dir, decal.rot()).scale(f * decal.size() * 0.95));
					level.addParticle(Gore.BLOOD_DROP, bead.x, bead.y, bead.z, 0.0, -0.02, 0.0);
				}
			}
		}
	}

	private static void submit(LevelRenderContext context, PoseStack poseStack, Face face, ClientLevel level) {
		FaceKey key = face.key;
		Direction dir = Direction.from3DDataValue(key.face());
		BlockPos lightPos = new BlockPos(key.x(), key.y(), key.z()).relative(dir);
		int light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPos),
				level.getBrightness(LightLayer.SKY, lightPos));
		float lift = 0.5f + WORLD_OFFSET;

		poseStack.pushPose();
		poseStack.translate(key.x() + 0.5 + dir.getStepX() * lift,
				key.y() + 0.5 + dir.getStepY() * lift,
				key.z() + 0.5 + dir.getStepZ() * lift);
		// rot 90 puts the tail on axis2, so model X = axis1 and model Y = −axis2,
		// matching BloodField.bake's texel layout.
		poseStack.rotate(DecalFrame.orientation(dir, 90.0f));
		// The cube is RES model units (RES/16 blocks) wide; scale it to one block.
		float scale = 16.0f / RES;
		poseStack.scale(scale, scale, scale);
		context.submitNodeCollector().submitModelPart(slotPart(face.slot), poseStack, renderType,
				light, OverlayTexture.NO_OVERLAY, null, 0xFFFFFFFF);
		poseStack.popPose();
	}

	private static void releaseAll() {
		for (Face face : FACES.values()) {
			if (face.slot >= 0) {
				FREE_SLOTS.add(face.slot);
			}
		}

		FACES.clear();
	}
}
