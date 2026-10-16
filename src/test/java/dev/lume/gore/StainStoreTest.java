package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests for the per-entity stain budget: {@link StainStore} and
 * {@link GoreStain} are data only, so no Minecraft bootstrap is needed.
 */
class StainStoreTest {
	/** A marker source type the later stains never use, so eviction is observable. */
	private static final int FIRST_SOURCE_TYPE = -12345;

	private static GoreStain stain(int sourceType) {
		return new GoreStain(sourceType, 0, -127, 0, 0.3f, 0.0f, 0);
	}

	@Test
	void fillingToTheCapKeepsEveryStain() {
		StainStore store = new StainStore();

		for (int i = 0; i < StainStore.MAX_STAINS; i++) {
			store.add(7, stain(i));
		}

		assertEquals(StainStore.MAX_STAINS, store.get(7).size());
	}

	@Test
	void oneOverTheCapEvictsTheOldestStain() {
		StainStore store = new StainStore();
		store.add(7, stain(FIRST_SOURCE_TYPE));

		for (int i = 1; i <= StainStore.MAX_STAINS; i++) {
			store.add(7, stain(i));
		}

		List<GoreStain> stains = store.get(7);
		assertEquals(StainStore.MAX_STAINS, stains.size());
		assertTrue(stains.stream().noneMatch(s -> s.sourceType() == FIRST_SOURCE_TYPE),
				"the first-added stain must be evicted once the cap is exceeded");
	}

	@Test
	void getOnAnUnknownIdReturnsAnEmptyListNotNull() {
		StainStore store = new StainStore();

		List<GoreStain> stains = store.get(4242);
		assertNotNull(stains);
		assertTrue(stains.isEmpty());
	}

	@Test
	void removeForgetsTheEntity() {
		StainStore store = new StainStore();
		store.add(9, stain(1));
		store.remove(9);

		assertTrue(store.get(9).isEmpty());
		assertFalse(store.entries().containsKey(9));
	}
}
