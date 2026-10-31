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
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
			});
			// LANATURE_GAMETEST=entities runs only the entity scene, =modmenu only the mod list
			if ("entities".equals(System.getenv("LANATURE_GAMETEST"))) {
				entities(context, server, world);
				return;
			}
			if ("modmenu".equals(System.getenv("LANATURE_GAMETEST"))) {
				modMenu(context);
				return;
			}
			server.runCommand("tp @p 0.5 99 0.5 180 -15");
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
	 * Modelled entities, still (no AI) in a row on a stone floor west of the first scene: chickens of each climate,
	 * named breeds, a baby, then the pack's other remodelled mobs; seen from the front and from behind. A second row
	 * behind the first camera holds what changes a model's pose or size: a sitting cat, a kitten, a large and a small
	 * salmon.
	 */
	private static void entities(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("fill -62 98 -12 -24 98 4 minecraft:stone");
		server.runCommand("fill -62 99 -12 -24 110 4 minecraft:air");
		String[] chickens = {
			"variant:\"minecraft:temperate\"", "variant:\"minecraft:warm\"", "variant:\"minecraft:cold\"", "CustomName:\"sculk\"",
			"CustomName:\"Turkey\"", "CustomName:\"Duck\"", "Age:-24000",
		};
		for (int i = 0; i < chickens.length; i++) {
			server.runCommand("summon minecraft:chicken " + (-58.5 + i * 1.5) + " 99 -4.5 {" + chickens[i] + ",NoAI:1b,Rotation:[0f,0f]}");
		}
		String[] others = {"fox", "cat", "parrot", "allay", "armor_stand", "copper_golem", "salmon", "cod", "pufferfish"};
		server.runCommand("fill -30 98 -6 -25 98 -3 minecraft:water");
		for (int i = 0; i < others.length; i++) {
			server.runCommand("summon minecraft:" + others[i] + " " + (-44.5 + i * 2) + " 99 -4.5 {NoAI:1b,Rotation:[0f,0f]}");
		}
		// the last a tame cat (an owner) with a blue collar, which Bedrock dyes through the cat's colour-mask material
		String[] posed = {"cat {Sitting:1b", "cat {Age:-24000", "salmon {type:\"large\"", "salmon {type:\"small\"", "cat {Owner:[I;1,2,3,4],CollarColor:11b"};
		for (int i = 0; i < posed.length; i++) {
			server.runCommand("summon minecraft:" + posed[i].replace(" {", " " + (-42.5 + i * 2) + " 99 1.5 {") + ",NoAI:1b,Rotation:[0f,0f]}");
		}
		server.runCommand("tp @p -54 100 -0.5 180 25");
		world.getConnection().waitForChunksRender();
		context.waitTicks(40);
		String state = context.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
				if (e != mc.player) {
					sb.append("\n  ").append(e instanceof net.minecraft.world.entity.LivingEntity le ? "body " + le.yBodyRot + " head " + le.yHeadRot + " " : "")
						.append(e.getType().builtInRegistryHolder().getRegisteredName()).append(e.hasCustomName() ? " \"" + e.getCustomName().getString() + "\"" : "")
						.append(": ").append(describe(EntityScripts.get().model(e, 1))).append("\n    playing ").append(EntityScripts.get().describePlaying(e));
				}
			}
			return sb.toString();
		});
		LOGGER.info("Entity models: {}", state);
		context.takeScreenshot("lanature-entities-chickens");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-chickens-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		server.runCommand("tp @p -54 100 -8.5 0 25");
		context.waitTicks(10);
		context.takeScreenshot("lanature-entities-chickens-back");
		server.runCommand("tp @p -40 100.5 -0.5 180 25");
		context.waitTicks(10);
		context.takeScreenshot("lanature-entities-others");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-others-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		// close-ups, Bedrock then Java: the armor stand from the front, the fox and the cat from the side, the copper golem
		// from the front, the second row from behind it
		String[][] closeUps = {{"armor_stand", "-36.5 99.5 -1.5 180 10"}, {"fox", "-41.5 99.2 -4.5 90 10"}, {"cat", "-39.5 99.2 -4.5 90 10"},
			{"copper_golem", "-34.5 99.6 -2 180 10"}, {"tame_cat", "-32 99.4 1.5 90 15"},
			{"posed", "-39.5 100.3 4.5 180 20"}};
		for (String[] c : closeUps) {
			server.runCommand("tp @p " + c[1]);
			context.waitTicks(10);
			context.takeScreenshot("lanature-entities-" + c[0]);
			context.runOnClient(mc -> EntityScripts.get().setModels(false));
			context.waitTicks(2);
			context.takeScreenshot("lanature-entities-" + c[0] + "-java");
			context.runOnClient(mc -> EntityScripts.get().setModels(true));
		}
		// the hurt flash, through a USE_OVERLAY material: the fox struck, a tick later (its red lasts ten ticks)
		server.runCommand("tp @p -41.5 99.2 -4.5 90 10");
		context.waitTicks(10);
		server.runCommand("damage @e[type=minecraft:fox,limit=1] 1");
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-fox-hurt");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(25);
		server.runCommand("damage @e[type=minecraft:fox,limit=1] 1");
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-fox-hurt-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		// the warden keeps Java's model (the pack changes only its scripts): its scripts' controllers, still, then a tick
		// after a vibration (the event the server sends), which starts the shiver
		server.runCommand("summon minecraft:warden -40.5 99 -10.5 {NoAI:1b,Rotation:[0f,0f]}");
		context.waitTicks(30);
		LOGGER.info("Warden still: {}", warden(context));
		context.runOnClient(mc -> wardens(mc).forEach(w -> w.handleEntityEvent((byte) 61)));
		context.waitTicks(1);
		LOGGER.info("Warden after a vibration: {}", warden(context));
		context.waitTicks(20);
		LOGGER.info("Warden a second later: {}", warden(context));
		// what is modelled but not living, in a row west of the second: experience orbs (a spectator draws no orbs to it),
		// an end crystal, a pearl and two potions held in the air
		server.runCommand("gamemode spectator @p");
		String[] thrown = {
			"experience_orb -56.5 99.3 1.5 {Value:1,NoGravity:1b}", "experience_orb -55 99.3 1.5 {Value:400,NoGravity:1b}",
			"end_crystal -52.5 99 1.5 {ShowBottom:1b}", "ender_pearl -50 99.5 1.5 {NoGravity:1b}",
			"splash_potion -48.5 99.5 1.5 {NoGravity:1b,Item:{id:\"minecraft:splash_potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:swiftness\"}}}}",
			"lingering_potion -47 99.5 1.5 {NoGravity:1b,Item:{id:\"minecraft:lingering_potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:poison\"}}}}",
		};
		for (String t : thrown) {
			server.runCommand("summon minecraft:" + t);
		}
		server.runCommand("tp @p -51.5 100.6 4.5 180 20");
		context.waitTicks(20);
		String thrownState = context.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
				if (!(e instanceof net.minecraft.world.entity.LivingEntity)) {
					sb.append("\n  ").append(e.getType().builtInRegistryHolder().getRegisteredName()).append(": ").append(describe(EntityScripts.get().model(e, 1)))
						.append("\n    playing ").append(EntityScripts.get().describePlaying(e));
				}
			}
			return sb.toString();
		});
		LOGGER.info("Non-living models: {}", thrownState);
		context.takeScreenshot("lanature-entities-thrown");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-thrown-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		// the pack's vanilla orbs (a setting): one atlas texture, the frame picked by the render controller's uv_anim;
		// Java's icons 0, 4 and 5
		server.runCommand("summon minecraft:experience_orb -58 99.3 1.5 {Value:40,NoGravity:1b}");
		server.runCommand("summon minecraft:experience_orb -59.5 99.3 1.5 {Value:80,NoGravity:1b}");
		Object orbModel = context.computeOnClient(mc -> PackSettings.get().value("lnbc:xp_orb_model"));
		context.runOnClient(mc -> PackSettings.get().set("lnbc:xp_orb_model", "vanilla"));
		server.runCommand("tp @p -57.5 100.2 3.5 180 25");
		context.waitTicks(10);
		String orbs = context.computeOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, mc.player.getBoundingBox().inflate(16))
			.stream().map(o -> "icon " + o.getIcon() + ": " + describe(EntityScripts.get().model(o, 1))).toList().toString());
		LOGGER.info("Vanilla orbs: {}", orbs);
		context.takeScreenshot("lanature-entities-orbs-vanilla");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-orbs-vanilla-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		context.runOnClient(mc -> PackSettings.get().set("lnbc:xp_orb_model", orbModel));
		server.runCommand("gamemode creative @p");
		// the bobber, cast into the pool (a bobber exists only on a fishing player's line)
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:fishing_rod");
		server.runCommand("tp @p -27.5 99 0.5 180 35");
		context.waitTicks(5);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitTicks(40);
		String hook = context.computeOnClient(mc -> mc.player.fishing == null ? "no bobber"
			: describe(EntityScripts.get().model(mc.player.fishing, 1)) + "\n    playing " + EntityScripts.get().describePlaying(mc.player.fishing));
		LOGGER.info("Bobber: {}", hook);
		context.takeScreenshot("lanature-entities-bobber");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-bobber-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		context.getInput().pressKey(options -> options.keyUse);
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		server.runCommand("tp @p -30.5 100.5 -0.5 180 30");
		context.waitTicks(10);
		context.takeScreenshot("lanature-entities-fish");
		server.runCommand("tp @p -40 100.5 -8.5 0 25");
		context.waitTicks(10);
		context.takeScreenshot("lanature-entities-others-back");
	}

	/** Mod Menu's list (in the development client), searched for the mod: its icon, name and description. */
	private static void modMenu(ClientGameTestContext context) {
		context.setScreen(() -> {
			try {
				return (net.minecraft.client.gui.screens.Screen) Class.forName("com.terraformersmc.modmenu.gui.ModsScreen")
					.getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Mod Menu is not in the development client", e);
			}
		});
		context.waitTicks(10);
		context.getInput().typeChars("La Nature");
		context.waitTicks(10);
		double[] firstRow = context.computeOnClient(mc -> new double[] {mc.getWindow().getScreenWidth() * 0.2, mc.getWindow().getScreenHeight() * 0.2});
		context.getInput().setCursorPos(firstRow[0], firstRow[1]);
		context.getInput().pressMouse(0); // the left button
		context.waitTicks(10);
		context.takeScreenshot("lanature-modmenu");
		context.setScreen(() -> null);
	}

	private static java.util.List<net.minecraft.world.entity.monster.warden.Warden> wardens(net.minecraft.client.Minecraft mc) {
		return mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.warden.Warden.class, mc.player.getBoundingBox().inflate(32));
	}

	private static String warden(ClientGameTestContext context) {
		return context.computeOnClient(mc -> wardens(mc).stream().map(w -> "tendrils " + w.getTendrilAnimation(1) + " heart " + w.getHeartAnimation(1)
			+ ": " + describe(EntityScripts.get().model(w, 1)) + "; playing " + EntityScripts.get().describePlaying(w)).toList().toString());
	}

	private static String describe(xyz.ivyhq.lanature.client.model.ModelState m) {
		if (m == null) {
			return "Java model";
		}
		return m.parts().stream().map(p -> p.draw().geometry().id() + " " + p.draw().texture()
			+ " " + java.util.Arrays.stream(p.draw().boneMaterials()).filter(java.util.Objects::nonNull).distinct().toList()
			+ (p.draw().changeColor() != -1 ? String.format(" color #%08X", p.draw().changeColor()) : "")
			+ (p.draw().uvAnim() != null ? " uv_anim " + java.util.Arrays.toString(p.draw().uvAnim()) : "")).toList() + " scale " + m.scale();
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
