package dev.lume.gore;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a damage source plus the already-computed impact direction into a body
 * stain record. The decision of how big and which cell lives here so
 * {@link Bleeding} does not grow another branch. Size follows the shape of the
 * wall-decal roll so a body stain reads at the same scale as a wall splat.
 */
public final class StainWounds {
	/** Atlas cells 16..19 are wound lacerations (M5); cells 0..11 are impact splats. */
	private static final int WOUND_CELLS = 4;
	private static final int WOUND_BASE = 16;
	/** Hard ceiling on stain size. */
	private static final float MAX_SIZE = 0.42f;

	private StainWounds() {
	}

	/**
	 * Creates the stain for a single hit. {@code severity} is a 0..1 wound
	 * strength that scales the size roll; the random rolls happen in a fixed
	 * order (size, then rotation, then variant) so a given stream reproduces
	 * the same stain.
	 */
	public static GoreStain create(DamageSource source, Vec3 impactDirection, float severity, RandomSource random) {
		// The caller's direction is only unit-ish (Bleeding's last-resort fallback
		// is (0, -0.6, 0)), so normalise it: each byte then holds one axis of a
		// true unit vector the client can decode back without skew.
		Vec3 dir = impactDirection.normalize();

		return new GoreStain(
				sourceType(source),
				dirByte(dir.x),
				dirByte(dir.y),
				dirByte(dir.z),
				Math.min(MAX_SIZE, 0.22f + 0.18f * random.nextFloat() + severity * 0.10f),
				random.nextFloat() * 360.0f,
				// M5: body stains are wound lacerations (cells 16..19), not
				// impact splats. The wound cell is a dark gash with raised edges,
				// and a body drip grows downward from it (see BodyStainLayer).
				WOUND_BASE + random.nextInt(WOUND_CELLS));
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
