package dev.lume.gore;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gore implements ModInitializer {
	public static final String MOD_ID = "gore";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final SimpleParticleType BLOOD_MIST = FabricParticleTypes.simple();
	public static final SimpleParticleType BLOOD_DROP = FabricParticleTypes.simple();
	/**
	 * An arterial jet: a short-lived emitter that keeps throwing droplets along
	 * its velocity for a fraction of a second. The server sends one per jet and
	 * each client simulates the spray it produces.
	 */
	public static final SimpleParticleType BLOOD_JET = FabricParticleTypes.simple();
	/**
	 * A chunk of flesh thrown by a kill. Gibs fly to floor smears the server
	 * placed (see {@link GorePayloads.DecalsPayload#gibs}); this type only
	 * registers their sprites with the client.
	 */
	public static final SimpleParticleType BLOOD_GIB = FabricParticleTypes.simple();

	/** Which limbs a creature has lost, saved with it and synced to its trackers. */
	public static final AttachmentType<LimbState> LIMBS = AttachmentRegistry.<LimbState>builder()
			.persistent(LimbState.CODEC)
			.syncWith(LimbState.STREAM_CODEC, AttachmentSyncPredicate.all())
			.buildAndRegister(id("limbs"));

	/** How bloody a piece of gear is, 0..100. Purely cosmetic. */
	public static final DataComponentType<Integer> BLOOD = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			id("blood"),
			DataComponentType.<Integer>builder()
					.persistent(Codec.INT)
					.networkSynchronized(ByteBufCodecs.VAR_INT)
					.build()
	);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, id("blood_mist"), BLOOD_MIST);
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, id("blood_drop"), BLOOD_DROP);
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, id("blood_jet"), BLOOD_JET);
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, id("blood_gib"), BLOOD_GIB);

		PayloadTypeRegistry.clientboundPlay().register(GorePayloads.DecalsPayload.TYPE, GorePayloads.DECALS_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GorePayloads.DecalsSyncPayload.TYPE, GorePayloads.SYNC_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StainPayloads.AddPayload.TYPE, StainPayloads.ADD_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StainPayloads.SyncPayload.TYPE, StainPayloads.SYNC_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GorePayloads.DecalsGonePayload.TYPE, GorePayloads.GONE_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Dismemberment.SeverPayload.TYPE, Dismemberment.SEVER_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(GorePayloads.HelloPayload.TYPE, GorePayloads.HELLO_CODEC);

		// Test-only: behave like a server without the mod, so the client's own
		// simulation (ClientBleeding) is what draws the blood.
		if (Boolean.getBoolean("gore.vanillaServer")) {
			LOGGER.warn("gore.vanillaServer set: server half disabled, clients simulate blood");
			return;
		}

		ServerPlayNetworking.registerGlobalReceiver(GorePayloads.HelloPayload.TYPE, (payload, context) -> {
		});

		Bleeding.init();
		BloodTrail.init();
		StainBinding.init();
		DecalAging.init();

		// Join replay: both the decal map and the body-stain map for the player's level.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Sync.sendTo(handler.player);
			StainSync.sendTo(handler.player);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
			Sync.sendTo(player);
			StainSync.sendTo(player);
		});
		// Body stains now live in per-level SavedData, so a world stop must only
		// drop the in-memory bookkeeping around them.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BloodTrail.clear();
			StainBinding.clear();
			DecalAging.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(Wash::tick);

		LOGGER.info("Gore initialized.");
	}
}
