package dev.lume.gore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * A blood splatter stuck to a block face. {@code (x, y, z)} is the block;
 * {@code u}/{@code v} are the hit offset from the block center along the face's
 * tangent axes in block units (-0.5..0.5); {@code variant} picks a cell in the
 * decal texture atlas; {@code rot} is a random roll in degrees.
 */
public record Decal(int x, int y, int z, int face, float u, float v, float size, float rot, int variant) {
	public static final Codec<Decal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("x").forGetter(Decal::x),
			Codec.INT.fieldOf("y").forGetter(Decal::y),
			Codec.INT.fieldOf("z").forGetter(Decal::z),
			Codec.INT.fieldOf("face").forGetter(Decal::face),
			Codec.FLOAT.fieldOf("u").forGetter(Decal::u),
			Codec.FLOAT.fieldOf("v").forGetter(Decal::v),
			Codec.FLOAT.fieldOf("size").forGetter(Decal::size),
			Codec.FLOAT.fieldOf("rot").forGetter(Decal::rot),
			Codec.INT.fieldOf("variant").forGetter(Decal::variant)
	).apply(instance, Decal::new));

	private static final StreamCodec<ByteBuf, Integer> BYTE_AS_INT = ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue);

	public static final StreamCodec<ByteBuf, Decal> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Decal::x,
			ByteBufCodecs.VAR_INT, Decal::y,
			ByteBufCodecs.VAR_INT, Decal::z,
			BYTE_AS_INT, Decal::face,
			ByteBufCodecs.FLOAT, Decal::u,
			ByteBufCodecs.FLOAT, Decal::v,
			ByteBufCodecs.FLOAT, Decal::size,
			ByteBufCodecs.ROTATION_BYTE, Decal::rot,
			ByteBufCodecs.VAR_INT, Decal::variant,
			Decal::new);

	/** First tangent axis of the face, so that {@code axis1 x axis2 == face normal}. */
	public static Vec3 axis1(Direction face) {
		return switch (face) {
			case DOWN -> new Vec3(1.0, 0.0, 0.0);
			case UP -> new Vec3(1.0, 0.0, 0.0);
			case NORTH -> new Vec3(-1.0, 0.0, 0.0);
			case SOUTH -> new Vec3(1.0, 0.0, 0.0);
			case WEST -> new Vec3(0.0, 0.0, 1.0);
			case EAST -> new Vec3(0.0, 1.0, 0.0);
		};
	}

	/** Second tangent axis of the face. */
	public static Vec3 axis2(Direction face) {
		return switch (face) {
			case DOWN -> new Vec3(0.0, 0.0, 1.0);
			case UP -> new Vec3(0.0, 0.0, -1.0);
			case NORTH -> new Vec3(0.0, 1.0, 0.0);
			case SOUTH -> new Vec3(0.0, 1.0, 0.0);
			case WEST -> new Vec3(0.0, 1.0, 0.0);
			case EAST -> new Vec3(0.0, 0.0, 1.0);
		};
	}
}
