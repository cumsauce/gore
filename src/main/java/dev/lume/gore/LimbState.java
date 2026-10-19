package dev.lume.gore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a creature's body has been through, part by part: the damage each
 * severable limb has taken (as a fraction of max health), which limbs are gone,
 * and which part the last blow struck (so the killing blow knows what to cut).
 *
 * <p>Attached to the entity ({@link Gore#LIMBS}), saved with it and synced to
 * every client tracking it, so a creature that lost an arm still has one arm
 * after a reload or when another player walks up.
 */
public record LimbState(int severed, List<Float> damage, int lastHit) {
	public static final LimbState NONE = new LimbState(0, zeros(), Limb.TORSO.ordinal());

	public static final Codec<LimbState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("severed", 0).forGetter(LimbState::severed),
			Codec.FLOAT.listOf().optionalFieldOf("damage", zeros()).forGetter(LimbState::damage),
			Codec.INT.optionalFieldOf("lastHit", Limb.TORSO.ordinal()).forGetter(LimbState::lastHit)
	).apply(instance, LimbState::new));

	/** Clients only draw what is gone; the damage stays on the server. */
	public static final StreamCodec<ByteBuf, LimbState> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(
			severed -> new LimbState(severed, zeros(), Limb.TORSO.ordinal()), LimbState::severed);

	public LimbState {
		damage = List.copyOf(damage.size() == Limb.SEVERABLE ? damage : zeros());
	}

	private static List<Float> zeros() {
		List<Float> out = new ArrayList<>(Limb.SEVERABLE);
		for (int i = 0; i < Limb.SEVERABLE; i++) {
			out.add(0.0f);
		}
		return List.copyOf(out);
	}

	public boolean isSevered(Limb limb) {
		return (severed & limb.bit()) != 0;
	}

	public float damage(Limb limb) {
		return limb == Limb.TORSO ? 0.0f : damage.get(limb.ordinal());
	}

	/** This state after a blow of {@code fraction} of max health on {@code limb}. */
	public LimbState hit(Limb limb, float fraction) {
		if (limb == Limb.TORSO) {
			return new LimbState(severed, damage, limb.ordinal());
		}
		List<Float> next = new ArrayList<>(damage);
		next.set(limb.ordinal(), damage.get(limb.ordinal()) + fraction);
		return new LimbState(severed, next, limb.ordinal());
	}

	public LimbState sever(Limb limb) {
		return new LimbState(severed | limb.bit(), damage, lastHit);
	}

	public Limb lastLimb() {
		Limb[] all = Limb.values();
		return lastHit >= 0 && lastHit < all.length ? all[lastHit] : Limb.TORSO;
	}
}
