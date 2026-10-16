package dev.lume.gore.client;

import dev.lume.gore.Gore;
import dev.lume.gore.GorePayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ExtractItemDecorationsCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.Identifier;

public class GoreClient implements ClientModInitializer {
	public static final Identifier BLOOD_ATLAS = Gore.id("textures/decal/blood_atlas.png");

	@Override
	public void onInitializeClient() {
		ParticleProviderRegistry registry = ParticleProviderRegistry.getInstance();
		registry.register(Gore.BLOOD_MIST, BloodMistParticle.Provider::new);
		registry.register(Gore.BLOOD_DROP, BloodDropParticle.Provider::new);

		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				client.getTextureManager().registerAndLoad(BLOOD_ATLAS, new SimpleTexture(BLOOD_ATLAS)));

		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsPayload.TYPE, (payload, context) ->
				context.client().execute(() -> DecalManager.addDecals(payload.decals())));
		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> DecalManager.sync(payload.dimension(), payload.decals())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> DecalManager.clear());

		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(DecalRenderer::render);
		ExtractItemDecorationsCallback.EVENT.register(BloodItemDecorations::extract);

		Gore.LOGGER.info("Gore client initialized.");
	}
}
