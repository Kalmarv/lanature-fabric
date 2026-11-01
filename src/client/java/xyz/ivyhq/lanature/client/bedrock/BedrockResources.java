package xyz.ivyhq.lanature.client.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.ivyhq.lanature.client.entity.EntityDefinitions;
import xyz.ivyhq.lanature.client.entity.EntityScripts;
import xyz.ivyhq.lanature.client.environment.BiomeLooks;
import xyz.ivyhq.lanature.client.environment.Colormaps;
import xyz.ivyhq.lanature.client.model.EntityMaterial;
import xyz.ivyhq.lanature.client.particle.Material;
import xyz.ivyhq.lanature.client.particle.ParticleLoader;
import xyz.ivyhq.lanature.client.settings.PackSettings;

/**
 * Loads the converted Bedrock data from {@code assets/lanature/bedrock/} on every resource reload: the pack's
 * settings, the block-name, biome and item tables, the particle effects, client entities, biome looks (fogs and colours) and colour maps.
 */
public final class BedrockResources extends SimpleReloadListener<BedrockResources.Loaded> {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("lanature", "bedrock");
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/resources");
	private static final String ROOT = "bedrock";
	private static volatile Map<String, BiomeInfo> biomes = Map.of();
	private static volatile Map<String, ItemInfo> items = Map.of();

	public record BiomeInfo(String bedrock, Set<String> tags) {
	}

	/** A Java item's Bedrock item tags and cooldown type ({@code attack}, {@code use}, or null without one). */
	public record ItemInfo(Set<String> tags, @Nullable String cooldown) {
		static final ItemInfo NONE = new ItemInfo(Set.of(), null);
	}

	/** Everything read off-thread, applied on the client thread. */
	public record Loaded(JsonObject manifest, ParticleLoader.Result particles, EntityDefinitions entities, Map<String, BiomeInfo> biomes,
		Map<String, ItemInfo> items, BiomeLooks.Table looks, Colormaps.Loaded colormaps) {
	}

	/** Java biome id → its Bedrock biome and tags. */
	public static Map<String, BiomeInfo> biomes() {
		return biomes;
	}

	/** What a Java item's Bedrock item adds, as far as the converted data records it. */
	public static ItemInfo item(String javaItem) {
		return items.getOrDefault(javaItem, ItemInfo.NONE);
	}

	@Override
	protected Loaded prepare(PreparableReloadListener.SharedState state) {
		ResourceManager rm = state.resourceManager();
		JsonObject manifest = json(rm, ROOT + "/manifest.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject());
		Map<String, Set<BlockState>> blockNames = blockNames(json(rm, ROOT + "/block_names.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()));
		List<JsonObject> particleFiles = new ArrayList<>(files(rm, ROOT + "/vanilla/particles"));
		particleFiles.addAll(files(rm, ROOT + "/particles"));
		// vanilla's entity materials, the port's additions for mobs newer than them, then the pack's over both
		Map<String, EntityMaterial> entityMaterials = EntityMaterial.table(
			json(rm, ROOT + "/vanilla/materials/entity.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()),
			json(rm, ROOT + "/engine/entity.material.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()),
			json(rm, ROOT + "/materials/entity.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()));
		// the vanilla Bedrock files the pack relies on, then the pack's own over them
		EntityDefinitions.Files vanilla = entityFiles(rm, ROOT + "/vanilla/");
		// render controllers the engine defines itself, under vanilla's
		List<JsonObject> renderControllers = new ArrayList<>();
		json(rm, ROOT + "/engine/render_controllers.json").ifPresent(e -> renderControllers.add(e.getAsJsonObject()));
		renderControllers.addAll(vanilla.renderControllers());
		vanilla = new EntityDefinitions.Files(vanilla.entities(), vanilla.attachables(), vanilla.controllers(), vanilla.animations(), vanilla.models(),
			renderControllers);
		EntityDefinitions entities = EntityDefinitions.load(vanilla, entityFiles(rm, ROOT + "/"), entityMaterials,
			json(rm, ROOT + "/entity_scales.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()));
		// the engine's own particle materials, then the pack's materials over them
		Map<String, Material> materials = Material.table(
			json(rm, ROOT + "/engine/particles.material.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()),
			json(rm, ROOT + "/materials/particles.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()));
		LOGGER.info("Particle materials: {}", new TreeMap<>(materials));
		ParticleLoader.Result particles = ParticleLoader.load(particleFiles, blockNames, materials,
			json(rm, ROOT + "/triggers.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()),
			json(rm, ROOT + "/sound_events.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()));
		Map<String, BiomeInfo> biomeMap = new HashMap<>();
		json(rm, ROOT + "/biomes_java.json").ifPresent(b -> b.getAsJsonObject().entrySet().forEach(e -> {
			JsonObject o = e.getValue().getAsJsonObject();
			Set<String> tags = new HashSet<>();
			o.getAsJsonArray("tags").forEach(t -> tags.add(t.getAsString()));
			biomeMap.put(e.getKey(), new BiomeInfo(o.get("bedrock").getAsString(), Set.copyOf(tags)));
		}));
		Map<String, ItemInfo> itemMap = new HashMap<>();
		json(rm, ROOT + "/items.json").ifPresent(t -> t.getAsJsonObject().entrySet().forEach(e -> {
			JsonObject o = e.getValue().getAsJsonObject();
			Set<String> tags = new HashSet<>();
			if (o.has("tags")) {
				o.getAsJsonArray("tags").forEach(tag -> tags.add(tag.getAsString()));
			}
			itemMap.put(e.getKey(), new ItemInfo(Set.copyOf(tags), o.has("cooldown") ? o.get("cooldown").getAsString() : null));
		}));
		// vanilla fogs first, so the pack's own replace any of the same identifier
		List<JsonObject> fogFiles = new ArrayList<>(files(rm, ROOT + "/vanilla/fogs"));
		fogFiles.addAll(files(rm, ROOT + "/fogs"));
		Colormaps.Loaded colormaps = Colormaps.load(rm, json(rm, ROOT + "/colormaps.json").map(JsonElement::getAsJsonObject).orElse(null));
		BiomeLooks.Table looks = BiomeLooks.resolve(fogFiles, json(rm, ROOT + "/biomes_client.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()),
			files(rm, ROOT + "/biomes"), biomeMap, colormaps.biomeMaps(),
			BiomeLooks.VanillaWater.of(json(rm, ROOT + "/vanilla/biomes_client.json").map(JsonElement::getAsJsonObject).orElse(new JsonObject()),
				files(rm, ROOT + "/vanilla/biomes")));
		return new Loaded(manifest, particles, entities, Map.copyOf(biomeMap), Map.copyOf(itemMap), looks, colormaps);
	}

	@Override
	protected void apply(Loaded loaded, PreparableReloadListener.SharedState state) {
		PackSettings.get().define(loaded.manifest());
		biomes = loaded.biomes();
		items = loaded.items();
		loaded.particles().apply();
		EntityScripts.get().load(loaded.entities());
		ClientLevel level = Minecraft.getInstance().level;
		// the maps first: binding samples them for each biome
		Colormaps.apply(loaded.colormaps());
		BiomeLooks.bind(loaded.looks(), level == null ? null : level.registryAccess());
		EntityDefinitions e = loaded.entities();
		LOGGER.info("Loaded {} Bedrock particle effects, {} particle triggers, {} client entities ({} modelled: {}), {} attachables ({} modelled: {}; "
			+ "{} controllers, {} animations, {} render controllers), {} pack settings, {} biome looks, colour maps {}", loaded.particles().effects().size(),
			loaded.particles().triggers().size(), e.entities().size(), e.modelled().size(), e.modelled(), e.attachables().size(),
			e.modelledAttachables().size(), e.modelledAttachables(), e.controllers().size(), e.animations().size(), e.renderControllers().size(),
			PackSettings.get().all().size(), loaded.looks().byJavaId().size(), new TreeSet<>(loaded.colormaps().maps().keySet()));
	}

	/** Bedrock block name → the Java block states listed for it ({@code minecraft:water[level=0]} or a whole block). */
	private static Map<String, Set<BlockState>> blockNames(JsonObject table) {
		Map<String, Set<BlockState>> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : table.entrySet()) {
			Set<BlockState> states = new HashSet<>();
			e.getValue().getAsJsonArray().forEach(s -> states.addAll(states(s.getAsString())));
			out.put(e.getKey(), Set.copyOf(states));
		}
		return out;
	}

	static Collection<BlockState> states(String spec) {
		int bracket = spec.indexOf('[');
		Identifier id = Identifier.tryParse(bracket < 0 ? spec : spec.substring(0, bracket));
		Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
		if (block.isEmpty()) {
			return List.of();
		}
		List<BlockState> all = block.get().getStateDefinition().getPossibleStates();
		if (bracket < 0) {
			return all;
		}
		Map<String, String> wanted = new HashMap<>();
		for (String kv : spec.substring(bracket + 1, spec.length() - 1).split(",")) {
			int eq = kv.indexOf('=');
			if (eq > 0) {
				wanted.put(kv.substring(0, eq), kv.substring(eq + 1));
			}
		}
		return all.stream().filter(s -> wanted.entrySet().stream().allMatch(w -> {
			Property<?> p = s.getBlock().getStateDefinition().getProperty(w.getKey());
			return p != null && name(p, s).equals(w.getValue());
		})).toList();
	}

	private static <T extends Comparable<T>> String name(Property<T> p, BlockState s) {
		return p.getName(s.getValue(p));
	}

	private static EntityDefinitions.Files entityFiles(ResourceManager rm, String root) {
		return new EntityDefinitions.Files(files(rm, root + "entity"), files(rm, root + "attachables"), files(rm, root + "animation_controllers"),
			files(rm, root + "animations"), files(rm, root + "models"), files(rm, root + "render_controllers"));
	}

	/** Every JSON object under a folder, in path order (a sub-folder's files sort among the rest). */
	private static List<JsonObject> files(ResourceManager rm, String dir) {
		List<JsonObject> out = new ArrayList<>();
		new TreeMap<>(rm.listResources(dir, id -> id.getNamespace().equals("lanature") && id.getPath().endsWith(".json"))).forEach((id, res) -> {
			JsonElement e = read(id, res);
			if (e != null && e.isJsonObject()) {
				out.add(e.getAsJsonObject());
			}
		});
		return out;
	}

	private static Optional<JsonElement> json(ResourceManager rm, String path) {
		Identifier id = Identifier.fromNamespaceAndPath("lanature", path);
		return rm.getResource(id).map(res -> read(id, res));
	}

	private static JsonElement read(Identifier id, Resource res) {
		try (Reader r = res.openAsReader()) {
			return JsonParser.parseReader(r);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read {}", id, e);
			return null;
		}
	}
}
