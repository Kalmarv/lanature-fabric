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
import xyz.ivyhq.lanature.client.environment.Colormaps;
import xyz.ivyhq.lanature.client.environment.PackEnvironment;
import xyz.ivyhq.lanature.client.environment.WaterOpacity;
import xyz.ivyhq.lanature.client.mixin.LocalPlayerAccessor;
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
			// LANATURE_GAMETEST=entities runs only the entity scene, =attachables only the held items, =items only the item
			// icons, =colormaps only the colour-mapped leaves, =environment only the end portal and weather, =pictures only
			// the block and item break effects, =events only the crit, totem, splash and smash effects, =poses only the
			// player's poses that Java's renderer also turns (crawling, swimming, riding, gliding, sleeping) and its item
			// uses, =modmenu only the mod list
			if ("entities".equals(System.getenv("LANATURE_GAMETEST"))) {
				entities(context, server, world);
				return;
			}
			if ("attachables".equals(System.getenv("LANATURE_GAMETEST"))) {
				attachables(context, server, world);
				return;
			}
			if ("items".equals(System.getenv("LANATURE_GAMETEST"))) {
				items(context, server);
				return;
			}
			if ("colormaps".equals(System.getenv("LANATURE_GAMETEST"))) {
				colormaps(context, server, world);
				return;
			}
			if ("environment".equals(System.getenv("LANATURE_GAMETEST"))) {
				environment(context, server, world);
				return;
			}
			if ("pictures".equals(System.getenv("LANATURE_GAMETEST"))) {
				pictures(context, server, world);
				return;
			}
			if ("events".equals(System.getenv("LANATURE_GAMETEST"))) {
				events(context, server, world);
				return;
			}
			if ("poses".equals(System.getenv("LANATURE_GAMETEST"))) {
				poses(context, server, world);
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
		// invulnerable, so the fish on land do not dry out and die before the shot
		String[] others = {"fox", "cat", "parrot", "allay", "armor_stand", "copper_golem", "salmon", "cod", "pufferfish"};
		server.runCommand("fill -30 98 -6 -25 98 -3 minecraft:water");
		for (int i = 0; i < others.length; i++) {
			server.runCommand("summon minecraft:" + others[i] + " " + (-44.5 + i * 2) + " 99 -4.5 {NoAI:1b,Invulnerable:1b,Rotation:[0f,0f]}");
		}
		// the last a tame cat (an owner) with a blue collar, which Bedrock dyes through the cat's colour-mask material
		String[] posed = {"cat {Sitting:1b", "cat {Age:-24000", "salmon {type:\"large\"", "salmon {type:\"small\"", "cat {Owner:[I;1,2,3,4],CollarColor:11b"};
		for (int i = 0; i < posed.length; i++) {
			server.runCommand("summon minecraft:" + posed[i].replace(" {", " " + (-42.5 + i * 2) + " 99 1.5 {") + ",NoAI:1b,Invulnerable:1b,Rotation:[0f,0f]}");
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
		// effects at a locator follow its bone: the crystal's aura and light ride its bobbing crystal, the orb's its swirling wind charges
		for (int k = 0; k < 5; k++) {
			String located = context.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
					if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal || e instanceof net.minecraft.world.entity.ExperienceOrb orb && orb.getValue() == 400) {
						sb.append(e.getType().builtInRegistryHolder().getRegisteredName()).append(": ").append(EntityScripts.get().describe(e)).append("; ");
					}
				}
				return sb.toString();
			});
			LOGGER.info("Located effects {}: {}", k, located);
			context.waitTicks(3);
		}
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
		// the pool reaching to where the line lands, so the bobber floats (the pack's float state reads the block below it)
		server.runCommand("fill -30 98 -2 -25 98 -1 minecraft:water");
		context.waitTicks(5);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitTicks(40);
		String hook = context.computeOnClient(mc -> mc.player.fishing == null ? "no bobber"
			: describe(EntityScripts.get().model(mc.player.fishing, 1)) + "\n    playing " + EntityScripts.get().describePlaying(mc.player.fishing)
			+ "\n    java: in water " + mc.player.fishing.isInWater() + ", on ground " + mc.player.fishing.onGround() + ", motion "
			+ mc.player.fishing.getDeltaMovement() + ", at " + mc.player.fishing.position() + ", block 0.05 below " + mc.level.getBlockState(
				net.minecraft.core.BlockPos.containing(mc.player.fishing.getX(), mc.player.fishing.getY() - 0.05, mc.player.fishing.getZ())));
		LOGGER.info("Bobber: {}", hook);
		context.takeScreenshot("lanature-entities-bobber");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-entities-bobber-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		server.runCommand("fill -30 98 -2 -25 98 -1 minecraft:stone");
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
	/**
	 * Held items with attachable models, Bedrock then Java: still husks (humanoids that do not burn) in a row east of
	 * the first scene, each holding a pair, seen from the front and from the side; then the player holding a torch and a
	 * lantern, seen from the front in third person.
	 */
	/**
	 * The local player in third person, from the front, in each pose the pack animates where Java's renderer or model
	 * poses it too: the pack's pose and then Java's.
	 */
	private static void poses(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("fill 54 90 -24 70 98 4 minecraft:stone");
		server.runCommand("fill 54 99 -24 70 140 4 minecraft:air");
		server.runCommand("tp @p 62.5 99 -2.5 180 10");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
		world.getConnection().waitForChunksRender();
		context.waitTicks(40);
		posePair(context, "standing");
		// sneaking: vanilla Bedrock leans the body by its root, Java its model
		context.getInput().holdKey(options -> options.keyShift);
		context.waitTicks(10);
		posePair(context, "sneaking");
		context.getInput().releaseKey(options -> options.keyShift);
		// crawling: a block over the head leaves no room to stand
		server.runCommand("setblock 62 100 -3 minecraft:stone");
		context.waitTicks(20);
		posePair(context, "crawling");
		server.runCommand("setblock 62 100 -3 minecraft:air");
		// swimming: sprinting forward under water
		server.runCommand("fill 56 95 -22 68 98 -1 minecraft:water");
		server.runCommand("tp @p 62.5 95.5 -2.5 180 10");
		context.waitTicks(10);
		context.getInput().holdKey(options -> options.keyUp);
		context.getInput().holdKey(options -> options.keySprint);
		context.waitTicks(25);
		posePair(context, "swimming");
		context.getInput().releaseKey(options -> options.keySprint);
		context.getInput().releaseKey(options -> options.keyUp);
		server.runCommand("fill 56 95 -22 68 98 -1 minecraft:stone");
		// riding a boat
		server.runCommand("tp @p 62.5 99 -2.5 180 10");
		server.runCommand("summon minecraft:oak_boat 62.5 99 -2.5 {Rotation:[180f,0f]}");
		context.waitTicks(5);
		server.runCommand("ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		context.waitTicks(20);
		posePair(context, "riding");
		server.runCommand("ride @p dismount");
		server.runCommand("kill @e[type=minecraft:oak_boat]");
		// gliding with an elytra, high up
		server.runCommand("gamemode survival @p");
		server.runCommand("item replace entity @p armor.chest with minecraft:elytra");
		server.runCommand("tp @p 62.5 160 -2.5 180 10");
		context.waitTicks(3);
		server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().startFallFlying());
		// Java turns the body over the first 10 ticks of a glide
		context.waitTicks(20);
		posePair(context, "gliding");
		server.runCommand("item replace entity @p armor.chest with minecraft:air");
		server.runCommand("gamemode creative @p");
		server.runCommand("tp @p 62.5 99 -2.5 180 10");
		context.waitTicks(20);
		// items in use, or held in a pose of their own
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:crossbow[minecraft:charged_projectiles=[{id:\"minecraft:arrow\"}]]");
		context.waitTicks(10);
		posePair(context, "crossbow-charged");
		String[][] uses = {{"crossbow", "air"}, {"spyglass", "air"}, {"bow", "air"}, {"stone", "shield"}, {"trident", "air"}};
		for (String[] use : uses) {
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:" + use[0]);
			server.runCommand("item replace entity @p weapon.offhand with minecraft:" + use[1]);
			context.waitTicks(5);
			context.getInput().holdKey(options -> options.keyUse);
			context.waitTicks(12);
			posePair(context, "using-" + (use[1].equals("air") ? use[0] : use[1]));
			context.getInput().releaseKey(options -> options.keyUse);
			context.waitTicks(5);
		}
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:air");
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		// sleeping in a bed, at night (the day wakes a sleeper)
		server.runCommand("time set midnight");
		server.runCommand("setblock 62 99 -4 minecraft:red_bed[facing=north,part=head]");
		server.runCommand("setblock 62 99 -3 minecraft:red_bed[facing=north,part=foot]");
		server.runCommand("tp @p 62.5 99 -1.5 180 10");
		context.waitTicks(5);
		server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().startSleeping(new net.minecraft.core.BlockPos(62, 99, -4)));
		context.waitTicks(20);
		posePair(context, "sleeping");
		server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().stopSleeping());
	}

	/** The local player's pose, the pack's then Java's, after turning its body to its head. */
	private static void posePair(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
		context.waitTicks(1);
		LOGGER.info("Pose {}: Java pose {}, sleeping {}, gliding {}, passenger {}, using {}\n  {}\n  playing {}", name,
			context.computeOnClient(mc -> mc.player.getPose()), context.computeOnClient(mc -> mc.player.isSleeping()),
			context.computeOnClient(mc -> mc.player.isFallFlying()), context.computeOnClient(mc -> mc.player.isPassenger()),
			context.computeOnClient(mc -> mc.player.getUseItem().getItem()),
			context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1))),
			context.computeOnClient(mc -> EntityScripts.get().describePlaying(mc.player)));
		context.takeScreenshot("lanature-poses-" + name);
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(1);
		context.takeScreenshot("lanature-poses-" + name + "-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
	}

	private static void attachables(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("fill 24 98 -12 50 98 4 minecraft:stone");
		server.runCommand("fill 24 99 -12 50 110 4 minecraft:air");
		String[][] pairs = {{"torch", "lantern"}, {"soul_torch", "soul_lantern"}, {"redstone_torch", "copper_lantern"}, {"iron_spear", "air"},
			{"mace", "totem_of_undying"}, {"cod", "pumpkin"}};
		for (int i = 0; i < pairs.length; i++) {
			String equipment = "equipment:{mainhand:{id:\"minecraft:" + pairs[i][0] + "\"}" + (pairs[i][1].equals("air") ? "" : ",offhand:{id:\"minecraft:" + pairs[i][1] + "\"}") + "}";
			server.runCommand("summon minecraft:husk " + (28.5 + i * 2) + " 99 -4.5 {NoAI:1b,Silent:1b,Rotation:[0f,0f]," + equipment + "}");
		}
		server.runCommand("tp @p 33.5 100 0.5 180 10");
		world.getConnection().waitForChunksRender();
		context.waitTicks(40);
		String state = context.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.LivingEntity le && e != mc.player) {
					for (net.minecraft.world.entity.HumanoidArm arm : net.minecraft.world.entity.HumanoidArm.values()) {
						sb.append("\n  ").append(e.getType().builtInRegistryHolder().getRegisteredName()).append(" at x ").append(e.getX()).append(' ')
							.append(arm).append(": ").append(EntityScripts.get().describeAttachable(le, arm)).append("\n    ")
							.append(describe(EntityScripts.get().attachableModel(le, arm, 1)));
					}
				}
			}
			return sb.toString();
		});
		LOGGER.info("Attachables: {}", state);
		String[][] views = {{"row", "33.5 100 0.5 180 10"}, {"close", "29.5 99.6 -2.5 180 5"}, {"side", "31.5 99.6 -4.5 90 5"}};
		for (String[] v : views) {
			server.runCommand("tp @p " + v[1]);
			context.waitTicks(10);
			context.takeScreenshot("lanature-attachables-" + v[0]);
			context.runOnClient(mc -> EntityScripts.get().setModels(false));
			context.waitTicks(2);
			context.takeScreenshot("lanature-attachables-" + v[0] + "-java");
			context.runOnClient(mc -> EntityScripts.get().setModels(true));
		}
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:torch");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:lantern");
		server.runCommand("tp @p 40.5 99 -4.5 0 10");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
		context.waitTicks(20);
		// the body turned to the head (after a teleport it lags up to 50 degrees behind)
		context.runOnClient(mc -> {
			mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot();
		});
		context.waitTicks(1);
		LOGGER.info("Player pose: {}\n  playing {}", context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1))),
			context.computeOnClient(mc -> EntityScripts.get().describePlaying(mc.player)));
		context.takeScreenshot("lanature-attachables-player");
		// the torch's flame rides the hand through a swing (an effect at a locator follows its posed bone)
		context.runOnClient(mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false));
		for (int k = 0; k < 6; k++) {
			String torch = context.computeOnClient(mc -> java.util.Arrays.stream(EntityScripts.get().describe(mc.player).split(", "))
				.filter(c -> c.startsWith("player.torch=")).findFirst().orElse("no torch controller"));
			LOGGER.info("Torch flame in a swing, tick {}: {}", k, torch);
			context.waitTicks(1);
		}
		context.waitTicks(10);
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-attachables-player-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		// from behind
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		context.waitTicks(2);
		context.takeScreenshot("lanature-attachables-player-back");
		context.runOnClient(mc -> EntityScripts.get().setModels(false));
		context.waitTicks(2);
		context.takeScreenshot("lanature-attachables-player-back-java");
		context.runOnClient(mc -> EntityScripts.get().setModels(true));
		// a spear held and charged in third person: the engine's spear variables pose the arm
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:iron_spear");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:air");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
		context.waitTicks(10);
		context.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
		context.waitTicks(1);
		String held = context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1)).lines()
			.filter(l -> l.trim().toLowerCase(java.util.Locale.ROOT).startsWith("rightarm") || l.trim().toLowerCase(java.util.Locale.ROOT).startsWith("rightitem")).toList().toString());
		LOGGER.info("Spear held: {}", held);
		context.takeScreenshot("lanature-attachables-player-spear");
		// held as a player holds it: Java releases an item in use the tick its use key is up
		context.getInput().holdKey(options -> options.keyUse);
		context.waitTicks(15);
		context.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
		String charged = context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1)).lines()
			.filter(l -> l.trim().toLowerCase(java.util.Locale.ROOT).startsWith("rightarm") || l.trim().toLowerCase(java.util.Locale.ROOT).startsWith("rightitem")).toList().toString());
		LOGGER.info("Spear charged 15 ticks: {}", charged);
		context.takeScreenshot("lanature-attachables-player-spear-charge");
		String chargedPlaying = context.computeOnClient(mc -> EntityScripts.get().describePlaying(mc.player));
		LOGGER.info("Spear charged 15 ticks, playing: {}", chargedPlaying);
		context.waitTicks(45);
		context.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
		String shaking = context.computeOnClient(mc -> EntityScripts.get().describePlaying(mc.player));
		LOGGER.info("Spear charged 60 ticks, playing: {}", shaking);
		context.takeScreenshot("lanature-attachables-player-spear-charge-60");
		context.getInput().releaseKey(options -> options.keyUse);
		// the charge unlocks a recipe; its toast would cover the first-person shots
		context.runOnClient(mc -> mc.gui.toastManager().clear());
		// an enchanted spear: vanilla's item_default render controller picks the glint material by v.is_enchanted
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:iron_spear[minecraft:enchantments={\"minecraft:sharpness\":1}]");
		context.waitTicks(10);
		context.takeScreenshot("lanature-attachables-player-spear-enchanted");
		// eating and drinking: the engine's use_item progress raises the right arm by vanilla's use_item_progress, raised
		// in the wait before the first bite, then shaken from bite to bite
		for (String food : new String[] {"golden_apple", "potion"}) {
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:" + food);
			context.waitTicks(5);
			context.getInput().holdKey(options -> options.keyUse);
			for (int t : new int[] {3, 7, 9}) {
				context.waitTicks(t == 3 ? 3 : t == 7 ? 4 : 2);
				context.runOnClient(mc -> mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot());
				int at = t;
				String using = context.computeOnClient(mc -> "used " + mc.player.getTicksUsingItem() + " ticks, "
					+ "startup " + EntityScripts.get().variable(mc.player, "use_item_startup_progress")
					+ ", interval " + EntityScripts.get().variable(mc.player, "use_item_interval_progress") + ", "
					+ String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1)).lines()
						.filter(l -> l.trim().toLowerCase(java.util.Locale.ROOT).startsWith("rightarm")).toList()
					+ "\n  playing " + EntityScripts.get().describePlaying(mc.player));
				LOGGER.info("Using {} ({}): {}", food, at, using);
			}
			context.takeScreenshot("lanature-attachables-player-" + food.replace('_', '-'));
			context.getInput().releaseKey(options -> options.keyUse);
		}
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:torch");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:lantern");
		// first person (Java draws hands only with the HUD shown): the torch and the lantern, then the lantern in the main
		// hand alone; then the inventory's paperdoll
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		context.runOnClient(mc -> {
			if (mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
		String[][] firstPerson = {{"air", "air"}, {"torch", "lantern"}, {"lantern", "air"}};
		for (String[] fp : firstPerson) {
			server.runCommand("item replace entity @p weapon.mainhand with minecraft:" + fp[0]);
			server.runCommand("item replace entity @p weapon.offhand with minecraft:" + fp[1]);
			context.waitTicks(20);
			LOGGER.info("First-person pose with {} and {}: {}\n  playing {}", fp[0], fp[1],
				context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1))),
				context.computeOnClient(mc -> EntityScripts.get().describePlaying(mc.player)));
			context.takeScreenshot("lanature-attachables-first-person-" + fp[0] + "-" + fp[1]);
			context.runOnClient(mc -> EntityScripts.get().setModels(false));
			context.waitTicks(2);
			context.takeScreenshot("lanature-attachables-first-person-" + fp[0] + "-" + fp[1] + "-java");
			context.runOnClient(mc -> EntityScripts.get().setModels(true));
		}
		// a swing with the spear: its attack controllers read the holder's attack time, c.owning_entity->v.attack_time
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:iron_spear");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:air");
		context.waitTicks(20);
		String beforeSwing = context.computeOnClient(mc -> String.valueOf(EntityScripts.get().describeAttachable(mc.player, net.minecraft.world.entity.HumanoidArm.RIGHT)));
		LOGGER.info("Spear before the swing: {}", beforeSwing);
		String fpArms = context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1)).lines()
			.filter(l -> l.trim().toLowerCase(java.util.Locale.ROOT).matches("(right|left)(arm|item).*")).toList().toString());
		LOGGER.info("Spear held in first person: {}", fpArms);
		context.takeScreenshot("lanature-attachables-first-person-spear");
		context.runOnClient(mc -> mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false));
		context.waitTicks(2);
		String swingTime = context.computeOnClient(mc -> String.valueOf(mc.player.getSwingAnimation(1)));
		String swinging = context.computeOnClient(mc -> String.valueOf(EntityScripts.get().describeAttachable(mc.player, net.minecraft.world.entity.HumanoidArm.RIGHT)));
		LOGGER.info("Spear swinging (attack time {}): {}", swingTime, swinging);
		context.takeScreenshot("lanature-attachables-first-person-spear-swing");
		// the empty hand with the view pitched and the head turned from the body: Bedrock's base pose turns the body, and
		// the arms on it, with the view, so the arm should stay where it is in the view straight ahead
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:air");
		context.waitTicks(20);
		float[][] turns = {{0, -45}, {0, 30}, {40, 0}, {-40, 0}};
		for (float[] turn : turns) {
			context.runOnClient(mc -> {
				mc.player.setXRot(turn[1]);
				mc.player.xRotO = turn[1];
				mc.player.yBodyRot = mc.player.yBodyRotO = mc.player.getYRot() - turn[0];
			});
			context.waitTicks(1);
			String name = "lanature-attachables-first-person-turned-" + (int) turn[0] + "-" + (int) turn[1];
			LOGGER.info("First-person turned {}: head {} body {} pitch {}{}", name, context.computeOnClient(mc -> mc.player.getYHeadRot()),
				context.computeOnClient(mc -> mc.player.yBodyRot), context.computeOnClient(mc -> mc.player.getXRot()),
				context.computeOnClient(mc -> String.valueOf(EntityScripts.get().humanoidPose(mc.player, 1))));
			context.takeScreenshot(name);
			context.runOnClient(mc -> EntityScripts.get().setModels(false));
			context.waitTicks(1);
			context.takeScreenshot(name + "-java");
			context.runOnClient(mc -> EntityScripts.get().setModels(true));
		}
		context.runOnClient(mc -> {
			mc.player.setXRot(0);
			mc.player.xRotO = 0;
		});
		// the paperdoll keeps Java's pose and held items
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:torch");
		server.runCommand("item replace entity @p weapon.offhand with minecraft:lantern");
		context.waitTicks(5);
		context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
		context.waitTicks(5);
		context.takeScreenshot("lanature-attachables-paperdoll");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
			}
		});
	}

	/**
	 * Item icons the pack redraws: every potion that has a Bedrock picture, drinkable, splash and lingering, in the
	 * inventory (the hotbar and the three rows above it), then the same with water and luck, which keep Java's bottle.
	 */
	private static void items(ClientGameTestContext context, TestServerContext server) {
		String[] potions = {"night_vision", "invisibility", "leaping", "fire_resistance", "swiftness", "slowness", "turtle_master",
			"water_breathing", "healing", "harming", "poison", "regeneration", "strength", "weakness", "slow_falling", "wind_charged",
			"weaving", "oozing", "infested", "water", "luck", "strong_healing", "long_poison", "awkward"};
		// twelve of each: drinkable the first twelve, splash the last twelve (water, luck and awkward among them),
		// lingering the middle twelve
		String[] items = {"potion", "splash_potion", "lingering_potion"};
		int[] first = {0, 12, 6};
		for (int row = 0; row < 3; row++) {
			for (int i = 0; i < 12; i++) {
				int slot = row * 12 + i;
				String target = slot < 9 ? "hotbar." + slot : "inventory." + (slot - 9);
				server.runCommand("item replace entity @p " + target + " with minecraft:" + items[row] + "[minecraft:potion_contents=\"minecraft:"
					+ potions[first[row] + i] + "\"]");
			}
		}
		context.waitTicks(5);
		context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(5);
		context.takeScreenshot("lanature-items-potions");
		context.setScreen(() -> null);
	}

	/**
	 * The pack's colour maps: in a swamp, a mangrove swamp and plains, a column each of oak, birch, spruce, mangrove and
	 * jungle leaves and a vine, with each block's tint logged beside Java's; then the same without the maps (Java's
	 * tints), and the icons of those and other block items Bedrock draws with carried textures or variations.
	 */
	private static void colormaps(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		String[] biomes = {"swamp", "mangrove_swamp", "plains"};
		String[] leaves = {"oak_leaves", "birch_leaves", "spruce_leaves", "mangrove_leaves", "jungle_leaves"};
		// there first, so the chunks the commands change are loaded (the server loads them over a few ticks)
		server.runCommand("gamerule fall_damage false");
		server.runCommand("tp @p 119.5 104 22.5 180 12");
		context.waitTicks(40);
		world.getConnection().waitForChunksRender();
		server.runCommand("fill 88 98 -12 152 98 24 minecraft:grass_block");
		server.runCommand("fill 88 99 -12 119 112 24 minecraft:air");
		server.runCommand("fill 120 99 -12 152 112 24 minecraft:air");
		for (int i = 0; i < biomes.length; i++) {
			int x0 = 100 + i * 20;
			server.runCommand("fillbiome " + (x0 - 10) + " 84 -16 " + (x0 + 9) + " 115 24 minecraft:" + biomes[i]);
			for (int l = 0; l < leaves.length; l++) {
				server.runCommand("fill " + (x0 - 3 + l) + " 99 -4 " + (x0 - 3 + l) + " 101 -4 minecraft:" + leaves[l] + "[persistent=true]");
			}
			server.runCommand("fill " + (x0 + 2) + " 99 -4 " + (x0 + 2) + " 101 -4 minecraft:stone");
			server.runCommand("setblock " + (x0 + 2) + " 100 -3 minecraft:vine[north=true]");
		}
		// onto the platform, which the player fell past before it was built (the world is superflat, far below)
		server.runCommand("tp @p 119.5 99 22.5 180 4");
		world.getConnection().waitForChunksRender();
		context.waitTicks(60);
		java.util.function.Supplier<String> tints = () -> context.computeOnClient(mc -> {
			net.minecraft.client.color.block.BlockColors java = net.minecraft.client.color.block.BlockColors.createDefault();
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < biomes.length; i++) {
				int x0 = 100 + i * 20;
				net.minecraft.core.BlockPos ground = new net.minecraft.core.BlockPos(x0, 98, -2);
				sb.append("\n  ").append(mc.level.getBiome(ground).getRegisteredName()).append(':');
				for (int l = 0; l <= leaves.length; l++) {
					net.minecraft.core.BlockPos pos = l < leaves.length ? new net.minecraft.core.BlockPos(x0 - 3 + l, 100, -4) : new net.minecraft.core.BlockPos(x0 + 2, 100, -3);
					net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
					net.minecraft.client.color.block.BlockTintSource tint = mc.getBlockColors().getTintSource(state, 0);
					net.minecraft.client.color.block.BlockTintSource javaTint = java.getTintSource(state, 0);
					sb.append(String.format(" %s %s (Java %s)", state.getBlock().builtInRegistryHolder().getRegisteredName().replace("minecraft:", ""),
						tint == null ? "untinted" : String.format("#%06X", tint.colorInWorld(state, mc.level, pos) & 0xFFFFFF),
						javaTint == null ? "untinted" : String.format("#%06X", javaTint.colorInWorld(state, mc.level, pos) & 0xFFFFFF)));
				}
			}
			return sb.toString();
		});
		LOGGER.info("Colour map view from {} looking at {}", context.computeOnClient(mc -> mc.player.position() + " rot " + mc.player.getYRot() + "/" + mc.player.getXRot()),
			context.computeOnClient(mc -> mc.hitResult == null ? "nothing" : mc.hitResult.getLocation() + " " + mc.hitResult.getType()));
		LOGGER.info("Colour maps {}; tints with them:{}", Colormaps.current().maps().keySet(), tints.get());
		context.takeScreenshot("lanature-colormaps");
		Colormaps.Loaded loaded = Colormaps.current();
		java.util.function.Consumer<Colormaps.Loaded> use = l -> context.runOnClient(mc -> {
			Colormaps.apply(l);
			BiomeLooks.bind(mc.level.registryAccess());
			mc.levelExtractor.allChanged();
		});
		use.accept(Colormaps.Loaded.EMPTY);
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		LOGGER.info("Tints without the colour maps:{}", tints.get());
		context.takeScreenshot("lanature-colormaps-java");
		use.accept(loaded);
		world.getConnection().waitForChunksRender();
		// the leaves and vine, then block items whose Bedrock icon is a carried texture or a first variation
		String[] icons = {"oak_leaves", "birch_leaves", "spruce_leaves", "mangrove_leaves", "jungle_leaves", "vine", "lily_pad", "brain_coral",
			"sunflower", "oak_planks", "brown_mushroom", "crimson_roots", "closed_eyeblossom", "pale_oak_planks", "tube_coral_fan", "spruce_log",
			"red_tulip", "moss_block", "dark_oak_leaves", "acacia_leaves", "warped_fungus", "pumpkin", "cobblestone", "oak_sapling"};
		for (int i = 0; i < icons.length; i++) {
			server.runCommand("item replace entity @p " + (i < 9 ? "hotbar." + i : "inventory." + (i - 9)) + " with minecraft:" + icons[i]);
		}
		context.waitTicks(5);
		context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(5);
		context.takeScreenshot("lanature-colormaps-items");
		context.setScreen(() -> null);
	}

	/**
	 * The pack's end portal colours (Java's end portal shader with the pack's layer colours) on a 3×3 portal and an end
	 * gateway, then its rain and snow sprites in Java's weather sheets: rain over plains, snow over snowy plains; then
	 * its water ({@link #water}).
	 */
	private static void environment(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("gamerule fall_damage false");
		server.runCommand("tp @p -0.5 101 20.5 180 30");
		context.waitTicks(40);
		world.getConnection().waitForChunksRender();
		server.runCommand("fill -6 98 6 6 98 24 minecraft:stone");
		server.runCommand("fill -6 99 6 6 112 24 minecraft:air");
		server.runCommand("fill -2 98 12 0 98 14 minecraft:end_portal");
		server.runCommand("setblock 3 99 13 minecraft:end_gateway");
		server.runCommand("tp @p -0.5 101 18.5 180 40");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot("lanature-environment-end-portal");
		server.runCommand("fillbiome -16 84 0 15 120 31 minecraft:plains");
		server.runCommand("tp @p -0.5 99 18.5 180 -10");
		server.runCommand("weather rain");
		context.waitTicks(200);
		context.takeScreenshot("lanature-environment-rain");
		server.runCommand("fillbiome -16 84 0 15 120 31 minecraft:snowy_plains");
		context.waitTicks(100);
		context.takeScreenshot("lanature-environment-snow");
		server.runCommand("weather clear");
		water(context, server, world);
	}

	/**
	 * The pack's water: its surface transparency over a striped pool floor in plains and in a swamp, the pack's then
	 * Java's (the biome looks unbound), and its water fog coming in under water, logged with the time under water.
	 */
	private static void water(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("fill -4 94 9 4 94 17 minecraft:white_wool");
		for (int x = -4; x <= 4; x += 2) {
			server.runCommand("fill " + x + " 94 9 " + x + " 94 17 minecraft:black_wool");
		}
		server.runCommand("fill -4 95 9 4 98 17 minecraft:water");
		// looking down at the pool's middle, once the rain has gone
		server.runCommand("tp @p -0.5 103 20.5 180 34");
		context.waitTicks(100);
		net.minecraft.core.BlockPos surface = new net.minecraft.core.BlockPos(0, 98, 13);
		for (String biome : java.util.List.of("plains", "swamp")) {
			server.runCommand("fillbiome -16 84 0 15 120 31 minecraft:" + biome);
			context.waitTicks(10);
			context.runOnClient(mc -> mc.levelExtractor.allChanged());
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);
			LOGGER.info("Water in {}: {}", biome, context.computeOnClient(mc -> {
				BiomeLooks.BiomeLook look = BiomeLooks.of(mc.level.getBiome(surface).value());
				int tint = WaterOpacity.apply(0xFFFFFFFF, mc.level, surface);
				return mc.level.getBiome(surface).getRegisteredName() + ", pack share " + (look == null ? null : look.waterOpacity()) + ", tint alpha "
					+ net.minecraft.util.ARGB.alpha(tint) + ", colour " + String.format("#%06X", mc.level.getBiome(surface).value().getWaterColor() & 0xFFFFFF);
			}));
			context.takeScreenshot("lanature-water-" + biome);
			BiomeLooks.Table looks = context.computeOnClient(mc -> BiomeLooks.table());
			context.runOnClient(mc -> {
				BiomeLooks.bind(new BiomeLooks.Table(java.util.Map.of(), null), mc.level.registryAccess());
				mc.levelExtractor.allChanged();
			});
			world.getConnection().waitForChunksRender();
			context.waitTicks(5);
			context.takeScreenshot("lanature-water-" + biome + "-java");
			context.runOnClient(mc -> {
				BiomeLooks.bind(looks, mc.level.registryAccess());
				mc.levelExtractor.allChanged();
			});
		}
		server.runCommand("fillbiome -16 84 0 15 120 31 minecraft:plains");
		context.waitTicks(10);
		context.runOnClient(mc -> mc.levelExtractor.allChanged());
		world.getConnection().waitForChunksRender();
		// under water, looking along the pool
		server.runCommand("tp @p 0.5 95 16.5 180 0");
		int waited = 0;
		for (int ticks : new int[] {20, 60, 100, 200, 300, 400, 600}) {
			context.waitTicks(ticks - waited);
			waited = ticks;
			LOGGER.info("Under water for {} ticks: {}", ticks, context.computeOnClient(mc -> {
				net.minecraft.client.renderer.fog.FogData f = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
				return String.format("vision time %d, Java vision %.3f, fog %.2f..%.2f, sky end %.2f, cloud end %.2f",
					((LocalPlayerAccessor) mc.player).lanature$waterVisionTime(), mc.player.getWaterVision(), f.environmentalStart, f.environmentalEnd,
					f.skyEnd, f.cloudEnd);
			}));
			if (ticks == 60 || ticks == 400) {
				context.takeScreenshot("lanature-water-fog-" + ticks / 20 + "s");
			}
		}
		server.runCommand("tp @p -0.5 103 20.5 180 34");
	}

	/** Breaks, cracks and item breaks drawn by the pack's effects from the blocks' and items' own pictures. */
	private static void pictures(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("gamerule fall_damage false");
		server.runCommand("tp @p -0.5 101 20.5 180 30");
		context.waitTicks(40);
		world.getConnection().waitForChunksRender();
		server.runCommand("fill -6 98 6 6 98 24 minecraft:stone");
		server.runCommand("fill -6 99 6 6 112 24 minecraft:air");
		String[] blocks = {"grass_block", "stone", "birch_leaves", "red_wool"};
		for (int i = 0; i < blocks.length; i++) {
			// a row to break, and behind it a row to crack
			server.runCommand("setblock " + (i * 2 - 4) + " 99 15 minecraft:" + blocks[i]);
			server.runCommand("setblock " + (i * 2 - 4) + " 99 12 minecraft:" + blocks[i]);
		}
		server.runCommand("tp @p -0.5 100.5 19.5 180 20");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		net.minecraft.world.level.block.state.BlockState[] broken = context.computeOnClient(mc -> {
			net.minecraft.world.level.block.state.BlockState[] out = new net.minecraft.world.level.block.state.BlockState[blocks.length];
			for (int i = 0; i < blocks.length; i++) {
				out[i] = mc.level.getBlockState(new net.minecraft.core.BlockPos(i * 2 - 4, 99, 15));
			}
			return out;
		});
		server.runCommand("fill -4 99 15 2 99 15 minecraft:air");
		context.waitTicks(2);
		context.runOnClient(mc -> {
			for (int i = 0; i < blocks.length; i++) {
				mc.level.addDestroyBlockEffect(new net.minecraft.core.BlockPos(i * 2 - 4, 99, 15), broken[i]);
				for (int k = 0; k < 8; k++) {
					mc.level.addBreakingBlockEffects(new net.minecraft.core.BlockPos(i * 2 - 4, 99, 12), net.minecraft.core.Direction.SOUTH, false);
				}
			}
			for (int k = 0; k < 16; k++) {
				mc.level.addParticle(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
					net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE))),
					-2.5, 100.2, 17, 0, 0.1, 0);
				mc.level.addParticle(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM,
					net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE))),
					1.5, 100.2, 17, 0, 0.1, 0);
			}
		});
		context.waitTicks(3);
		log(context, "after breaks");
		context.runOnClient(mc -> LOGGER.info("Picture effects: block_destruction {}, breaking_item_icon_air {}, breaking_item_terrain_air {}",
			ParticleSystem.get().particleCount("lnbc:block_destruction"), ParticleSystem.get().particleCount("lnbc:breaking_item_icon_air"),
			ParticleSystem.get().particleCount("lnbc:breaking_item_terrain_air")));
		context.takeScreenshot("lanature-pictures");
		context.waitTicks(10);
		context.takeScreenshot("lanature-pictures-later");
	}

	/** The effects the game's events start once in place of Java's bursts: a crit, a totem, a splash and a mace smash. */
	private static void events(ClientGameTestContext context, TestServerContext server, TestSingleplayerContext world) {
		server.runCommand("gamerule fall_damage false");
		server.runCommand("tp @p -0.5 101 20.5 180 30");
		context.waitTicks(40);
		world.getConnection().waitForChunksRender();
		server.runCommand("fill -8 98 4 8 98 24 minecraft:grass_block");
		server.runCommand("fill -8 99 4 8 112 24 minecraft:air");
		server.runCommand("summon minecraft:armor_stand -2.5 99 13.5 {NoGravity:1b,Tags:[\"lanature_target\"]}");
		server.runCommand("tp @p -0.5 100.5 19.5 180 15");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		server.runCommand("item replace entity @p weapon.mainhand with minecraft:air");
		world.getConnection().waitForChunksRender();
		context.waitTicks(20);
		String[] effects = {"minecraft:critical_hit_emitter", "minecraft:totem_particle", "minecraft:splash_spell_emitter", "minecraft:smash_ground_particle",
			"minecraft:smash_ground_particle_center", "minecraft:mobspell_emitter", "minecraft:mobspell_ambient", "minecraft:arrow_spell_emitter",
			"minecraft:mobspell_lingering"};
		java.util.function.Function<net.minecraft.client.Minecraft, String> counts = mc -> {
			StringBuilder b = new StringBuilder();
			for (String e : effects) {
				b.append(e).append(' ').append(ParticleSystem.get().emitterCount(e)).append('/').append(ParticleSystem.get().particleCount(e)).append("; ");
			}
			return b.toString();
		};
		context.runOnClient(mc -> {
			net.minecraft.world.entity.Entity stand = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
				new net.minecraft.world.phys.AABB(-10, 90, 0, 10, 110, 30)).getFirst();
			mc.particleEngine.createTrackingEmitter(stand, net.minecraft.core.particles.ParticleTypes.CRIT);
		});
		context.waitTicks(1);
		LOGGER.info("Event effects after a crit: {}", context.computeOnClient(counts::apply));
		context.takeScreenshot("lanature-events-crit");
		context.runOnClient(mc -> {
			net.minecraft.world.entity.Entity stand = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
				new net.minecraft.world.phys.AABB(-10, 90, 0, 10, 110, 30)).getFirst();
			mc.particleEngine.createTrackingEmitter(stand, net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, 30);
		});
		context.waitTicks(4);
		LOGGER.info("Event effects after a totem: {}", context.computeOnClient(counts::apply));
		context.takeScreenshot("lanature-events-totem");
		context.waitTicks(40);
		// the totem's effect follows its entity
		server.runCommand("tp @e[type=minecraft:armor_stand] 2.5 99 13.5");
		context.waitTicks(10);
		context.takeScreenshot("lanature-events-totem-moved");
		context.waitTicks(30);
		context.runOnClient(mc -> {
			mc.level.levelEvent(2002, new net.minecraft.core.BlockPos(1, 99, 14), 0xF82423);
			mc.level.levelEvent(2007, new net.minecraft.core.BlockPos(-3, 99, 11), 0x3F76E4);
		});
		context.waitTicks(4);
		LOGGER.info("Event effects after two splashes: {}", context.computeOnClient(counts::apply));
		context.takeScreenshot("lanature-events-splash");
		context.waitTicks(60);
		context.runOnClient(mc -> mc.level.levelEvent(2013, new net.minecraft.core.BlockPos(-1, 98, 12), 750));
		context.waitTicks(6);
		LOGGER.info("Event effects after a smash: {}", context.computeOnClient(counts::apply));
		context.takeScreenshot("lanature-events-smash");
		context.waitTicks(60);
		// spell sites: a status effect and an ambient one, tipped and spectral arrows in flight, a lingering cloud; a glowing fox's outline
		server.runCommand("kill @e[type=minecraft:armor_stand]");
		String[][] spells = {
			{"status", "summon minecraft:pig -1.5 99 14.5 {NoAI:1b,active_effects:[{id:\"minecraft:speed\",duration:2000,amplifier:0,show_particles:1b}]}"},
			{"ambient", "summon minecraft:pig -1.5 99 14.5 {NoAI:1b,active_effects:[{id:\"minecraft:jump_boost\",duration:2000,amplifier:0,ambient:1b,show_particles:1b}]}"},
			{"tipped", "summon minecraft:arrow -1.5 100.5 15.5 {NoGravity:1b,Motion:[0.0,0.0,0.0],item:{id:\"minecraft:tipped_arrow\",count:1,"
				+ "components:{\"minecraft:potion_contents\":{potion:\"minecraft:poison\"}}}}"},
			{"spectral", "summon minecraft:spectral_arrow 0.5 100.5 15.5 {NoGravity:1b,Motion:[0.0,0.0,0.0]}"},
			{"cloud", "summon minecraft:area_effect_cloud -0.5 99 14.5 {Radius:1.5f,Duration:2000,WaitTime:0,potion_contents:{potion:\"minecraft:regeneration\"}}"},
		};
		for (int i = 0; i < spells.length; i++) {
			if (i == 0 || !spells[i - 1][0].equals(spells[i][0])) {
				LOGGER.info("Event effects before the {} site: {}", spells[i][0], context.computeOnClient(counts::apply));
			}
			server.runCommand(spells[i][1]);
			if (i + 1 < spells.length && spells[i + 1][0].equals(spells[i][0])) {
				continue;
			}
			context.waitTicks(spells[i][0].equals("ambient") ? 100 : 40);
			LOGGER.info("Event effects with the {} site: {}; busiest {}", spells[i][0], context.computeOnClient(counts::apply),
				context.computeOnClient(mc -> ParticleSystem.get().busiest(12)));
			context.takeScreenshot("lanature-events-" + spells[i][0]);
			server.runCommand("kill @e[type=!minecraft:player]");
			context.waitTicks(40);
		}
		server.runCommand("summon minecraft:fox -1.5 99 15.5 {NoAI:1b,Glowing:1b}");
		context.waitTicks(10);
		context.takeScreenshot("lanature-events-glowing");
		// a warden digging into the grass (its pose set on the client, as the server's would sync it): the pack's digging
		// animation starts the dig effect, which reads the grass block's picture through q.surface_particle_*
		server.runCommand("kill @e[type=!minecraft:player]");
		// the killed fox's poof clears first
		context.waitTicks(40);
		server.runCommand("summon minecraft:warden -0.5 99 12.5 {NoAI:1b,Rotation:[0f,0f]}");
		context.waitTicks(20);
		context.runOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.warden.Warden.class,
			new net.minecraft.world.phys.AABB(-10, 90, 0, 10, 110, 30)).getFirst().setPose(net.minecraft.world.entity.Pose.DIGGING));
		context.waitTicks(10);
		String dig = context.computeOnClient(mc -> "warden_dig " + ParticleSystem.get().emitterCount("minecraft:warden_dig") + "/"
			+ ParticleSystem.get().particleCount("minecraft:warden_dig") + "; busiest " + ParticleSystem.get().busiest(6));
		LOGGER.info("Warden digging: {}", dig);
		context.takeScreenshot("lanature-events-warden-dig");
	}

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
