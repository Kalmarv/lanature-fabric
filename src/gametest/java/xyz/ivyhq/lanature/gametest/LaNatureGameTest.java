package xyz.ivyhq.lanature.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.attribute.EnvironmentAttributes;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.environment.BiomeLooks;
import xyz.ivyhq.lanature.client.environment.PackEnvironment;
import xyz.ivyhq.lanature.client.particle.ParticleSystem;
import xyz.ivyhq.lanature.client.settings.PackSettings;
import xyz.ivyhq.lanature.client.settings.PackSettingsScreen;
import net.fabricmc.loader.api.FabricLoader;

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
			// the settings screen: the pack's texts and controls, a change saved on Done
			context.runOnClient(mc -> PackSettings.get().set("lnbc:render_distance", 40.0));
			context.setScreen(() -> new PackSettingsScreen(null));
			context.waitTicks(5);
			context.takeScreenshot("lanature-settings-top");
			// the middle of the list, in window pixels
			double[] middle = context.computeOnClient(mc -> new double[] {mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0});
			context.getInput().setCursorPos(middle[0], middle[1]);
			context.getInput().scroll(-12);
			context.waitTicks(5);
			context.takeScreenshot("lanature-settings-scrolled");
			context.getInput().scroll(-40);
			context.waitTicks(5);
			context.takeScreenshot("lanature-settings-controls");
			context.clickScreenButton("gui.done");
			context.waitTicks(2);
			try {
				LOGGER.info("Saved settings: {}", java.nio.file.Files.readString(FabricLoader.getInstance().getConfigDir().resolve("lanature.json")).replaceAll("\\s+", " "));
			} catch (java.io.IOException e) {
				throw new AssertionError("settings were not saved", e);
			}
			fog(context, server, world);
		}
	}

	/**
	 * Biome fogs: stone pillars every 12 blocks to the east (away from the first scene), seen from the default biome, then with the camera's
	 * surroundings turned into a frozen biome (the pack's {@code lnbc:frozen} haze), in rain, and in a meadow (the
	 * pack's sky colour).
	 */
	private static void fog(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		String looks = context.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (String id : java.util.List.of("minecraft:plains", "minecraft:snowy_plains", "minecraft:meadow", "minecraft:birch_forest", "minecraft:deep_dark")) {
				sb.append("\n  ").append(id).append(" = ").append(BiomeLooks.table().byJavaId().get(id));
			}
			return sb.append("\n  fallback = ").append(BiomeLooks.table().fallback()).toString();
		});
		LOGGER.info("Biome looks: {}", looks);
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		server.runCommand("fill -2 98 -2 2 98 2 minecraft:stone");
		server.runCommand("fill 4 98 -3 6 98 3 minecraft:water");
		for (int i = 0; i < 6; i++) {
			int x = 12 + 12 * i;
			int z = (i % 2 == 0 ? -1 : 1) * (3 + i * 2);
			server.runCommand("fill " + (x - 1) + " 92 " + (z - 1) + " " + (x + 1) + " 116 " + (z + 1) + " minecraft:stone");
		}
		server.runCommand("tp @p 0.5 99 0.5 -90 5");
		world.getConnection().waitForChunksRender();
		context.waitTicks(40);
		fogState(context, "default biome");
		context.takeScreenshot("lanature-fog-default");
		server.runCommand("fillbiome -16 84 -16 15 115 15 minecraft:snowy_plains");
		context.waitTicks(40);
		fogState(context, "snowy plains");
		context.takeScreenshot("lanature-fog-frozen");
		server.runCommand("weather rain");
		context.waitTicks(200);
		fogState(context, "snowy plains, rain");
		context.takeScreenshot("lanature-fog-frozen-rain");
		server.runCommand("weather clear");
		server.runCommand("fillbiome -16 84 -16 15 115 15 minecraft:meadow");
		context.waitTicks(200);
		fogState(context, "meadow");
		context.takeScreenshot("lanature-fog-meadow");
	}

	private static void fogState(ClientGameTestContext context, String where) {
		String state = context.computeOnClient(mc -> {
			net.minecraft.client.Camera camera = mc.gameRenderer.mainCamera();
			net.minecraft.world.attribute.EnvironmentAttributeProbe probe = camera.attributeProbe();
			java.util.function.Function<org.joml.Vector3fc, String> hex = c -> String.format("#%06X", net.minecraft.util.ARGB.colorFromFloat(1, c.x(), c.y(), c.z()) & 0xFFFFFF);
			return "biome " + mc.level.getBiome(camera.blockPosition()).getRegisteredName()
				+ ", render distance " + mc.options.getEffectiveRenderDistance() * 16
				+ ", fog " + probe.getValue(EnvironmentAttributes.FOG_START_DISTANCE, 1) + ".." + probe.getValue(EnvironmentAttributes.FOG_END_DISTANCE, 1)
				+ " " + hex.apply(probe.getValue(EnvironmentAttributes.FOG_COLOR, 1))
				+ ", water fog " + probe.getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, 1) + ".." + probe.getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, 1)
				+ " " + hex.apply(probe.getValue(EnvironmentAttributes.WATER_FOG_COLOR, 1))
				+ ", sky " + hex.apply(probe.getValue(EnvironmentAttributes.SKY_COLOR, 1))
				+ ", rain " + mc.level.getRainLevel(1) + ", pack weather " + PackEnvironment.weather(mc.level, camera.position())
				+ ", water colour " + String.format("#%06X", mc.level.getBiome(camera.blockPosition()).value().getWaterColor() & 0xFFFFFF);
		});
		LOGGER.info("Fog in {}: {}", where, state);
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
