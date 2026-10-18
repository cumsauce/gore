package dev.lume.gore;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent, per-level list of decals with a fixed budget (oldest evicted first). */
public class DecalStore extends SavedData {
	public static final int MAX_DECALS = 1024;

	public static final Codec<DecalStore> CODEC = Decal.CODEC.listOf()
			.xmap(DecalStore::new, store -> List.copyOf(store.decals));

	public static final SavedDataType<DecalStore> TYPE = new SavedDataType<>(
			Gore.id("decals"),
			DecalStore::new,
			CODEC,
			null
	);

	private final List<Decal> decals = new ArrayList<>();

	public DecalStore() {
	}

	private DecalStore(List<Decal> initial) {
		decals.addAll(initial);
	}

	public void add(Decal decal) {
		while (decals.size() >= MAX_DECALS) {
			decals.remove(0);
		}
		decals.add(decal);
		setDirty();
	}

	/**
	 * Adds a decal, coalescing it into an existing neighbour (see
	 * {@link DecalMerge}) when one is on the same face and close enough: the
	 * target is replaced by the single merged decal, so a merge never grows the
	 * list. Returns the decal that was actually stored.
	 *
	 * <p>{@link #add} owns eviction for plain additions, but {@link Bleeding}
	 * now places exclusively through this method, so the same oldest-first
	 * budget guard has to run here too or the store could exceed
	 * {@link #MAX_DECALS} without bound.
	 */
	public Decal addOrMerge(Decal candidate) {
		Decal result = DecalMerge.applyTo(decals, candidate);
		while (decals.size() > MAX_DECALS) {
			decals.remove(0);
		}
		setDirty();
		return result;
	}

	public List<Decal> getAll() {
		return List.copyOf(decals);
	}

	/**
	 * Removes exactly these decals, used by {@link DecalAging} when a stain has
	 * faded out or been rained off. Dirty only when something was removed.
	 */
	public void removeAll(java.util.Collection<Decal> gone) {
		if (decals.removeAll(gone)) {
			setDirty();
		}
	}
}
