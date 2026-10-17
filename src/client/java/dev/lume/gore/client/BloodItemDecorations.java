package dev.lume.gore.client;

import dev.lume.gore.BloodAppearance;
import dev.lume.gore.Gore;
import dev.lume.gore.Silhouette;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Draws blood smears over item icons that carry the blood component.
 *
 * <p>The smear is placed from the icon's own alpha mask, so it lands on the blade of a
 * sword or the limb of a bow rather than floating in the transparent corner of the slot,
 * and it is rotated to lie along the item's long axis so it reads as something the item
 * was dragged through.
 */
public final class BloodItemDecorations {
	private static final float TEXTURE_SIZE = 512.0f;
	private static final float CELL_PIXELS = 64.0f;
	private static final float INSET = 1.0f;

	/**
	 * The most elongated impact cells (atlas cells 9, 10 and 11) — long tapered streaks
	 * rather than the round splats of cells 0 to 8.
	 */
	private static final int SMEAR_FIRST_CELL = 9;
	private static final int SMEAR_CELLS = 3;

	/** Icon masks only depend on the item, so extract each one once instead of per frame. */
	private static final Map<Item, Silhouette> SILHOUETTES = new ConcurrentHashMap<>();

	private BloodItemDecorations() {
	}

	public static void extract(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
		Integer blood = stack.get(Gore.BLOOD);

		if (blood == null || blood <= 0) {
			return;
		}

		long seed = BloodAppearance.seedFor(stack.getItem().hashCode(), 0, 0, 0, 0f, 0f, blood, 0L);
		Random rng = new Random(seed);
		int count = 1 + Math.min(3, blood / 30);
		Optional<Silhouette> shape = silhouetteOf(stack);

		if (shape.isEmpty()) {
			// The icon texture is not readable (missing atlas, exotic model). Fall back to
			// the old slot-uniform placement: it still reads as blood on the item.
			for (int i = 0; i < count; i++) {
				int size = 6 + rng.nextInt(7);
				int maxOffset = Math.max(0, 16 - size);
				int gx = x + (maxOffset == 0 ? 0 : rng.nextInt(maxOffset + 1));
				int gy = y + (maxOffset == 0 ? 0 : rng.nextInt(maxOffset + 1));
				draw(graphics, SMEAR_FIRST_CELL + rng.nextInt(SMEAR_CELLS), gx, gy, size, size, 0.0f, 0xFFFFFFFF);
			}

			return;
		}

		Silhouette mask = shape.get();
		float[] axis = mask.dominantAxis();
		// The streak art is long along the texture's +Y, so this angle lays that long axis
		// on the item's dominant axis.
		float rotation = (float) Math.atan2(-axis[0], axis[1]);
		List<float[]> points = Silhouette.place(mask.alpha(), mask.w(), mask.h(), count, seed);
		int size = 5 + Math.min(9, blood / 14);

		for (int i = 0; i < points.size(); i++) {
			float[] point = points.get(i);
			int gx = Math.round(x + point[0] - size / 2.0f);
			int gy = Math.round(y + point[1] - size / 2.0f);
			int color = BloodAppearance.tint(seed + i * 0x9E3779B97F4A7C15L, 0.0f);
			draw(graphics, SMEAR_FIRST_CELL + rng.nextInt(SMEAR_CELLS), gx, gy, size, size, rotation, color);
		}
	}

	private static Optional<Silhouette> silhouetteOf(ItemStack stack) {
		Silhouette cached = SILHOUETTES.get(stack.getItem());

		if (cached != null) {
			return Optional.of(cached);
		}

		Optional<Silhouette> extracted = ItemSilhouette.from(stack);

		// Only cache hits: a miss usually means the texture atlas was not ready yet, and
		// that must be retried rather than remembered.
		if (extracted.isPresent()) {
			if (SILHOUETTES.size() > 1024) {
				SILHOUETTES.clear();
			}

			SILHOUETTES.put(stack.getItem(), extracted.get());
		}

		return extracted;
	}

	private static void draw(GuiGraphicsExtractor graphics, int cell, int gx, int gy, int w, int h,
			float rotation, int color) {
		int cellX = cell % 8;
		int cellY = cell / 8;
		float u = cellX * CELL_PIXELS + INSET;
		float v = cellY * CELL_PIXELS + INSET;

		if (rotation == 0.0f) {
			blit(graphics, gx, gy, w, h, u, v, color);
			return;
		}

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(gx + w / 2.0f, gy + h / 2.0f);
		pose.rotate(rotation);
		pose.translate(-(gx + w / 2.0f), -(gy + h / 2.0f));
		blit(graphics, gx, gy, w, h, u, v, color);
		pose.popMatrix();
	}

	private static void blit(GuiGraphicsExtractor graphics, int gx, int gy, int w, int h, float u, float v, int color) {
		int cellSpan = (int) (CELL_PIXELS - 2 * INSET);
		graphics.blit(RenderPipelines.GUI_TEXTURED, GoreClient.BLOOD_ATLAS,
				gx, gy, u, v, w, h,
				cellSpan, cellSpan,
				(int) TEXTURE_SIZE, (int) TEXTURE_SIZE, color);
	}
}
