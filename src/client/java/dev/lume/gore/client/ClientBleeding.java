package dev.lume.gore.client;

import dev.lume.gore.Bleeding;
import dev.lume.gore.Decal;
import dev.lume.gore.DecalMerge;
import dev.lume.gore.Dismemberment;
import dev.lume.gore.Drying;
import dev.lume.gore.GorePayloads;
import dev.lume.gore.GoreStain;
import dev.lume.gore.Limb;
import dev.lume.gore.LimbState;
import dev.lume.gore.StainStore;
import dev.lume.gore.StainWounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * Blood without the server: on a server that does not run the mod, this
 * client simulates every hit it sees with the same code the server would run
 * ({@link Bleeding}, {@link StainWounds}, {@link Dismemberment}), only locally.
 *
 * <p>What degrades: the blood is this player's alone (other players see their
 * own, or none), nothing persists across a relog, gear does not get bloody,
 * damage is inferred from the health the server syncs (so armour and
 * resistances are already counted, but a mob whose health is hidden bleeds at
 * a fixed strength), and severed limbs come back after a relog.
 *
 * <p>A hit arrives as a damage event before the health it took, so each one
 * waits {@link #SETTLE_TICKS} for the new health to land, then bleeds by the
 * difference; a body at zero health dies with the full burst.
 */
public final class ClientBleeding {
	private static final int SETTLE_TICKS = 2;
	/** Severity for a hit whose health drop never shows up. */
	private static final float UNKNOWN_SEVERITY = 0.4f;
	private static final int PRUNE_EVERY = 100;

	private static boolean serverHasGore = true;
	private static final List<Pending> PENDING = new ArrayList<>();
	private static final Map<Integer, LimbState> LIMBS = new HashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();
	/** Body stains, merged and capped by the server's own rules. */
	private static StainStore BODIES = new StainStore();

	private record Pending(int entityId, float healthBefore, DamageSource source, long due) {
	}

	private ClientBleeding() {
	}

	/** Called on join: the server runs the mod if it accepts the hello channel. */
	public static void onJoin() {
		serverHasGore = ClientPlayNetworking.canSend(GorePayloads.HelloPayload.TYPE);
		clear();
	}

	public static boolean active() {
		return !serverHasGore;
	}

	public static void clear() {
		PENDING.clear();
		LIMBS.clear();
		BODIES = new StainStore();
	}

	/** The limbs this client cut off {@code entityId}, when it simulates. */
	public static LimbState limbs(int entityId) {
		return LIMBS.get(entityId);
	}

	/** From the damage-event hook: remember the health before the hit lands. */
	public static void onDamageEvent(LivingEntity entity, DamageSource source) {
		if (!active() || entity instanceof ArmorStand || !(entity.level() instanceof ClientLevel level)) {
			return;
		}
		PENDING.add(new Pending(entity.getId(), entity.getHealth(), source, level.getGameTime() + SETTLE_TICKS));
	}

	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null || !active()) {
			return;
		}

		long now = level.getGameTime();
		for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (now < p.due()) {
				continue;
			}
			it.remove();
			Entity entity = level.getEntity(p.entityId());
			if (entity instanceof LivingEntity living) {
				float taken = p.healthBefore() - living.getHealth();
				boolean dead = living.isDeadOrDying();
				hit(level, living, p.source(), taken, dead);
			}
		}

		if (now % PRUNE_EVERY == 0) {
			prune(now);
		}
		LIMBS.keySet().removeIf(id -> level.getEntity(id) == null);
	}

	private static void hit(ClientLevel level, LivingEntity victim, DamageSource source, float taken, boolean dead) {
		float severity = taken > 0.0f ? Bleeding.severity(taken, victim.getMaxHealth()) : UNKNOWN_SEVERITY;
		Vec3 dir = Bleeding.impactDirection(victim, source);
		Vec3 hit = Bleeding.hitPoint(victim, source, dir);
		Vec3 wound = hit != null
				? victim.position().add(hit)
				: new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * 0.62, victim.getZ()).add(dir.scale(0.25));
		Bleeding.Emitter local = (type, at, v) -> level.addParticle(type, at.x, at.y, at.z, v.x, v.y, v.z);

		Bleeding.sprayParticles(local, wound, dir, severity, RANDOM);
		place(level, wound, Bleeding.collectDecals(level, victim, wound, dir, severity, RANDOM), List.of());

		List<GoreStain> stains = StainWounds.create(source, dir, severity,
				hit == null ? null : Bleeding.inBodyFrame(hit, victim.getYRot()), level.getGameTime(), RANDOM);
		for (GoreStain stain : stains) {
			BODIES.add(victim.getUUID(), stain);
		}
		List<GoreStain> all = BODIES.get(victim.getUUID());
		StainManager.add(victim.getId(), all);
		GoreClient.trackStains(victim.getId(), all);

		LimbState state = LIMBS.getOrDefault(victim.getId(), LimbState.NONE);
		Limb limb = Dismemberment.limbAt(victim, hit);
		if (state.isSevered(limb)) {
			limb = Limb.TORSO;
		}
		state = state.hit(limb, Math.max(0.0f, taken) / Math.max(1.0f, victim.getMaxHealth()));
		boolean bladed = Dismemberment.bladed(source);
		if (dead) {
			Limb killed = Dismemberment.cuts(source) ? Dismemberment.killCut(limb, bladed, RANDOM) : null;
			limb = killed == null || state.isSevered(killed) ? Limb.TORSO : killed;
		}
		boolean cut = Dismemberment.cuts(source) && limb != Limb.TORSO && (dead
				|| !(victim instanceof net.minecraft.world.entity.player.Player)
						&& Dismemberment.shouldSever(limb, state.damage(limb), severity, bladed));
		if (cut) {
			state = state.sever(limb);
			SeveredLimbs.sever(level, victim.getId(), limb, dir);
		}
		LIMBS.put(victim.getId(), state);

		if (dead) {
			Vec3 burst = new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * 0.45, victim.getZ());
			List<Decal> placed = Bleeding.burstDecals(level, victim, burst, RANDOM);
			List<Decal> smears = Bleeding.gibSmears(level, victim, burst, RANDOM);
			place(level, burst, placed, smears);
			Bleeding.burstParticles(local, burst, RANDOM);
		}
	}

	/** Merges the new decals into what this client already shows and flies their droplets and gibs. */
	private static void place(ClientLevel level, Vec3 origin, List<Decal> placed, List<Decal> gibs) {
		if (placed.isEmpty() && gibs.isEmpty()) {
			return;
		}
		List<Decal> shown = new ArrayList<>(DecalManager.snapshot());
		List<Decal> stored = new ArrayList<>(placed.size() + gibs.size());
		List<Decal> all = new ArrayList<>(placed);
		all.addAll(gibs);
		for (Decal decal : all) {
			stored.add(DecalMerge.applyTo(shown, decal));
		}
		long now = level.getGameTime();
		List<Decal> flights = placed.stream().filter(d -> d.born() > now).toList();
		GoreClient.receiveDecals(new GorePayloads.DecalsPayload(stored, origin, flights, gibs));
	}

	/** The server's DecalAging, locally: blood that has faded out is dropped. */
	private static void prune(long now) {
		List<Decal> gone = new ArrayList<>();
		for (Decal decal : DecalManager.snapshot()) {
			if (decal.born() != 0L && Drying.fade(decal.born(), now, Drying.SCALE) <= 0.0f) {
				gone.add(decal);
			}
		}
		if (!gone.isEmpty()) {
			DecalManager.remove(gone);
		}
	}
}
