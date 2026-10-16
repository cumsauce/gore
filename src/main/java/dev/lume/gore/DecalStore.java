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

	public List<Decal> getAll() {
		return List.copyOf(decals);
	}
}
