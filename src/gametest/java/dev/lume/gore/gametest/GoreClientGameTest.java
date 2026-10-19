package dev.lume.gore.gametest;

import dev.lume.gore.Decal;
import dev.lume.gore.DecalStore;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
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
			context.runOnClient(mc -> LOGGER.info("[gore-gametest] spray camera at {} rot {} {}",
					mc.player.position(), mc.player.getXRot(), mc.player.getYRot()));

			singleplayer.getServer().runCommand(
					"damage @e[type=minecraft:cow,limit=1] 120 minecraft:player_attack at "
							+ (fx - 3) + " " + (fy + 1) + " " + (fz - 2.5));
			// Captured after the hurt animation ends (hurtTime is 10 ticks). At +2 the
			// cow is still flashing red, and its whole model passes the blood hue test,
			// which swamps any count of the spray itself. At +12 the flash is gone and
			// the jet is still crossing the frame, so what is on screen is the spray.
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

			// Task 7: pooling. A pool is born small and floods outward over two
			// seconds (Flow.poolSize). The floor block in front of the player is
			// bare, so the pool is the only blood in frame; the tick-3 shot and
			// the tick-45 shot must differ in size, with the bbox growing around
			// a fixed centre (tools/pixelstat.py).
			// The spray-scene cow is still standing between the player and the
			// floor, so move it out of frame first.
			singleplayer.getServer().runCommand(
					"tp @e[type=minecraft:cow,limit=1] " + (fx + 0.5) + " " + fy + " " + (fz + 200));
			context.waitTicks(5);
			Vec3 poolPoint = new Vec3(fx + 0.5, fy, fz - 2);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, poolPoint));
			context.waitTicks(5);

			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				DecalManager.addDecals(List.of(new Decal(fx, fy - 1, fz - 2,
						Direction.UP.get3DDataValue(), 0f, 0f, 0.8f, 0f, 13, mc.level.getGameTime())));
			});
			context.waitTicks(3);
			context.takeScreenshot("gore-pool-03");
			context.waitTicks(42);
			context.takeScreenshot("gore-pool-45");

			// Task 8: a mob that has taken several hits. The coat darkens with
			// every wound and the gash and its spatter sit on the body face, so
			// three hits must read as a bloody animal rather than three red
			// specks. The cow is brought a block closer to fill the frame.
			context.runOnClient(mc -> DecalManager.clear());
			singleplayer.getServer().runCommand("kill @e[type=minecraft:cow]");
			context.waitTicks(5);

			BlockPos close = new BlockPos(fx, fy, fz - 2);
			singleplayer.getServer().runCommand(
					"summon minecraft:cow " + (fx + 0.5) + " " + fy + " " + (fz - 1.6) + " "
							+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:0b,Health:200f,"
							+ "attributes:[{id:\"minecraft:max_health\",base:200d}]}");
			context.waitTicks(10);
			Vec3 chestPoint = new Vec3(fx + 0.5, fy + 0.9, fz - 1.6);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, chestPoint));
			context.waitTicks(5);

			// Space the hits past the 10-tick hurt-immunity window: three hits
			// closer together are two hits, and the scene is about a body that
			// has taken several.
			for (int hit = 0; hit < 3; hit++) {
				singleplayer.getServer().runCommand(
						"damage @e[type=minecraft:cow,limit=1] 55 minecraft:player_attack by @p");
				context.waitTicks(13);
			}

			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, chestPoint));
			context.waitTicks(20);
			context.takeScreenshot("gore-body-close");

			// Task 8b: a humanoid, where the torso face is actually visible. A
			// cow's body box is mostly hidden behind its own head and front legs,
			// so the wound and its drip can only be seen through the gap between
			// them; a husk shows the whole chest.
			singleplayer.getServer().runCommand("kill @e[type=minecraft:cow]");
			context.waitTicks(5);
			singleplayer.getServer().runCommand(
					"summon minecraft:husk " + (fx + 0.5) + " " + fy + " " + (fz - 2.5) + " "
							+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Health:200f,"
							+ "attributes:[{id:\"minecraft:max_health\",base:200d}]}");
			context.waitTicks(10);
			Vec3 huskChest = new Vec3(fx + 0.5, fy + 1.0, fz - 2.5);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, huskChest));
			context.waitTicks(5);

			for (int hit = 0; hit < 2; hit++) {
				singleplayer.getServer().runCommand(
						"damage @e[type=minecraft:husk,limit=1] 60 minecraft:player_attack by @p");
				context.waitTicks(13);
			}

			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, huskChest));
			context.waitTicks(20);
			context.takeScreenshot("gore-body-humanoid");

			// Bodies dry on the same clock as walls (-Dgore.dryScale=0.02 in the
			// game-test JVM): the gash browns within seconds of the hit.
			context.waitTicks(130);
			context.takeScreenshot("gore-body-humanoid-dry");

			// Task 8c: dismemberment. A hard blow aimed at the husk's left arm
			// (its left is +x: it faces the player) takes the arm off; the
			// killing blow aimed at its head takes the head. Shot while the limb
			// is in the air and after it has landed.
			Vec3 huskArm = new Vec3(fx + 0.5 + 0.27, fy + 1.3, fz - 2.5);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, huskArm));
			context.waitTicks(3);
			singleplayer.getServer().runCommand(
					"damage @e[type=minecraft:husk,limit=1] 70 minecraft:player_attack by @p");
			context.waitTicks(6);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, huskChest));
			context.waitTicks(2);
			context.takeScreenshot("gore-sever-arm");
			context.waitTicks(40);
			context.takeScreenshot("gore-sever-arm-settled");

			Vec3 huskHead = new Vec3(fx + 0.5, fy + 1.8, fz - 2.5);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, huskHead));
			context.waitTicks(3);
			singleplayer.getServer().runCommand(
					"damage @e[type=minecraft:husk,limit=1] 1000 minecraft:player_attack by @p");
			context.waitTicks(4);
			context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, huskChest));
			context.waitTicks(2);
			context.takeScreenshot("gore-sever-head");
			context.waitTicks(40);
			context.takeScreenshot("gore-sever-head-settled");

			// Task 9: the killing blow. The body lets go all at once: a pool
			// spreads under it, spatter lands around it and jets empty the wound.
			// Shot early, while the burst is still in the air, then again after
			// the pool has finished spreading.
			singleplayer.getServer().runCommand("kill @e[type=minecraft:cow,limit=1]");
			context.waitTicks(3);
			context.takeScreenshot("gore-death");
			context.waitTicks(45);
			context.takeScreenshot("gore-death-settled");

			// Task 10: rain washes wall decals off. Place a decal on the clean wall
			// at fz+4 (top of the wall so the block above sees the sky), set weather
			// to rain, wait ~110 ticks (RAIN_WASH_TICKS=4800 * dryScale 0.02 = 96
			// ticks of rain exposure at this scale), and verify the decal is gone
			// from both the server store and the client mirror.
			singleplayer.getServer().runCommand("kill @e[type=minecraft:cow]");
			context.waitTicks(5);

			int rainX = fx;
			int rainY = fy + 5;
			int rainZ = fz + 4;

			// Client-side mirror (as all other decal scenes do)
			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				long born = mc.level.getGameTime();
				DecalManager.addDecals(List.of(new Decal(rainX, rainY, rainZ,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 0f, 9, born)));
			});
			context.waitTicks(5);

			// Same decal on the server store so DecalAging can wash it off.
			// Clear the store first so this decal doesn't merge with a legacy
			// (born=0) decal from an earlier scene — DecalAging skips born=0.
			singleplayer.getServer().computeOnServer(server -> {
				ServerLevel level = server.getLevel(Level.OVERWORLD);
				DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
				store.removeAll(store.getAll());
				store.addOrMerge(new Decal(rainX, rainY, rainZ,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 0f, 9, level.getGameTime()));
				return null;
			});

			// Look at the wall so the decal is in frame
			context.getInput().lookAt(new BlockPos(rainX, rainY, rainZ));
			context.waitTicks(5);
			context.takeScreenshot("gore-rain-wet");

			// Task 10: decay removes old decals. Place a decal born long ago on
			// both the server store and the client mirror, wait one tick, and
			// verify DecalAging removes it from both (server removes from store,
			// broadcasts DecalsGonePayload, client drops it). This uses the same
			// DecalAging infrastructure as rain washing.
			singleplayer.getServer().runCommand("kill @e[type=minecraft:cow]");
			context.waitTicks(5);

			int decayX = fx;
			int decayY = fy + 5;
			int decayZ = fz + 4;

			// Client-side mirror (same literal born as server so remove() matches by equals)
			context.runOnClient(mc -> {
				DecalManager.clear();
				DecalRenderer.clearDrips();
				DecalManager.addDecals(List.of(new Decal(decayX, decayY, decayZ,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 0f, 9, -100000L)));
			});
			context.waitTicks(5);

			// Same decal on the server store, born long ago so Drying.fade removes it
			singleplayer.getServer().computeOnServer(server -> {
				ServerLevel level = server.getLevel(Level.OVERWORLD);
				DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
				store.removeAll(store.getAll());
				store.addOrMerge(new Decal(decayX, decayY, decayZ,
						Direction.NORTH.get3DDataValue(), 0f, 0f, 0.5f, 0f, 9, -100000L));
				return null;
			});
			context.waitTicks(5);

			// Look at the wall so the decal is in frame before it disappears
			context.getInput().lookAt(new BlockPos(decayX, decayY, decayZ));
			context.waitTicks(5);
			context.takeScreenshot("gore-decay-wet");

			// Give DecalAging one tick to remove the faded decal
			context.waitTicks(3);

			// Check server store first
			singleplayer.getServer().computeOnServer(server -> {
				ServerLevel level = server.getLevel(Level.OVERWORLD);
				DecalStore store = level.getDataStorage().computeIfAbsent(DecalStore.TYPE);
				LOGGER.info("[gore-gametest] after decay store size={}", store.getAll().size());
				return null;
			});

			// Give the client a tick to receive DecalsGonePayload
			context.waitTicks(2);

			// Verify the decal is gone from the client mirror
			context.computeOnClient(mc -> {
				List<Decal> remaining = DecalManager.snapshot();
				if (!remaining.isEmpty()) {
					throw new IllegalStateException("decayed decal should be removed: " + remaining);
				}
				return true;
			});
			context.takeScreenshot("gore-decay-dry");

			// Leave the decals empty for any later scenes.
			context.runOnClient(mc -> DecalManager.clear());

			sequences(context, singleplayer, fx, fy, fz);
		}
	}

	/**
	 * Temporal scenes: frame sequences rather than stills, because the defects
	 * that matter here — z-fighting shimmer, particles jittering, a drip's
	 * shape while it runs — only exist across frames. Each frame is named
	 * {@code seq-<scene>-<nnn>}; tools/seq.py turns a scene into an animated
	 * PNG, a contact sheet and a per-frame flicker score.
	 */
	private static void sequences(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			int fx, int fy, int fz) {
		singleplayer.getServer().runCommand("weather clear");
		singleplayer.getServer().runCommand("difficulty peaceful");
		singleplayer.getServer().runCommand("kill @e[type=!minecraft:player]");
		singleplayer.getServer().computeOnServer(server -> {
			DecalStore store = server.getLevel(Level.OVERWORLD).getDataStorage().computeIfAbsent(DecalStore.TYPE);
			store.removeAll(store.getAll());
			return null;
		});
		context.runOnClient(mc -> {
			DecalManager.clear();
			DecalRenderer.clearDrips();
			mc.player.setPos(fx + 0.5, fy, fz + 0.5);
		});
		context.waitTicks(5);

		// 1. Overlap: a cluster of stains, two pools and drips on the front wall
		// and floor, all overlapping, with the camera strafing slowly sideways.
		// Coplanar quads shimmer under exactly this kind of motion.
		context.runOnClient(mc -> {
			long born = mc.level.getGameTime();
			int n = Direction.SOUTH.get3DDataValue();
			int up = Direction.UP.get3DDataValue();
			List<Decal> decals = new java.util.ArrayList<>();
			java.util.Random r = new java.util.Random(7);
			for (int i = 0; i < 18; i++) {
				int bx = fx - 1 + r.nextInt(3);
				decals.add(new Decal(bx, fy + 1 + r.nextInt(2), fz - 4, n,
						(r.nextFloat() - 0.5f) * 0.6f, (r.nextFloat() - 0.5f) * 0.6f,
						0.25f + r.nextFloat() * 0.25f, r.nextFloat() * 360f, r.nextInt(12), born - 5));
			}
			decals.add(new Decal(fx, fy + 2, fz - 4, n, 0.1f, 0.2f, 0.7f, 90f, 14, born));
			decals.add(new Decal(fx - 1, fy + 2, fz - 4, n, 0.3f, 0.1f, 0.5f, 90f, 14, born - 10));
			decals.add(new Decal(fx, fy - 1, fz - 3, up, 0.0f, 0.0f, 0.9f, 0f, 12, born));
			decals.add(new Decal(fx + 1, fy - 1, fz - 3, up, -0.3f, 0.1f, 0.8f, 40f, 13, born));
			for (int i = 0; i < 8; i++) {
				decals.add(new Decal(fx + r.nextInt(2), fy - 1, fz - 3, up,
						(r.nextFloat() - 0.5f) * 0.7f, (r.nextFloat() - 0.5f) * 0.7f,
						0.3f, r.nextFloat() * 360f, r.nextInt(12), born - 5));
			}
			// Bypass the merge so the renderer sees every overlap.
			DecalManager.sync(mc.level.dimension().identifier(), decals);
			mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(fx + 0.5, fy + 1.2, fz - 3.5));
		});
		for (int i = 0; i < 48; i++) {
			int step = i;
			context.runOnClient(mc -> {
				mc.player.setPos(fx + 0.5 - 1.2 + step * 0.05, fy, fz + 0.5 - step * 0.02);
				mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(fx + 0.5, fy + 1.2, fz - 3.5));
			});
			context.waitTicks(1);
			context.takeScreenshot(String.format("seq-overlap-%03d", i));
		}

		// 2. Spray: a cow struck from the side, every tick for a second and a
		// half — droplets, mist and jets, then what lands.
		context.runOnClient(mc -> {
			DecalManager.clear();
			DecalRenderer.clearDrips();
			mc.player.setPos(fx + 0.5, fy, fz + 0.5);
			mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(fx + 0.5, fy + 1.0, fz - 2.5));
		});
		singleplayer.getServer().runCommand(
				"summon minecraft:cow " + (fx + 0.5) + " " + fy + " " + (fz - 2.5) + " "
						+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Health:200f,"
						+ "attributes:[{id:\"minecraft:max_health\",base:200d}]}");
		context.waitTicks(10);
		singleplayer.getServer().runCommand(
				"damage @e[type=minecraft:cow,limit=1] 120 minecraft:player_attack at "
						+ (fx - 3) + " " + (fy + 1) + " " + (fz - 2.5));
		for (int i = 0; i < 30; i++) {
			context.waitTicks(1);
			context.takeScreenshot(String.format("seq-spray-%03d", i));
		}
		singleplayer.getServer().runCommand("kill @e[type=!minecraft:player]");
		context.waitTicks(30); // let the death animation and poof finish

		// 2b. Whip: a cow a block and a half from the wall, struck from behind so
		// the blood is thrown at the wall, filmed from the side. Every droplet
		// should be seen to fly and land as the stain it becomes.
		context.runOnClient(mc -> {
			DecalManager.clear();
			DecalRenderer.clearDrips();
			mc.player.setPos(fx + 4.5, fy + 0.5, fz + 1.0);
			mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(fx, fy + 1.0, fz - 2.5));
		});
		singleplayer.getServer().runCommand("kill @e[type=minecraft:item]");
		singleplayer.getServer().runCommand(
				"summon minecraft:cow " + (fx + 0.5) + " " + fy + " " + (fz - 0.8) + " "
						+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Health:200f,Rotation:[90f,0f],"
						+ "attributes:[{id:\"minecraft:max_health\",base:200d}]}");
		context.waitTicks(10);
		singleplayer.getServer().runCommand(
				"damage @e[type=minecraft:cow,limit=1] 120 minecraft:player_attack at "
						+ (fx + 0.5) + " " + (fy + 0.8) + " " + (fz + 3.0));
		for (int i = 0; i < 24; i++) {
			context.waitTicks(1);
			context.takeScreenshot(String.format("seq-whip-%03d", i));
		}
		singleplayer.getServer().runCommand("kill @e[type=!minecraft:player]");
		context.waitTicks(5);

		// 3. Drip: one run forming under a splat, close up, every third tick.
		context.runOnClient(mc -> {
			DecalManager.clear();
			DecalRenderer.clearDrips();
			long born = mc.level.getGameTime();
			int n = Direction.SOUTH.get3DDataValue();
			DecalManager.sync(mc.level.dimension().identifier(), List.of(
					new Decal(fx, fy + 2, fz - 4, n, 0f, 0.2f, 0.4f, 90f, 0, born),
					new Decal(fx, fy + 2, fz - 4, n, 0f, 0.2f, 0.7f, 90f, 14, born)));
			mc.player.setPos(fx + 0.5, fy, fz - 2.0);
			mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(fx + 0.5, fy + 1.9, fz - 3.5));
		});
		for (int i = 0; i < 40; i++) {
			context.waitTicks(3);
			context.takeScreenshot(String.format("seq-drip-%03d", i));
		}
		context.runOnClient(mc -> DecalManager.clear());

		// 4. Broken block: a stain in the middle of one wall block, a pool-sized
		// splat straddling the seam into its neighbour, a drip running down.
		// Breaking the middle block must take its blood with it (nothing left
		// hanging in the air) while the neighbours keep their part.
		singleplayer.getServer().runCommand(
				"fill " + (fx - 4) + " " + fy + " " + (fz - 4) + " " + (fx + 4) + " " + (fy + 5) + " " + (fz - 4) + " stone");
		context.runOnClient(mc -> {
			int n = Direction.SOUTH.get3DDataValue();
			long born = mc.level.getGameTime() - 200;
			DecalManager.sync(mc.level.dimension().identifier(), List.of(
					new Decal(fx, fy + 2, fz - 4, n, 0f, 0f, 0.35f, 0f, 0, born),
					new Decal(fx, fy + 2, fz - 4, n, 0.42f, 0.1f, 0.5f, 0f, 1, born),
					new Decal(fx, fy + 2, fz - 4, n, -0.2f, -0.2f, 0.8f, 90f, 14, born)));
			mc.player.setPos(fx + 0.5, fy, fz - 1.0);
			mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(fx + 0.5, fy + 2.0, fz - 3.5));
		});
		context.waitTicks(5);
		context.takeScreenshot("seq-break-000");
		singleplayer.getServer().runCommand("setblock " + fx + " " + (fy + 2) + " " + (fz - 4) + " air");
		for (int i = 1; i < 4; i++) {
			context.waitTicks(1);
			context.takeScreenshot(String.format("seq-break-%03d", i));
		}
		context.runOnClient(mc -> DecalManager.clear());
	}
}
