package dev.lume.gore;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class GorePayloads {
	private GorePayloads() {
	}

	/** Incremental decals placed by a hit, sent to nearby clients. */
	public record DecalsPayload(List<Decal> decals) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<DecalsPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("decals"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Full decal list of a level, sent on join and on dimension change. */
	public record DecalsSyncPayload(Identifier dimension, List<Decal> decals) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<DecalsSyncPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("decals_sync"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static final StreamCodec<ByteBuf, DecalsPayload> DECALS_CODEC = StreamCodec.composite(
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC),
			DecalsPayload::decals,
			DecalsPayload::new);

	public static final StreamCodec<ByteBuf, DecalsSyncPayload> SYNC_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, DecalsSyncPayload::dimension,
			ByteBufCodecs.<ByteBuf, Decal>list().apply(Decal.STREAM_CODEC), DecalsSyncPayload::decals,
			DecalsSyncPayload::new);
}
