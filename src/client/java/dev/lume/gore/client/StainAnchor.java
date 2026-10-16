package dev.lume.gore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Maps a body stain's damage source to a body region, and that region to a part
 * of the entity's own model.
 *
 * <p>Both jobs are static and stateless. Resolution is defensive throughout: a
 * model with no usable part yields {@link Optional#empty()}, and the caller then
 * draws NOTHING. It must never throw and must never place a stain in mid-air
 * where no part of the body could carry it.
 */
public final class StainAnchor {
	/** Body regions a wound can be attributed to. */
	public enum Region {
		BODY,
		CHEST,
		HEAD,
		ARM,
		LEG,
		FOOT
	}

	/**
	 * Region lookup keyed by the damage type's identifier hash.
	 *
	 * <p>The key is {@code String.hashCode()} of the damage type's registry
	 * identifier (e.g. {@code "minecraft:arrow"}), exactly as
	 * {@code StainWounds.sourceType} computed it server-side. That hash is
	 * <b>sparse and may be NEGATIVE</b> — it is not a registry id and not a dense
	 * enumeration — so it must NEVER be used as an array index. A map keyed by
	 * the hash (or a switch over arbitrary ints) is the only safe encoding.
	 */
	private static final Map<Integer, Region> REGIONS = buildRegions();

	/**
	 * Per-model cache of {@link EntityModel#allParts()}, keyed weakly by the
	 * model instance. Models are rebuilt on resource reload, which is signalled
	 * through {@link #invalidate()}.
	 */
	private static final Map<EntityModel<?>, List<ModelPart>> ALL_PARTS = new WeakHashMap<>();

	private StainAnchor() {
	}

	private static Map<Integer, Region> buildRegions() {
		Map<Integer, Region> regions = new HashMap<>();

		// Melee and generic contact: BODY.
		register(regions, Region.BODY, "minecraft:generic", "minecraft:generic_kill", "minecraft:mob_attack",
				"minecraft:mob_attack_no_aggro", "minecraft:player_attack", "minecraft:in_wall", "minecraft:cramming",
				"minecraft:starve", "minecraft:cactus", "minecraft:dry_out", "minecraft:freeze",
				"minecraft:magic", "minecraft:indirect_magic", "minecraft:wither", "minecraft:dragon_breath",
				"minecraft:thorns", "minecraft:mace_smash", "minecraft:sonic_boom", "minecraft:fireworks",
				"minecraft:bad_respawn_point", "minecraft:outside_border", "minecraft:fell_out_of_world");

		// Explosions push outward from the blast centre, which lands on the body.
		register(regions, Region.BODY, "minecraft:explosion", "minecraft:player_explosion");

		// Projectiles and thrown things punch into the chest.
		register(regions, Region.CHEST, "minecraft:arrow", "minecraft:trident", "minecraft:spear",
				"minecraft:mob_projectile", "minecraft:spit", "minecraft:thrown", "minecraft:ender_pearl",
				"minecraft:wind_charge", "minecraft:fireball", "minecraft:unattributed_fireball",
				"minecraft:wither_skull", "minecraft:drown");

		// Heat comes from below: the legs stand in it.
		register(regions, Region.LEG, "minecraft:in_fire", "minecraft:on_fire", "minecraft:campfire",
				"minecraft:lava", "minecraft:hot_floor", "minecraft:sulfur_cube_hot",
				"minecraft:sweet_berry_bush");

		// Ground impacts: the feet take the hit.
		register(regions, Region.FOOT, "minecraft:fall", "minecraft:fly_into_wall", "minecraft:stalagmite");

		// Things that land on top of the head.
		register(regions, Region.HEAD, "minecraft:falling_block", "minecraft:falling_anvil",
				"minecraft:falling_stalactite", "minecraft:lightning_bolt");

		// A bee sting on the swatting arm.
		register(regions, Region.ARM, "minecraft:sting");

		return regions;
	}

	private static void register(Map<Integer, Region> regions, Region region, String... identifiers) {
		for (String identifier : identifiers) {
			regions.put(identifier.hashCode(), region);
		}
	}

	/**
	 * The body region a stain of this damage source belongs to. Unknown sources
	 * (modded damage types, datapack renames) fall back to {@link Region#BODY},
	 * which every model can carry.
	 *
	 * @param dir the decoded impact direction; reserved for side biases, the
	 *            region itself is fully determined by the damage source
	 */
	public static Region regionFor(int sourceType, Vec3 dir) {
		return REGIONS.getOrDefault(sourceType, Region.BODY);
	}

	/**
	 * Resolves the region's part on the given model: the region's own part first,
	 * then a fixed fallback chain (ARM/CHEST/HEAD/LEG fall back to BODY, FOOT to
	 * LEG), and finally, for BODY only, the whole model if it consists of exactly
	 * one part. Returns empty when nothing resolves — the caller then draws
	 * nothing rather than parking a stain in mid-air.
	 *
	 * @param outward the wound direction in model space; which arm or leg of a
	 *                pair is chosen follows its x component, which is cosmetic
	 */
	public static Optional<ModelPart> resolve(EntityModel<?> model, Region region, Vec3 outward) {
		if (model == null) {
			return Optional.empty();
		}

		if (model instanceof HumanoidModel<?> humanoid) {
			ModelPart part = switch (region) {
				case HEAD -> humanoid.head;
				case BODY, CHEST -> humanoid.body;
				case ARM -> outward.x >= 0.0 ? humanoid.leftArm : humanoid.rightArm;
				case LEG, FOOT -> outward.x >= 0.0 ? humanoid.leftLeg : humanoid.rightLeg;
			};

			return Optional.of(part);
		}

		ModelPart own = findNamed(model, region, outward);

		if (own != null) {
			return Optional.of(own);
		}

		// Fallback chain: resolve the next region down until BODY answers.
		return switch (region) {
			case ARM, CHEST, HEAD, LEG -> resolve(model, Region.BODY, outward);
			case FOOT -> resolve(model, Region.LEG, outward);
			case BODY -> singlePart(model);
		};
	}

	/**
	 * Carries a direction expressed in the entity's root frame into the part's own
	 * frame, using the part's <em>resting</em> pose — its initial pose, the one its
	 * live animation is applied on top of.
	 *
	 * <p>This is what makes a stain welded: the direction comes back fixed in the
	 * part's frame, so when the part animates, the part's own transform carries
	 * the stain with it. Dividing out the part's <em>live</em> pose instead would
	 * cancel precisely the motion being asked for.
	 *
	 * <p>Returns null when the direction is unusable.
	 */
	public static Vector3f restingDirection(ModelPart part, Vector3f outward) {
		PoseStack poseStack = new PoseStack();
		PartPose live = part.storePose();

		// Emit the part's resting transform by resetting it to its initial pose,
		// asking it for its transform, then putting the live pose back. Nothing is
		// drawn in between, so this does not disturb the model.
		part.resetPose();
		part.translateAndRotate(poseStack);
		part.loadPose(live);

		Vector3f local = new Matrix4f(poseStack.last().pose()).invert()
				.transformDirection(new Vector3f(outward));

		if (!Float.isFinite(local.x()) || !Float.isFinite(local.y()) || !Float.isFinite(local.z())) {
			return null;
		}

		if (local.lengthSquared() < 1.0E-8F) {
			return null;
		}

		return local.normalize();
	}

	/**
	 * Returns the part's own extent along {@code outward}, in the frame the pose
	 * stack currently defines, reading the part's cubes directly rather than
	 * through {@link ModelPart#visit}.
	 *
	 * <p>A caller that has already pushed the part's transform must use this one:
	 * {@code visit} applies that transform itself, so walking the part there would
	 * apply it twice and measure along the wrong direction. This variant pushes
	 * nothing, so the measurement is in exactly the frame the caller's next
	 * {@code translate} will be.
	 *
	 * <p>{@code outward} is in the pose stack's frame. Returns
	 * {@link Float#NEGATIVE_INFINITY} when the part has no cubes, which callers
	 * treat as "skip this stain".
	 */
	public static float ownExtentAlong(PoseStack poseStack, ModelPart part, Vector3f outward) {
		float[] support = {Float.NEGATIVE_INFINITY};

		// `visit` walks the part and its children from a fresh pose, so the pose it
		// hands a cube is that cube's transform down from the part's *parent*, not
		// from the caller: the part's own transform for its own cubes, and the
		// part's transform composed with a child's for that child's. Undoing the
		// part's own transform therefore maps the part's cubes to their raw
		// coordinates and a child's to its offset within the part — the frame
		// `outward` was already reduced into — no matter what the caller pushed
		// above. Using the caller's whole stack here instead would leave the entity
		// and root transforms stranded inside the mapping.
		PoseStack partOnly = new PoseStack();
		part.translateAndRotate(partOnly);
		Matrix4f undoPartFrame = new Matrix4f(partOnly.last().pose()).invert();

		part.visit(new PoseStack(), (pose, name, index, cube) -> {
			Matrix4f toPartFrame = new Matrix4f(undoPartFrame).mul(pose.pose());

			for (int corner = 0; corner < 8; corner++) {
				Vector3f point = new Vector3f(
						(corner & 1) == 0 ? cube.minX : cube.maxX,
						(corner & 2) == 0 ? cube.minY : cube.maxY,
						(corner & 4) == 0 ? cube.minZ : cube.maxZ);
				toPartFrame.transformPosition(point);
				support[0] = Math.max(support[0], point.dot(outward));
			}
		});

		return support[0];
	}

	/** Drops the per-model caches; called when render state is invalidated (resource reload). */
	public static void invalidate() {
		synchronized (ALL_PARTS) {
			ALL_PARTS.clear();
		}
	}

	private static Optional<ModelPart> singlePart(EntityModel<?> model) {
		List<ModelPart> parts = allParts(model);
		return parts.size() == 1 ? Optional.of(parts.get(0)) : Optional.empty();
	}

	/**
	 * Finds the region's own part among the model root's direct children. Model
	 * part names are matched loosely (vanilla names limbs {@code leg0}..{@code
	 * leg7}, {@code leftleg}, {@code paw}, ...), and there is no public way to
	 * enumerate a part's children — {@code ModelPart.getChild} is a name lookup —
	 * so the well-known vanilla spellings are probed.
	 */
	private static ModelPart findNamed(EntityModel<?> model, Region region, Vec3 outward) {
		ModelPart root = model.root();
		boolean left = outward.x >= 0.0;

		switch (region) {
			case BODY:
				return child(root, "body", "torso");
			case CHEST:
				return child(root, "chest", "upperbody");
			case HEAD:
				return child(root, "head");
			case ARM:
				return left ? child(root, "leftarm", "arm") : child(root, "rightarm", "arm");
			case LEG: {
				ModelPart sided = left ? child(root, "leftleg") : child(root, "rightleg");

				if (sided != null) {
					return sided;
				}

				ModelPart plain = child(root, "leg");

				if (plain != null) {
					return plain;
				}

				// Numbered quadruped limbs: leg0..leg7. Which exact limb carries
				// the stain is purely cosmetic; pick a stable one per side.
				for (int i = 0; i < 8; i++) {
					ModelPart leg = child(root, "leg" + i);

					if (leg == null) {
						continue;
					}

					if (!left && i % 2 == 0) {
						return leg;
					}

					if (left && i % 2 == 1) {
						return leg;
					}
				}

				return null;
			}
			case FOOT:
				return child(root, "foot", "paw", "hoof");
		}

		return null;
	}

	private static ModelPart child(ModelPart root, String... names) {
		for (String name : names) {
			if (root.hasChild(name)) {
				return root.getChild(name);
			}
		}

		return null;
	}

	private static List<ModelPart> allParts(EntityModel<?> model) {
		synchronized (ALL_PARTS) {
			return ALL_PARTS.computeIfAbsent(model, m -> List.copyOf(m.allParts()));
		}
	}
}
