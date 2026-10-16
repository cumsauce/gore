package dev.lume.gore.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.entity.EntityTypes;
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
 * <p>The wounds are dealt by a summoned zombie standing behind the cow, not by the
 * {@code /damage} command: {@code /damage ... minecraft:generic} has no attacker and
 * no projectile, so the mod's impact direction falls back to straight down and every
 * splatter lands on the floor. A real attacker makes the blood spray sideways, which
 * is what puts it on the wall.
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

			BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
			int fx = feet.getX();
			int fy = feet.getY();
			int fz = feet.getZ();
			LOGGER.info("[gore-gametest] feet block: {}", feet);

			// The wall four blocks ahead, the cow in front of it, the attacker behind.
			BlockPos wall = new BlockPos(fx, fy, fz - 4);
			BlockPos cow = new BlockPos(fx, fy, fz - 2);

			singleplayer.getServer().runCommand("time set midnight");
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
							+ "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:0b,Health:60f,"
							+ "attributes:[{id:\"minecraft:max_health\",base:60d}]}");
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

			// Wound the cow with arrows fired from the player's side. An arrow is a
			// real projectile: the mod reads its velocity, so the spray is directed
			// and the splatters land on the wall behind the cow. Two arrows of about
			// 15 each on a 60-health cow is severity 0.25, above the splatter
			// threshold, and leaves the cow alive for the screenshot. A summoned
			// arrow lands more than its "damage" value -- the velocity adds to it --
			// so the value is a starting point, not the final hit.
			for (int i = 0; i < 2; i++) {
				singleplayer.getServer().runCommand("execute at @p run summon minecraft:arrow ~ ~1 " + (fz - 1.05)
						+ " {damage:11.0d,Motion:[0.0d,0.0d,-3.0d],pickup:4b}");
				context.waitTicks(25);
			}

			context.waitTicks(30);
			context.takeScreenshot("gore-bleeding");

			// Look at the wall the splatters landed on.
			context.getInput().lookAt(wall);
			context.waitTicks(20);
			context.takeScreenshot("gore-splatters");
		}
	}
}
