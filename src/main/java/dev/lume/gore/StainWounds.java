package dev.lume.gore;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a damage source plus the already-computed impact direction into body
 * stains. The decision of how many, how big and which cell lives here so
 * {@link Bleeding} does not grow another branch. Size follows the shape of the
 * wall-decal roll so a body stain reads at the same scale as a wall splat.
 *
 * <p>A hit leaves more than one mark. The gash itself is a wound cell (atlas
 * 16–19); around it the blow throws spatter (cells 0–11) that lands on the same
 * face of the same body part. That is what turns a single red dot into a body
 * that looks hit: the client scatters each spatter stain over its own stable
 * point of the face (see {@code BodyStainLayer}), so the marks read as a spray
 * rather than as one blob.
 */
public final class StainWounds {
	/** Atlas cells 16..19 are wound lacerations; cells 0..11 are impact splats. */
	private static final int WOUND_CELLS = 4;
	private static final int WOUND_BASE = 16;
	/** Hard ceiling on a wound's size. */
	private static final float MAX_SIZE = 0.55f;
	/** The wound is always the same physical cut; severity barely changes it. */
	private static final float WOUND_BASE_SIZE = 0.30f;
	/**
	 * Spatter is smaller than the wound: the gash has to stay the mark the eye
	 * reads first, with the flecks around it as texture rather than as a second
	 * lump the same size.
	 */
	private static final float SPATTER_MIN = 0.08f;
	private static final float SPATTER_MAX = 0.18f;

	/**
	 * Quantisation step of the hit-point bytes, in blocks: 1/32 block is well
	 * under a pixel at body scale while keeping the offset inside one signed
	 * byte (±4 blocks).
	 */
	private static final double HIT_STEP = 1.0 / 32.0;

	private StainWounds() {
	}

	/**
	 * Creates the stains for a single hit: the wound first, then the spatter it
	 * threw. {@code severity} is a 0..1 wound strength that scales both the size
	 * roll and how much spatter there is; the random rolls happen in a fixed
	 * order so a given stream reproduces the same stains.
	 *
	 * <p>{@code hit} is where the blow landed relative to the victim's position
	 * (world axes, blocks) or {@code null} when nothing better than the old
	 * inference is known. {@code born} is the game time of the hit, which the
	 * client uses to dry the stain and to age the run that crawls from it.
	 */
	public static List<GoreStain> create(DamageSource source, Vec3 impactDirection, float severity,
			Vec3 hit, long born, RandomSource random) {
		// The caller's direction is only unit-ish (Bleeding's last-resort fallback
		// is (0, -0.6, 0)), so normalise it: each byte then holds one axis of a
		// true unit vector the client can decode back without skew.
		Vec3 dir = impactDirection.normalize();
		int type = sourceType(source);

		List<GoreStain> stains = new ArrayList<>(4);
		stains.add(new GoreStain(
				type,
				dirByte(dir.x),
				dirByte(dir.y),
				dirByte(dir.z),
				hitByte(hit == null ? 0.0 : hit.x),
				hitByte(hit == null ? 0.0 : hit.y),
				hitByte(hit == null ? 0.0 : hit.z),
				born,
				Math.min(MAX_SIZE, WOUND_BASE_SIZE + 0.16f * severity + 0.08f * random.nextFloat()),
				random.nextFloat() * 360.0f,
				// Body stains are wound lacerations (cells 16..19), not impact
				// splats: a dark gash with raised edges, which grows a body drip
				// downward (see BodyStainLayer).
				WOUND_BASE + random.nextInt(WOUND_CELLS)));

		// A glancing blow throws two or three flecks; a heavy one throws five.
		// Each gets its own direction — a small cone around the impact — so the
		// client's region lookup can still place it on the same part while the
		// spatter lands around the wound rather than under it. The flecks carry
		// the wound's hit point: they landed near where the blow did.
		int spatter = Math.round(1.0f + severity * 4.0f);
		for (int i = 0; i < spatter; i++) {
			Vec3 fleck = jitterDirection(dir, severity, random);
			float size = SPATTER_MIN + random.nextFloat() * (SPATTER_MAX - SPATTER_MIN);
			// Impact cells: elongation band 0–3, density band 0 for a single blob.
			int variant = random.nextInt(4) * 3;
			stains.add(new GoreStain(
					type,
					dirByte(fleck.x),
					dirByte(fleck.y),
					dirByte(fleck.z),
					hitByte(hit == null ? 0.0 : hit.x),
					hitByte(hit == null ? 0.0 : hit.y),
					hitByte(hit == null ? 0.0 : hit.z),
					born,
					size,
					random.nextFloat() * 360.0f,
					variant));
		}

		return stains;
	}

	/** Quantise one component of the hit offset to the byte the codec writes. */
	private static int hitByte(double component) {
		return Mth.clamp((int) Math.round(component / HIT_STEP), Byte.MIN_VALUE, Byte.MAX_VALUE);
	}

	/**
	 * A direction a few degrees off {@code dir}: the impact throws a narrow cone.
	 * Spread grows with severity, because a harder blow breaks the jet up more.
	 */
	private static Vec3 jitterDirection(Vec3 dir, float severity, RandomSource random) {
		Vec3 helper = Math.abs(dir.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
		Vec3 u = dir.cross(helper).normalize();
		Vec3 v = dir.cross(u).normalize();
		double angle = random.nextDouble() * Math.PI * 2.0;
		double spread = 0.18 + 0.42 * severity;
		return dir.add(u.scale(Math.cos(angle) * spread))
				.add(v.scale(Math.sin(angle) * spread))
				.normalize();
	}

	/**
	 * The integer the client's region table will switch on: the damage type's
	 * registry identifier (e.g. {@code minecraft:arrow}) hashed with
	 * {@link String#hashCode()}.
	 *
	 * <p>Not a registry integer id, deliberately. Damage types live in the
	 * world's dynamic registry — there is no {@code BuiltInRegistries.DAMAGE_TYPE}
	 * — and a bare {@link DamageSource} exposes no {@code RegistryAccess} to
	 * look one up from here. Dynamic-registry ids are also assigned while each
	 * side loads its own datapacks, so the same damage type need not share an
	 * int between server and client. The identifier string, by contrast, is
	 * identical on both sides for the same damage type, and
	 * {@link String#hashCode()} is fixed by the Java specification, so this
	 * value is identical on server and client and stable for the session.
	 */
	private static int sourceType(DamageSource source) {
		return source.typeHolder().unwrapKey()
				.map(key -> key.identifier().toString().hashCode())
				// Damage sources always hold a real registry entry, so the key is
				// present in practice; msgId is a deterministic last resort rather
				// than a crash if a tag-backed holder ever appears.
				.orElseGet(() -> source.type().msgId().hashCode());
	}

	/** Quantise one component of the normalised direction to the byte {@link GoreStain#STREAM_CODEC} writes. */
	private static int dirByte(double component) {
		// A unit component already sits within [-1, 1], so the product fits a
		// byte; the clamp guarantees it even for a malformed input, since the
		// codec's byte write would otherwise truncate silently.
		return Mth.clamp((int) Math.round(component * 127.0), Byte.MIN_VALUE, Byte.MAX_VALUE);
	}
}
