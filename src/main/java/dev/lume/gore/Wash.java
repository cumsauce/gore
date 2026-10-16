package dev.lume.gore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Rinses blood off a player's gear while they are in water. */
public final class Wash {
	private Wash() {
	}

	public static void tick(MinecraftServer server) {
		for (var player : server.getPlayerList().getPlayers()) {
			if (player.isInWater()) {
				wash(player);
			}
		}
	}

	private static void wash(Player player) {
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
