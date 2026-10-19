package dev.lume.gore.client;

import dev.lume.gore.Decal;
import dev.lume.gore.DecalMerge;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
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
		registry.register(Gore.BLOOD_JET, BloodJetParticle.Provider::new);

		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				client.getTextureManager().registerAndLoad(BLOOD_ATLAS, new SimpleTexture(BLOOD_ATLAS)));

		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					DecalManager.addDecals(payload.decals());
					launchFlights(payload);
				}));
		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> DecalManager.sync(payload.dimension(), payload.decals())));
		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsGonePayload.TYPE, (payload, context) ->
				context.client().execute(() -> DecalManager.remove(payload.decals())));
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
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			helper.register(new BodyStainLayer(renderer));
			helper.register(new BodyDamageLayer(renderer));
		});

		// Entity render states are produced during level extraction and only
		// submitted later, so this is the earliest legal point that can write
		// each visible entity's stain list into its state — after extraction has
		// filled it, before submission reads it. No Mixin needed: Fabric fires
		// this at the tail of LevelExtractor.extract.
		LevelExtractionEvents.END_EXTRACTION.register(GoreClient::extractStains);

		// COLLECT_SUBMITS fires at the tail of LevelRenderer.submitFeatures, the
		// point where this frame's geometry is baked. Decals submitted from a
		// draw event instead (AFTER_TRANSLUCENT_TERRAIN) are a frame late, so
		// they crawl across the world as the camera moves and are missing
		// entirely on frames that skip that draw call.
		LevelRenderEvents.COLLECT_SUBMITS.register(DecalRenderer::render);
		ExtractItemDecorationsCallback.EVENT.register(BloodItemDecorations::extract);

		Gore.LOGGER.info("Gore client initialized.");
	}

	/** One visible droplet per stain still in the air, landing as the stain appears. */
	private static void launchFlights(GorePayloads.DecalsPayload payload) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null || payload.flights().isEmpty()) {
			return;
		}

		long now = level.getGameTime();

		for (Decal decal : payload.flights()) {
			if (DecalMerge.isDrip(decal)) {
				continue; // a drip rides on the splat whose droplet already flies there
			}

			Direction face = Direction.from3DDataValue(decal.face());
			Vec3 to = Vec3.atCenterOf(new BlockPos(decal.x(), decal.y(), decal.z()))
					.add(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(0.51))
					.add(Decal.axis1(face).scale(decal.u()))
					.add(Decal.axis2(face).scale(decal.v()));
			BloodDropParticle.fly(level, payload.origin(), to, (int) (decal.born() - now), decal.size());
		}
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

			StateKey key = new StateKey(
					Mth.lerp(partialTick, entity.xOld, entity.getX()),
					Mth.lerp(partialTick, entity.yOld, entity.getY()),
					Mth.lerp(partialTick, entity.zOld, entity.getZ()),
					entity.getType());
			byKey.put(key, stains);
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
