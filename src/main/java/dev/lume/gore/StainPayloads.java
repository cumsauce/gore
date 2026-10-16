package dev.lume.gore;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class StainPayloads {
	private StainPayloads() {
	}

	/**
	 * The full current stain list of one entity, sent to nearby clients when a hit
	 * lands or a wash clears the entity. An empty list means "this entity is clean",
	 * so this same payload doubles as a removal.
	 */
	public record AddPayload(int entityId, List<GoreStain> stains) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<AddPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("stains_add"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Full stain map of a level, sent on join and on dimension change. */
	public record SyncPayload(Identifier dimension, List<Entry> entries) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<SyncPayload> TYPE = new CustomPacketPayload.Type<>(Gore.id("stains_sync"));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One entity's id and its stain list, the unit a {@link SyncPayload} is built from. */
	public record Entry(int entityId, List<GoreStain> stains) {
		public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Entry::entityId,
				ByteBufCodecs.<ByteBuf, GoreStain>list().apply(GoreStain.STREAM_CODEC), Entry::stains,
				Entry::new);
	}

	public static final StreamCodec<ByteBuf, AddPayload> ADD_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AddPayload::entityId,
			ByteBufCodecs.<ByteBuf, GoreStain>list().apply(GoreStain.STREAM_CODEC), AddPayload::stains,
			AddPayload::new);

	public static final StreamCodec<ByteBuf, SyncPayload> SYNC_CODEC = StreamCodec.composite(
			Identifier.STREAM_CODEC, SyncPayload::dimension,
			ByteBufCodecs.<ByteBuf, Entry>list().apply(Entry.STREAM_CODEC), SyncPayload::entries,
			SyncPayload::new);
}
