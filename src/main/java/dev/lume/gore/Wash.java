package dev.lume.gore;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Rinses blood off while it is being rained on or soaked.
 *
 * <p>Gear washes as soon as its owner is in water, as it always did. Bodies now
 * wash too: a creature in water loses its wounds outright, and a player caught
 * in the rain loses them slowly (rain does not strip mobs, which would let a
 * thunderstorm erase a fight). Washing is permanent — the stains are removed
 * from the store, not faded — because water takes blood away rather than
 * drying it.
 */
public final class Wash {
	private Wash() {
	}

	public static void tick(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			washBodies(level);
		}

		for (var player : server.getPlayerList().getPlayers()) {
			if (player.isInWater()) {
				washGear(player);
			}
		}
	}

	private static void washBodies(ServerLevel level) {
		StainStore store = StainStore.of(level);

		if (store.isEmpty()) {
			return;
		}

		RandomSource random = level.getRandom();

		for (UUID id : store.ids()) {
			var entity = level.getEntity(id);

			if (!(entity instanceof LivingEntity living) || store.get(id).isEmpty()) {
				continue;
			}

			// Rain wears a player's wounds down slowly; a body of water takes
			// them at once. Mobs do not wash in rain.
			boolean soaked = living.isInWater()
					|| (living instanceof Player && living.isInWaterOrRain() && random.nextFloat() < 0.05f);

			if (!soaked) {
				continue;
			}

			store.remove(id);
			BloodTrail.forget(level, id);
			StainSync.sendRemove(level, living.getId(), living.position());
		}
	}

	private static void washGear(Player player) {
		Inventory inventory = player.getInventory();

		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			clean(stack);
		}

		for (EquipmentSlot slot : EquipmentSlot.values()) {
			clean(player.getItemBySlot(slot));
		}
	}

	private static void clean(ItemStack stack) {
		if (!stack.isEmpty() && stack.get(Gore.BLOOD) != null) {
			stack.remove(Gore.BLOOD);
		}
	}
}
