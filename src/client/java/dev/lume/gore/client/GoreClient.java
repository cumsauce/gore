package dev.lume.gore.client;

import dev.lume.gore.Decal;
import dev.lume.gore.Dismemberment;
import dev.lume.gore.DecalMerge;
import dev.lume.gore.Gore;
import dev.lume.gore.GorePayloads;
import dev.lume.gore.GoreStain;
import dev.lume.gore.Limb;
import dev.lume.gore.LimbState;
import dev.lume.gore.StainPayloads;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
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
		registry.register(Gore.BLOOD_GIB, BloodGibParticle.Provider::new);

		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				client.getTextureManager().registerAndLoad(BLOOD_ATLAS, new SimpleTexture(BLOOD_ATLAS)));

		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsPayload.TYPE, (payload, context) ->
				context.client().execute(() -> receiveDecals(payload)));
		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsSyncPayload.TYPE, (payload, context) ->
				context.client().execute(() -> DecalManager.sync(payload.dimension(), payload.decals())));
		ClientPlayNetworking.registerGlobalReceiver(GorePayloads.DecalsGonePayload.TYPE, (payload, context) ->
				context.client().execute(() -> DecalManager.remove(payload.decals())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			DecalManager.clear();
			DecalRenderer.clearDrips();
			SeveredLimbs.clear();
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
		InvalidateRenderStateCallback.EVENT.register(LimbRender::invalidate);

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
		LevelRenderEvents.COLLECT_SUBMITS.register(SeveredLimbs::render);
		ClientTickEvents.END_CLIENT_TICK.register(SeveredLimbs::tick);
		ClientTickEvents.END_CLIENT_TICK.register(ClientBleeding::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientBleeding.onJoin());
		ClientPlayNetworking.registerGlobalReceiver(Dismemberment.SeverPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					if (context.client().level != null) {
						SeveredLimbs.sever(context.client().level, payload.entityId(),
								Limb.values()[Math.floorMod(payload.limb(), Limb.values().length)], payload.dir());
					}
				}));
		ExtractItemDecorationsCallback.EVENT.register(BloodItemDecorations::extract);

		Gore.LOGGER.info("Gore client initialized.");
	}

	/** A batch of new decals, from the server or from {@link ClientBleeding}. */
	static void receiveDecals(GorePayloads.DecalsPayload payload) {
		DecalManager.addDecals(payload.decals());
		launchFlights(payload);
	}

	/** One visible droplet per stain still in the air, landing as the stain appears. */
	private static void launchFlights(GorePayloads.DecalsPayload payload) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level == null) {
			return;
		}

		long now = level.getGameTime();

		for (Decal smear : payload.gibs()) {
			BloodGibParticle.fly(level, payload.origin(), smear, (int) (smear.born() - now));
		}

		for (Decal decal : payload.flights()) {
			if (DecalMerge.isDrip(decal)) {
				continue; // a drip rides on the splat whose droplet already flies there
			}

			Vec3 to = Decal.surfacePoint(decal);
			BloodDropParticle.fly(level, payload.origin(), to, (int) (decal.born() - now), decal.size());
		}
	}

	static void trackStains(int entityId, List<GoreStain> stains) {
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
		ClientLevel level = context.level();

		if (level == null) {
			return;
		}

		TickRateManager tickRate = level.tickRateManager();
		Map<StateKey, BodyData> byKey = null;
		long now = level.getGameTime();

		for (Entity entity : level.entitiesForRendering()) {
			boolean stained = STAINED_ENTITIES.contains(entity.getId());
			LimbState limbs = entity.getAttached(Gore.LIMBS);
			if (limbs == null && ClientBleeding.active()) {
				limbs = ClientBleeding.limbs(entity.getId());
			}
			int severed = limbs == null ? 0 : limbs.severed();

			if (!stained && severed == 0) {
				continue;
			}

			List<GoreStain> stains = stained ? StainManager.snapshot(entity.getId()) : List.of();

			if (stained && stains.isEmpty()) {
				STAINED_ENTITIES.remove(entity.getId());
				if (severed == 0) {
					continue;
				}
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
			byKey.put(key, new BodyData(stains, severed, BodyDamageLayer.limbCoat(stains, entity, now)));
		}

		if (byKey == null) {
			return;
		}

		for (EntityRenderState state : context.levelState().entityRenderStates) {
			BodyData data = byKey.get(new StateKey(state.x, state.y, state.z, state.entityType));

			if (data == null) {
				continue;
			}

			// Fabric injects FabricRenderState into EntityRenderState at
			// runtime; the cast is the standard bridge, no Mixin of ours.
			FabricRenderState frs = (FabricRenderState) state;
			if (!data.stains().isEmpty()) {
				frs.setData(StainStateKeys.STAINS, data.stains());
			}
			frs.setData(LimbRender.COAT, data.coat());
			if (data.severed() != 0) {
				frs.setData(LimbRender.SEVERED, data.severed());
				// A cut-off arm drops what it held.
				if (state instanceof ArmedEntityRenderState armed) {
					if ((data.severed() & Limb.RIGHT_ARM.bit()) != 0) {
						armed.rightHandItemState.clear();
					}
					if ((data.severed() & Limb.LEFT_ARM.bit()) != 0) {
						armed.leftHandItemState.clear();
					}
				}
			}
		}
	}

	private record BodyData(List<GoreStain> stains, int severed, float[] coat) {
	}
}
