package xyz.ivyhq.lanature.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.particle.ParticleSystem;

/**
 * Builds a small scene (a leaf canopy, torches, a campfire, water), lets the client run, and records what the
 * Bedrock particle system did: effect, emitter and particle counts in the log, and screenshots.
 */
public final class LaNatureGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/gametest");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			TestServerContext server = world.getServer();
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("tp @p 0.5 100 0.5 180 -10");
			context.waitTicks(20);
			server.runCommand("fill -8 98 -16 8 98 2 minecraft:grass_block");
			server.runCommand("fill -8 99 -16 8 112 2 minecraft:air");
			server.runCommand("fill -5 104 -12 5 104 -6 minecraft:oak_leaves[persistent=true]");
			server.runCommand("fill -5 105 -12 5 105 -6 minecraft:cherry_leaves[persistent=true]");
			for (int x = -4; x <= 4; x += 2) {
				server.runCommand("setblock " + x + " 99 -5 minecraft:torch");
			}
			server.runCommand("setblock 0 99 -9 minecraft:campfire");
			server.runCommand("fill 2 98 -10 4 98 -8 minecraft:water");
			server.runCommand("tp @p 0.5 99 0.5 180 -15");
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			world.getConnection().waitForChunksRender();
			context.waitTicks(100);
			log(context, "after 5 s");
			context.takeScreenshot("lanature-scene-day");
			context.waitTicks(100);
			log(context, "after 10 s");
			context.takeScreenshot("lanature-scene-day-later");
			context.waitTicks(500);
			log(context, "after 35 s");
			server.runCommand("time set midnight");
			context.waitTicks(100);
			log(context, "night");
			context.takeScreenshot("lanature-scene-night");
			// client entity scripts: a torch held in third person, then the dust of the deep overworld
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:torch");
			context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
			context.waitTicks(60);
			log(context, "holding a torch");
			context.takeScreenshot("lanature-torch-third-person");
			// this world is empty below the scene: a stone cave pocket to stand in
			server.runCommand("fill -4 17 -4 4 24 4 minecraft:stone");
			server.runCommand("fill -3 18 -3 3 23 3 minecraft:air");
			server.runCommand("tp @p 0.5 18 0.5");
			context.waitTicks(100);
			log(context, "at y 18");
		}
	}

	private static void log(ClientGameTestContext context, String when) {
		String counts = context.computeOnClient(mc -> {
			ParticleSystem ps = ParticleSystem.get();
			return ps.effectCount() + " effects loaded, " + ps.totalEmitters() + " emitters, " + ps.totalParticles() + " particles; busiest: "
				+ ps.busiest(8) + "; " + ps.timings() + "; entity scripts: " + EntityScripts.get().running() + " running, player "
				+ EntityScripts.get().describe(mc.player);
		});
		LOGGER.info("Bedrock particles {}: {}", when, counts);
	}
}
