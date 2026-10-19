package dev.lume.gore.client;

import dev.lume.gore.Decal;
import dev.lume.gore.DecalMerge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.resources.Identifier;

/** Client-side mirror of the server's decal list for the current level. */
public final class DecalManager {
	public static final int MAX_DECALS = 1024;

	private static final Deque<Decal> DECALS = new ArrayDeque<>();
	private static Identifier dimension;
	/** Cached render snapshot; null when the list changed. */
	private static List<Decal> cached;
	/** Bumped whenever the list changes, so the face field knows to re-index. */
	private static int version;

	private DecalManager() {
	}

	/** Adds incrementally placed decals, coalescing into a neighbour when the merged decal from the server arrives. */
	public static void addDecals(List<Decal> decals) {
		for (Decal decal : decals) {
			// The merged decal shares its predecessor's x,y,z,face,u,v, so it
			// matches itself here: remove the target it replaced before adding.
			DecalMerge.findTarget(DECALS, decal).ifPresent(DECALS::remove);

			while (DECALS.size() >= MAX_DECALS) {
				DECALS.pollFirst();
			}

			DECALS.addLast(decal);
		}

		cached = null;
		version++;
	}

	/** Replaces the list with an authoritative snapshot for the given level. */
	public static void sync(Identifier dimensionId, List<Decal> decals) {
		if (dimension == null || !dimension.equals(dimensionId)) {
			DECALS.clear();
			dimension = dimensionId;
		}

		DECALS.clear();
		DECALS.addAll(decals);
		cached = null;
		version++;
	}

	public static void clear() {
		DECALS.clear();
		dimension = null;
		cached = null;
		version++;
	}

	/**
	 * Drops decals the server has retired (faded out or rained off). Removal is
	 * by value: the server names the exact decal, and a decal's fields are its
	 * identity.
	 */
	public static void remove(List<Decal> gone) {
		if (gone == null || gone.isEmpty()) {
			return;
		}

		if (DECALS.removeAll(gone)) {
			cached = null;
		version++;
		}
	}

	/** The decals, oldest first. Cached until the list changes; do not mutate. */
	public static List<Decal> snapshot() {
		if (cached == null) {
			cached = new ArrayList<>(DECALS);
		}

		return cached;
	}

	public static int version() {
		return version;
	}
}
