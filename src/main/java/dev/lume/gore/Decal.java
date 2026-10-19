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
 *
 * <p>{@code born} is when the decal first shows on the wall. {@code fed} is
 * when the latest merged supply lands on it ({@code 0}: none): a drip that a
 * droplet merges into stays visible while that droplet flies, and its run
 * restarts from the tick the droplet arrives.
 */
public record Decal(int x, int y, int z, int face, float u, float v, float size, float rot, int variant, long born,
		long fed) {
	public static final Codec<Decal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("x").forGetter(Decal::x),
			Codec.INT.fieldOf("y").forGetter(Decal::y),
			Codec.INT.fieldOf("z").forGetter(Decal::z),
			Codec.INT.fieldOf("face").forGetter(Decal::face),
			Codec.FLOAT.fieldOf("u").forGetter(Decal::u),
			Codec.FLOAT.fieldOf("v").forGetter(Decal::v),
			Codec.FLOAT.fieldOf("size").forGetter(Decal::size),
			Codec.FLOAT.fieldOf("rot").forGetter(Decal::rot),
			Codec.INT.fieldOf("variant").forGetter(Decal::variant),
			Codec.LONG.optionalFieldOf("born", 0L).forGetter(Decal::born),
			Codec.LONG.optionalFieldOf("fed", 0L).forGetter(Decal::fed)
	).apply(instance, Decal::new));

	private static final StreamCodec<ByteBuf, Integer> BYTE_AS_INT = ByteBufCodecs.BYTE.map(Byte::intValue, Integer::byteValue);

	public static final StreamCodec<ByteBuf, Decal> STREAM_CODEC = StreamCodec.of(
			(buf, d) -> {
				ByteBufCodecs.VAR_INT.encode(buf, d.x());
				ByteBufCodecs.VAR_INT.encode(buf, d.y());
				ByteBufCodecs.VAR_INT.encode(buf, d.z());
				BYTE_AS_INT.encode(buf, d.face());
				ByteBufCodecs.FLOAT.encode(buf, d.u());
				ByteBufCodecs.FLOAT.encode(buf, d.v());
				ByteBufCodecs.FLOAT.encode(buf, d.size());
				ByteBufCodecs.ROTATION_BYTE.encode(buf, d.rot());
				ByteBufCodecs.VAR_INT.encode(buf, d.variant());
				ByteBufCodecs.VAR_LONG.encode(buf, d.born());
				ByteBufCodecs.VAR_LONG.encode(buf, d.fed());
			},
			buf -> new Decal(
					ByteBufCodecs.VAR_INT.decode(buf),
					ByteBufCodecs.VAR_INT.decode(buf),
					ByteBufCodecs.VAR_INT.decode(buf),
					BYTE_AS_INT.decode(buf),
					ByteBufCodecs.FLOAT.decode(buf),
					ByteBufCodecs.FLOAT.decode(buf),
					ByteBufCodecs.FLOAT.decode(buf),
					ByteBufCodecs.ROTATION_BYTE.decode(buf),
					ByteBufCodecs.VAR_INT.decode(buf),
					ByteBufCodecs.VAR_LONG.decode(buf),
					ByteBufCodecs.VAR_LONG.decode(buf)));

	/** A decal with no merged supply in flight. */
	public Decal(int x, int y, int z, int face, float u, float v, float size, float rot, int variant, long born) {
		this(x, y, z, face, u, v, size, rot, variant, born, 0L);
	}

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

	/**
	 * The world point where this decal's droplet lands: the hit point on the
	 * face, lifted a hair off it along the normal.
	 */
	public static Vec3 surfacePoint(Decal d) {
		Direction face = Direction.from3DDataValue(d.face());
		return new Vec3(d.x() + 0.5, d.y() + 0.5, d.z() + 0.5)
				.add(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(0.51))
				.add(axis1(face).scale(d.u()))
				.add(axis2(face).scale(d.v()));
	}
}
