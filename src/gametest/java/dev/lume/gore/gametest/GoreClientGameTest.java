package dev.lume.gore.gametest;

import dev.lume.gore.Decal;
import dev.lume.gore.client.DecalManager;
import dev.lume.gore.client.DecalRenderer;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the real client so the mod's rendering can be seen where {@code runClient}
 * cannot open a window (a VM, where GLFW's NSGL backend finds no pixel format).
 *
 * <p>Two things about this harness shape the test. The player belongs to the
 * integrated server, so writing to the client's copy of it (its position or its
 * rotation) is overwritten on the next update -- the camera has to be aimed through
 * {@link ClientGameTestContext#getInput()}, which drives the real mouse. And the
 * world is superflat with its surface near y = -60, so every coordinate is derived
 * from the player rather than assumed.
 *
 * <p>The wounds are dealt with {@code /damage ... minecraft:player_attack by @p}, which
 * makes the player the causing entity. Without an attacker the mod's impact direction
 * falls back to straight down and every splatter lands on the floor; with one, the
 * blood sprays away from the player, which is what puts it on the wall. The same
 * causing entity is what lets the mod stain the weapon: {@code getWeaponItem()}
 * resolves to the attacker's main hand, so the held sword is the item to inspect.
 *
 * <p>The weapon stain is an item decoration, which the client draws on the GUI icon
 * rather than the first-person hand model, so the proof is a screenshot of the
 * inventory with the bloodied sword in the hotbar.
 */
@SuppressWarnings("UnstableApiUsage")
public class GoreClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("gore-gametest");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			// Particles are the whole point; the restored defaults could have them off.
			context.runOnClient(mc -> mc.options.particles().set(ParticleStatus.ALL));

			// The HUD (hotbar, crosshair, held item and the first-person hand) paints
			// non-blood pixels into every world screenshot, which makes the pixel gates
			// and human review noisier. There is no options.hideGui anymore — F1 goes
			// through Hud.toggle(), and GuiRenderState.isHudHidden (written from
			// Hud.isHidden) is what skips the hand too. Keep it on for the rest of the
			// scenario; a Screen such as the inventory is extracted separately from the
			// HUD, so 0003 still shows the blood on the item icon.
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});

			BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
			int fx = feet.getX();
			int fy = feet.getY();
			int fz = feet.getZ();
			LOGGER.info("[gore-gametest] feet block: {}", feet);

			// The wall four blocks ahead, the cow in front of it, the attacker behind.
			BlockPos wall = new BlockPos(fx, fy, fz - 4);
			BlockPos cow = new BlockPos(fx, fy, fz - 2);

			singleplayer.getServer().runCommand("time set noon");
			singleplayer.getServer().runCommand("weather clear");
			singleplayer.getServer().runCommand("difficulty easy");
			singleplayer.getServer().runCommand(
					"fill " + (fx - 8) + " " + (fy - 1) + " " + (fz - 8) + " " + (fx + 8) + " " + (fy - 1) + " " + (fz + 8) + " stone");
			singleplayer.getServer().runCommand(
					"fill " + (fx - 8) + " " + fy + " " + (fz - 8) + " " + (fx + 8) + " " + (fy + 6) + " " + (fz + 8) + " air");
			singleplayer.getServer().runCommand(
					"fill " + (fx - 4) + " " + fy + " " + (fz - 4) + " " + (fx + 4) + " " + (fy + 5) + " " + (fz - 4) + " stone");
			singleplayer.getServer().runCommand(
					"summon minecraft:cow " + (fx + 0.5) + " " + fy + " " + (fz - 2.5) + " "
							+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:0b,Health:200f,"
							+ "attributes:[{id:\"minecraft:max_health\",base:200d}]}");
			context.waitTicks(10);

			// Keep the player where they are; do not let them fall or drift.
			context.runOnClient(mc -> mc.player.setNoGravity(true));
			singleplayer.getConnection().waitForClientboundEntityUpdates(EntityTypes.COW);

			// Aim with the mouse, which is the only thing the renderer follows.
			context.getInput().lookAt(cow);
			context.waitTicks(10);

			int nearby = context.computeOnClient(mc -> mc.level.getEntitiesOfClass(
					net.minecraft.world.entity.animal.cow.Cow.class, mc.player.getBoundingBox().inflate(24)).size());
			float pitch = context.computeOnClient(mc -> mc.player.getXRot());
			LOGGER.info("[gore-gametest] cows near player: {} pitch after lookAt: {}", nearby, pitch);

			// Put a weapon in hand; the mod stains whatever the attacker holds.
			singleplayer.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:iron_sword");
			context.waitTicks(5);

			// Wound the cow as the player. A single 120-damage hit on a 200-health cow
			// is severity 0.6 — past the mist (0.35), splatter (0.22) and drip (0.55)
			// thresholds — and leaves it alive. The heavy hit also throws a run of
			// blood down the wall, which the drip cells animate.
			singleplayer.getServer().runCommand(
					"damage @e[type=minecraft:cow,limit=1] 120 minecraft:player_attack by @p");
			context.waitTicks(25);

			// Shoot quickly: the blood mist only lives about a second.
			context.waitTicks(5);
			context.takeScreenshot("gore-bleeding");

			// The body stain rides the cow's own model, so the proof is a screenshot
			// of the cow itself: aim at it and shoot. The wound faces the attacker
			// (the impact direction points away from them, and the stain faces back
			// along it), which is the side the camera is on.
			context.getInput().lookAt(cow);
			context.waitTicks(10);
			context.takeScreenshot("gore-body");

			// The whole point of the stain is that it is welded to the body, so it
			// must follow the cow when the cow turns. Spin the cow a half turn and
			// shoot again: the stains have to travel with the limbs they landed on
			// rather than staying behind in the world or sliding to another part.
			singleplayer.getServer().runCommand(
					"tp @e[type=minecraft:cow,limit=1] " + (fx + 0.5) + " " + fy + " " + (fz - 2.5) + " 180 0");
			context.waitTicks(10);
			context.takeScreenshot("gore-body-turned");
			singleplayer.getServer().runCommand(
					"tp @e[type=minecraft:cow,limit=1] " + (fx + 0.5) + " " + fy + " " + (fz - 2.5) + " 0 0");
			context.waitTicks(5);

			// The stain lives on the item icon, which the GUI draws; the first-person
			// hand model is not where decorations render. Open the inventory so the
			// bloodied sword is plainly visible in the hotbar row.
			LocalPlayer clientPlayer = context.computeOnClient(mc -> mc.player);
			context.runOnClient(mc -> mc.setScreenAndShow(new InventoryScreen(clientPlayer)));
			context.waitTicks(10);
			context.takeScreenshot("gore-gear");
			context.runOnClient(mc -> mc.setScreenAndShow(null));
			context.waitTicks(5);

			// Look at the wall the splatters landed on. Move the cow out of the way
			// first, since it stands between the player and the wall.
			singleplayer.getServer().runCommand(
					"tp @e[type=minecraft:cow,limit=1] " + (fx + 0.5) + " " + fy + " " + (fz + 6));
			context.waitTicks(5);
			context.getInput().lookAt(wall);
			context.waitTicks(20);
			context.takeScreenshot("gore-splatters");

			// The bloodied sword decorates the hotbar icon, and replacing it with
			// air just bares the first-person arm, which still carries the weapon
			// stain — both are dark red inside pixelstat's colour window and would
			// drown the decal the tail scenes measure. Swap to a clean, unstained
			// item instead (the attacker's weapon was only needed earlier).
			singleplayer.getServer().runCommand("item replace entity @p weapon.mainhand with minecraft:stone");
			context.waitTicks(5);

			// The cow's open wound keeps emitting short-lived blood particles that
			// randomly cross the frame during the tail screenshots. The second wall
			// hides the cow itself, but not its spray, so move it far away too.
			singleplayer.getServer().runCommand(
					"tp @e[type=minecraft:cow,limit=1] " + (fx + 0.5) + " " + fy + " " + (fz + 200));
			context.waitTicks(5);

			// Stain-tail orientation (DecalFrame): a clean second wall behind the
			// player at fz+4 — the cow, teleported to fz+6, now hides behind it.
			// The player looks at its -Z (NORTH) face, the only face a decal can be
			// visible on from in front; variant 9 is elongation band 3 / density 0,
			// a long ellipse with a tail and no satellites. rot=90 points the tail
			// up, rot=-90 down; tools/pixelstat.py compares the two screenshots'
			// centroids.
			singleplayer.getServer().runCommand(
					"fill " + (fx - 4) + " " + fy + " " + (fz + 4) + " " + (fx + 4) + " " + (fy + 5) + " " + (fz + 4) + " stone");
			Vec3 wallPoint = new Vec3(fx + 0.5, fy + 1.5, fz + 4);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, wallPoint));
			context.waitTicks(5);

			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				DecalManager.addDecals(List.of(new Decal(fx, fy + 1, fz + 4,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 90f, 9, 0L)));
			});
			context.computeOnClient(mc -> {
				LOGGER.info("[gore-gametest] decals before tail-up: {}", DecalManager.snapshot());
				return true;
			});
			context.waitTicks(15);
			context.takeScreenshot("gore-tail-up");

			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				DecalManager.addDecals(List.of(new Decal(fx, fy + 1, fz + 4,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, -90f, 9, 0L)));
			});
			context.computeOnClient(mc -> {
				LOGGER.info("[gore-gametest] decals before tail-down: {}", DecalManager.snapshot());
				return true;
			});
			context.waitTicks(15);
			context.takeScreenshot("gore-tail-down");

			// Leave the decals empty for any later scenes.
			context.runOnClient(mc -> DecalManager.clear());

			// Task 4: the directed spray. Re-summon the cow where the earlier wounds
			// stood and strike it from the player's left: the damage-source position
			// (fx-3, fy+1, fz-2.5) is the fallback for impactDirection when the blow
			// has no causing entity ("at" rather than "by"), so the jet travels +X —
			// screen-right for a player facing -Z. With drag-based droplet ballistics
			// the jet must visibly reach the right of the frame two ticks after the
			// hit, then have landed or been removed forty ticks later (only stains
			// and body blood remain).
			singleplayer.getServer().runCommand("kill @e[type=minecraft:cow]");
			context.waitTicks(5);
			singleplayer.getServer().runCommand(
					"summon minecraft:cow " + (fx + 0.5) + " " + fy + " " + (fz - 2.5) + " "
							+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:0b,Health:200f,"
							+ "attributes:[{id:\"minecraft:max_health\",base:200d}]}");
			context.waitTicks(5);
			context.getInput().lookAt(cow);
			context.waitTicks(5);

			singleplayer.getServer().runCommand(
					"damage @e[type=minecraft:cow,limit=1] 120 minecraft:player_attack at "
							+ (fx - 3) + " " + (fy + 1) + " " + (fz - 2.5));
			// Captured after the hurt animation ends (hurtTime is 10 ticks). At +2 the
			// cow is still flashing red, and its whole model passes the blood hue test,
			// which swamps any count of the spray itself. At +12 the flash is gone, the
			// droplets have landed, and what is left on screen is the mist cloud.
			context.waitTicks(12);
			context.takeScreenshot("gore-spray");
			context.waitTicks(40);
			context.takeScreenshot("gore-spray-settled");

			// Task 5: drip flow. The clean wall at fz+4, aimed at its top area
			// (fy+2, above any later stains); the drip decal is born now, so the
			// run must grow downward from a fixed anchor: at tick 5 it is a stub,
			// at 30 a short run, at 120 full length. tools/pixelstat.py over the
			// three screenshots checks bbox.y0 stays put while y1 descends.
			Vec3 dripWallPoint = new Vec3(fx + 0.5, fy + 2.5, fz + 4);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, dripWallPoint));
			context.waitTicks(5);

			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				long born = mc.level.getGameTime();
				DecalManager.addDecals(List.of(new Decal(fx, fy + 2, fz + 4,
						Direction.NORTH.get3DDataValue(), 0f, 0.3f, 0.6f, 90f, 14, born)));
			});
			context.waitTicks(5);
			context.takeScreenshot("gore-drip-05");
			context.waitTicks(25);
			context.takeScreenshot("gore-drip-30");
			context.waitTicks(90);
			context.takeScreenshot("gore-drip-120");

			// Task 6: drying. A round clean stain below the drip, born now. With
			// gore.dryScale=0.02 the clock is wet until tick 24 and fully dry at
			// tick 120, so the wet shot at age 5 and the dry shot at age 135 must
			// show the tint shift from white (wet red) to (133, 217, 153) (brown).
			Vec3 dryWallPoint = new Vec3(fx + 0.5, fy + 1.5, fz + 4);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, dryWallPoint));
			context.waitTicks(5);

			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				DecalManager.addDecals(List.of(new Decal(fx, fy + 1, fz + 4,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 0f, 0, mc.level.getGameTime())));
			});
			context.waitTicks(5);
			context.takeScreenshot("gore-dry-wet");
			context.waitTicks(130);
			context.takeScreenshot("gore-dry-dry");

			// Leave the decals empty for any later scenes.
			context.runOnClient(mc -> DecalManager.clear());
		}
	}
}
