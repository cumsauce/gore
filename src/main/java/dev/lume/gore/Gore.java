package dev.lume.gore;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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

		PayloadTypeRegistry.clientboundPlay().register(GorePayloads.DecalsPayload.TYPE, GorePayloads.DECALS_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GorePayloads.DecalsSyncPayload.TYPE, GorePayloads.SYNC_CODEC);

		Bleeding.init();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Sync.sendTo(handler.player));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> Sync.sendTo(player));
		ServerTickEvents.END_SERVER_TICK.register(Wash::tick);

		LOGGER.info("Gore initialized.");
	}
}
