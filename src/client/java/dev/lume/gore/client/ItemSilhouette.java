package dev.lume.gore.client;

import dev.lume.gore.Silhouette;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Turns an {@link ItemStack} into the {@link Silhouette} of the icon it renders as.
 *
 * <p>Resolving the stack through the item model resolver is what makes this independent of
 * the item's type: a sword, a bow and a modded trinket all report the sprite they actually
 * draw, so blood can be kept on that shape instead of on the whole 16x16 icon slot.
 *
 * <p>Every failure path returns {@link Optional#empty()} rather than throwing: the caller
 * falls back to the old uniform placement, which is cosmetic and must never break a
 * tooltip.
 */
public final class ItemSilhouette {
	/** Guards against a pathological sprite size; real item icons are 16x16. */
	private static final int MAX_DIMENSION = 512;

	private ItemSilhouette() {
	}

	public static Optional<Silhouette> from(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return Optional.empty();
		}

		try {
			Minecraft minecraft = Minecraft.getInstance();
			net.minecraft.world.entity.Entity owner = minecraft.player;

			if (owner == null) {
				return Optional.empty();
			}

			ItemStackRenderState state = new ItemStackRenderState();
			minecraft.getItemModelResolver().updateForNonLiving(state, stack, ItemDisplayContext.GUI, owner);

			if (state.isEmpty()) {
				return Optional.empty();
			}

			Material.Baked material = state.pickParticleMaterial(RandomSource.create(0L));

			if (material == null) {
				return Optional.empty();
			}

			TextureAtlasSprite sprite = material.sprite();

			if (sprite == null) {
				return Optional.empty();
			}

			SpriteContents contents = sprite.contents();
			int width = contents.width();
			int height = contents.height();

			if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
				return Optional.empty();
			}

			// isTransparent(frame, x, y) is SpriteContents' public per-pixel accessor; it reads
			// the frame-0 NativeImage and reports alpha == 0. The mask is therefore binary,
			// which is what an icon silhouette needs: the job is keeping blood off the
			// transparent corners, not antialiasing the smear.
			byte[] alpha = new byte[width * height];

			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					alpha[y * width + x] = contents.isTransparent(0, x, y) ? 0 : (byte) 255;
				}
			}

			return Optional.of(new Silhouette(alpha, width, height));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
