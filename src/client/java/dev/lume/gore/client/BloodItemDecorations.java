package dev.lume.gore.client;

import dev.lume.gore.Gore;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Draws blood splats from the decal atlas over item icons that carry the blood component. */
public final class BloodItemDecorations {
	private static final float TEXTURE_SIZE = 256.0f;
	private static final float CELL_PIXELS = 64.0f;
	private static final float INSET = 1.0f;

	private BloodItemDecorations() {
	}

	public static void extract(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
		Integer blood = stack.get(Gore.BLOOD);

		if (blood == null || blood <= 0) {
			return;
		}

		Random rng = new Random(stack.getItem().hashCode() * 31L + x * 17L + y * 71L);
		int splats = 1 + Math.min(3, blood / 30);

		for (int i = 0; i < splats; i++) {
			int cell = rng.nextInt(12);
			int cellX = cell % 4;
			int cellY = cell / 4;
			int size = 6 + rng.nextInt(7);
			int maxOffset = Math.max(0, 16 - size);
			int gx = x + (maxOffset == 0 ? 0 : rng.nextInt(maxOffset + 1));
			int gy = y + (maxOffset == 0 ? 0 : rng.nextInt(maxOffset + 1));
			float u0 = (cellX * CELL_PIXELS + INSET) / TEXTURE_SIZE;
			float u1 = ((cellX + 1) * CELL_PIXELS - INSET) / TEXTURE_SIZE;
			float v0 = (cellY * CELL_PIXELS + INSET) / TEXTURE_SIZE;
			float v1 = ((cellY + 1) * CELL_PIXELS - INSET) / TEXTURE_SIZE;
			graphics.blit(GoreClient.BLOOD_ATLAS, gx, gy, gx + size, gy + size, u0, u1, v0, v1);
		}
	}
}
