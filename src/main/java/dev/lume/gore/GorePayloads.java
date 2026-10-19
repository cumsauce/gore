package dev.lume.gore;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class GorePayloads {
	private GorePayloads() {
	}

	/**
	 * Incremental decals placed by a hit, sent to nearby clients. {@code flights}
	 * are the decals whose blood is still in the air: each was born in the
	 * future, at the tick its droplet lands, and the client flies a visible
	 * droplet from {@code origin} (the wound) to it over that time, so the stain
	 * appears where and when the droplet hits. Empty for non-flying marks.
	 * {@code gibs} are the smears left by chunks of flesh thrown by a kill: the
	 * client flies a gib from {@code origin} to each, landing on its born tick.
	 */
	public record DecalsPayload(List<Decal> decals, Vec3 origin, List<Decal> flights, List<Decal> gibs)
			implements CustomPacketPayload {
		public DecalsPayload(List<Decal> decals) {
			this(decals, Vec3.ZERO, List.of(), List.of());
		}

		public DecalsPayload(List<Decal> decals, Vec3 origin, List<Decal> flights) {
			this(decals, origin, flights, List.of());
		}

		public static final CustomPacketPayload.Type<DecalsPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("decals"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Never sent. The server registers a receiver for it so a client can ask
	 * {@code ClientPlayNetworking.canSend(HelloPayload.TYPE)}: true only when
	 * the server runs the mod. Without it the client simulates the blood
	 * itself (see {@code ClientBleeding}).
	 */
	public record HelloPayload() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<HelloPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("hello"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static final StreamCodec<ByteBuf, HelloPayload> HELLO_CODEC = StreamCodec.unit(new HelloPayload());

	/** Full decal list of a level, sent on join and on dimension change. */
	public record DecalsSyncPayload(Identifier dimension, List<Decal> decals) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<DecalsSyncPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("decals_sync"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Decals that are gone from the world: they faded out or were rained off.
	 * The server has already removed them from its store; the client drops the
	 * listed ones instead of waiting for the next full sync.
	 */
	public record DecalsGonePayload(List<Decal> decals) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<DecalsGonePayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("decals_gone"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static final StreamCodec<ByteBuf, DecalsPayload> DECALS_CODEC = StreamCodec.composite(
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC), DecalsPayload::decals,
			StreamCodec.composite(ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y,
					ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new), DecalsPayload::origin,
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC), DecalsPayload::flights,
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC), DecalsPayload::gibs,
			DecalsPayload::new);

	public static final StreamCodec<ByteBuf, DecalsGonePayload> GONE_CODEC = StreamCodec.composite(
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC),
			DecalsGonePayload::decals,
			DecalsGonePayload::new);

	public static final StreamCodec<ByteBuf, DecalsSyncPayload> SYNC_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, DecalsSyncPayload::dimension,
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC), DecalsSyncPayload::decals,
			DecalsSyncPayload::new);
}
