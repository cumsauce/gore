package dev.lume.gore.client;

import dev.lume.gore.GoreStain;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/** Render-state keys used by the gore stain feature. */
public final class StainStateKeys {
	private StainStateKeys() {
	}

	/** The entity's current stain list, read by the stain render layer. */
	public static final RenderStateDataKey<List<GoreStain>> STAINS = RenderStateDataKey.create(() -> "gore:stains");
}
