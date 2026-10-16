package dev.lume.gore.client;

import dev.lume.gore.Gore;
import dev.lume.gore.GorePayloads;
import dev.lume.gore.GoreStain;
import dev.lume.gore.StainPayloads;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ExtractItemDecorationsCallback;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public class GoreClient implements ClientModInitializer {
	public static final Identifier BLOOD_ATLAS = Gore.id("textures/decal/blood_atlas.png");

	/**
	 * Ids of entities the server has reported as bloodied, mirrored from the
	 * payload receivers. Render states carry no entity id, so extraction walks
	 * this set (usually tiny) instead of every entity in the level.
	 */
	private static final Set<Integer> STAINED_ENTITIES = ConcurrentHashMap.newKeySet();

	/** Key of an entity's extracted render state: its interpolated position and type. */
	private record StateKey(double x, double y, double z, EntityType<?> type) {
	}

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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			DecalManager.clear();
			DecalRenderer.clearDrips();
		});

		ClientPlayNetworking.registerGlobalReceiver(StainPayloads.AddPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					StainManager.add(payload.entityId(), payload.stains());
					trackStains(payload.entityId(), payload.stains());
				}));
		ClientPlayNetworking.registerGlobalReceiver(StainPayloads.SyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					StainManager.replace(payload.dimension(), payload.entries());
					STAINED_ENTITIES.clear();

					for (StainPayloads.Entry entry : payload.entries()) {
						trackStains(entry.entityId(), entry.stains());
					}
				}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			StainManager.clear();
			STAINED_ENTITIES.clear();
		});

		// Models (and the render states their data hangs off) are rebuilt on
		// resource reload; drop the per-model part caches so stains re-resolve.
		InvalidateRenderStateCallback.EVENT.register(StainAnchor::invalidate);

		// One registration covers every living entity type.
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) ->
				helper.register(new BodyStainLayer(renderer)));

		// Entity render states are produced during level extraction and only
		// submitted later, so this is the earliest legal point that can write
		// each visible entity's stain list into its state — after extraction has
		// filled it, before submission reads it. No Mixin needed: Fabric fires
		// this at the tail of LevelExtractor.extract.
		LevelExtractionEvents.END_EXTRACTION.register(GoreClient::extractStains);

		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(DecalRenderer::render);
		ExtractItemDecorationsCallback.EVENT.register(BloodItemDecorations::extract);

		Gore.LOGGER.info("Gore client initialized.");
	}

	private static void trackStains(int entityId, List<GoreStain> stains) {
		if (stains.isEmpty()) {
			STAINED_ENTITIES.remove(entityId);
		} else {
			STAINED_ENTITIES.add(entityId);
		}
	}

	/**
	 * Copies each bloodied, visible entity's stain list from {@link StainManager}
	 * into its freshly extracted render state, where {@link BodyStainLayer} reads
	 * it during submission.
	 *
	 * <p>An {@code EntityRenderState} carries no entity id, so the entity is
	 * matched to its state by the interpolated position and type — computed with
	 * the exact formula {@code EntityRenderer.extractRenderState} uses, with the
	 * same per-entity partial tick, so the keys match bit for bit.
	 */
	private static void extractStains(LevelExtractionContext context) {
		if (STAINED_ENTITIES.isEmpty()) {
			return;
		}

		ClientLevel level = context.level();

		if (level == null) {
			return;
		}

		TickRateManager tickRate = level.tickRateManager();
		Map<StateKey, List<GoreStain>> byKey = null;

		for (int entityId : STAINED_ENTITIES) {
			Entity entity = level.getEntity(entityId);

			if (entity == null) {
				continue;
			}

			List<GoreStain> stains = StainManager.snapshot(entityId);

			if (stains.isEmpty()) {
				STAINED_ENTITIES.remove(entityId);
				continue;
			}

			// Mirrors LevelExtractor: partial tick, then Mth.lerp over the old
			// and current positions.
			float partialTick = context.deltaTracker()
					.getGameTimeDeltaPartialTick(!tickRate.isEntityFrozen(entity));

			if (byKey == null) {
				byKey = new HashMap<>();
			}

			byKey.put(new StateKey(
					Mth.lerp(partialTick, entity.xOld, entity.getX()),
					Mth.lerp(partialTick, entity.yOld, entity.getY()),
					Mth.lerp(partialTick, entity.zOld, entity.getZ()),
					entity.getType()), stains);
		}

		if (byKey == null) {
			return;
		}

		for (EntityRenderState state : context.levelState().entityRenderStates) {
			List<GoreStain> stains = byKey.get(new StateKey(state.x, state.y, state.z, state.entityType));

			if (stains != null) {
				// Fabric injects FabricRenderState into EntityRenderState at
				// runtime; the cast is the standard bridge, no Mixin of ours.
				((FabricRenderState) state).setData(StainStateKeys.STAINS, stains);
			}
		}
	}
}
