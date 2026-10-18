package dev.lume.gore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A blood stain anchored to a living entity's body.
 *
 * <p>{@code sourceType} identifies what produced the stain; {@code (dirX, dirY,
 * dirZ)} is the impact direction quantised to bytes, which the client decodes
 * back to a unit vector well within a pixel at body scale; {@code (hitX, hitY,
 * hitZ)} is where the blow actually landed, as an offset from the entity's
 * position quantised to 1/32-block steps (all zero on stains from versions
 * before hit points existed, which the client then places by inference as it
 * used to); {@code born} is the game time the stain was made, which drives both
 * drying and the age of the run that crawls from it; {@code size} is the stain
 * scale; {@code rot} is a random roll in degrees; {@code variant} picks a cell
 * in the decal texture atlas (0..11 impact splats, 16..19 wound lacerations).
 */
public record GoreStain(int sourceType, int dirX, int dirY, int dirZ,
		int hitX, int hitY, int hitZ, long born, float size, float rot, int variant) {
	public static final Codec<GoreStain> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("sourceType").forGetter(GoreStain::sourceType),
			Codec.INT.fieldOf("dirX").forGetter(GoreStain::dirX),
			Codec.INT.fieldOf("dirY").forGetter(GoreStain::dirY),
			Codec.INT.fieldOf("dirZ").forGetter(GoreStain::dirZ),
			Codec.INT.optionalFieldOf("hitX", 0).forGetter(GoreStain::hitX),
			Codec.INT.optionalFieldOf("hitY", 0).forGetter(GoreStain::hitY),
			Codec.INT.optionalFieldOf("hitZ", 0).forGetter(GoreStain::hitZ),
			Codec.LONG.optionalFieldOf("born", 0L).forGetter(GoreStain::born),
			Codec.FLOAT.fieldOf("size").forGetter(GoreStain::size),
			Codec.FLOAT.fieldOf("rot").forGetter(GoreStain::rot),
			Codec.INT.fieldOf("variant").forGetter(GoreStain::variant)
	).apply(instance, GoreStain::new));

	private static final StreamCodec<ByteBuf, Integer> BYTE_AS_INT = ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue);

	public static final StreamCodec<ByteBuf, GoreStain> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, GoreStain::sourceType,
			BYTE_AS_INT, GoreStain::dirX,
			BYTE_AS_INT, GoreStain::dirY,
			BYTE_AS_INT, GoreStain::dirZ,
			BYTE_AS_INT, GoreStain::hitX,
			BYTE_AS_INT, GoreStain::hitY,
			BYTE_AS_INT, GoreStain::hitZ,
			ByteBufCodecs.VAR_LONG, GoreStain::born,
			ByteBufCodecs.FLOAT, GoreStain::size,
			ByteBufCodecs.ROTATION_BYTE, GoreStain::rot,
			ByteBufCodecs.VAR_INT, GoreStain::variant,
			GoreStain::new);

	/**
	 * True when this stain carries a real hit point. Stains from versions before
	 * hit points (and the degenerate case of a hit dead-centre on the entity's
	 * origin) carry (0, 0, 0); the client then falls back to placing the wound
	 * by inference from the impact direction, exactly as it always did.
	 */
	public boolean hasHit() {
		return hitX != 0 || hitY != 0 || hitZ != 0;
	}
}
