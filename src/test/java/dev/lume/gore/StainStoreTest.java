package dev.lume.gore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests for the per-entity stain budget and its persistence:
 * {@link StainStore} and {@link GoreStain} are data only, so no Minecraft
 * bootstrap is needed.
 */
class StainStoreTest {
	/** A marker source type the later stains never use, so eviction is observable. */
	private static final int FIRST_SOURCE_TYPE = -12345;

	private static final UUID MOB = UUID.fromString("00000000-0000-0000-0000-000000000007");

	private static GoreStain stain(int sourceType) {
		return new GoreStain(sourceType, 0, -127, 0, 0, 20, 0, 100L, 0.3f, 0.0f, 0);
	}

	@Test
	void fillingToTheCapKeepsEveryStain() {
		StainStore store = new StainStore();

		for (int i = 0; i < StainStore.MAX_STAINS; i++) {
			store.add(MOB, stain(i));
		}

		assertEquals(StainStore.MAX_STAINS, store.get(MOB).size());
	}

	@Test
	void oneOverTheCapEvictsTheOldestStain() {
		StainStore store = new StainStore();
		store.add(MOB, stain(FIRST_SOURCE_TYPE));

		for (int i = 1; i <= StainStore.MAX_STAINS; i++) {
			store.add(MOB, stain(i));
		}

		List<GoreStain> stains = store.get(MOB);
		assertEquals(StainStore.MAX_STAINS, stains.size());
		assertTrue(stains.stream().noneMatch(s -> s.sourceType() == FIRST_SOURCE_TYPE),
				"the first-added stain must be evicted once the cap is exceeded");
	}

	@Test
	void getOnAnUnknownIdReturnsAnEmptyListNotNull() {
		StainStore store = new StainStore();

		List<GoreStain> stains = store.get(UUID.randomUUID());
		assertNotNull(stains);
		assertTrue(stains.isEmpty());
	}

	@Test
	void removeForgetsTheEntity() {
		StainStore store = new StainStore();
		store.add(MOB, stain(1));
		store.remove(MOB);

		assertTrue(store.get(MOB).isEmpty());
		assertFalse(store.entries().containsKey(MOB));
	}

	@Test
	void woundsMerge_andKeepTheOlderBirthStamp() {
		StainStore store = new StainStore();
		GoreStain wound = new GoreStain(1, 0, -127, 0, 0, 20, 0, 100L, 0.3f, 40f, 16);
		GoreStain again = new GoreStain(1, 0, -127, 0, 0, 20, 0, 400L, 0.3f, 40f, 16);

		store.add(MOB, wound);
		store.add(MOB, again);

		List<GoreStain> stains = store.get(MOB);
		assertEquals(1, stains.size(), "two hits on one spot are one deepening wound");
		assertEquals(100L, stains.get(0).born(), "a reopened wound keeps its original age");
		assertTrue(stains.get(0).size() > 0.3f);
	}

	@Test
	void spatterNeverMergesIntoTheWound() {
		StainStore store = new StainStore();
		GoreStain wound = new GoreStain(1, 0, -127, 0, 0, 20, 0, 100L, 0.3f, 0f, 16);
		GoreStain spatter = new GoreStain(1, 0, -127, 0, 0, 20, 0, 100L, 0.1f, 0f, 0);

		store.add(MOB, wound);
		store.add(MOB, spatter);

		assertEquals(2, store.get(MOB).size(), "spatter is what makes a hit read as a spray");
	}

	@Test
	void storeSurvivesACodecRoundTrip() {
		StainStore store = new StainStore();
		GoreStain stain = new GoreStain(7, 10, -120, 30, 3, -24, 0, 1234L, 0.4f, 12.5f, 18);
		store.add(MOB, stain);

		var encoded = StainStore.CODEC.encodeStart(JsonOps.INSTANCE, store).getOrThrow();
		StainStore decoded = StainStore.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

		assertEquals(store.get(MOB), decoded.get(MOB),
				"a saved stain must come back with its hit point, age and look intact");
	}

	@Test
	void legacyStainWithoutAHitPointIsDetected() {
		GoreStain legacy = new GoreStain(1, 0, -127, 0, 0, 0, 0, 100L, 0.3f, 0f, 16);

		assertFalse(legacy.hasHit(), "all-zero offsets are the pre-hit-point stamp");
		assertTrue(stain(1).hasHit());
	}
}
