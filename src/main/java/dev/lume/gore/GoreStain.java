package dev.lume.gore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A blood stain anchored to a living entity's body. {@code sourceType}
 * identifies what produced the stain; {@code (dirX, dirY, dirZ)} is the impact
 * direction quantised to bytes, which the client decodes back to a unit vector
 * well within a pixel at body scale; {@code size} is the stain scale;
 * {@code rot} is a random roll in degrees; {@code variant} picks an impact cell
 * in the decal texture atlas and is bounded to 0..11 because body stains use
 * impact cells only.
 */
public record GoreStain(int sourceType, int dirX, int dirY, int dirZ, float size, float rot, int variant) {
	public static final Codec<GoreStain> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("sourceType").forGetter(GoreStain::sourceType),
			Codec.INT.fieldOf("dirX").forGetter(GoreStain::dirX),
			Codec.INT.fieldOf("dirY").forGetter(GoreStain::dirY),
			Codec.INT.fieldOf("dirZ").forGetter(GoreStain::dirZ),
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
			ByteBufCodecs.FLOAT, GoreStain::size,
			ByteBufCodecs.ROTATION_BYTE, GoreStain::rot,
			ByteBufCodecs.VAR_INT, GoreStain::variant,
			GoreStain::new);
}
