package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Converts the La Nature .mcpack into Java resources.
 *
 * <pre>
 * --pack PATH            the .mcpack (zip) or its extracted folder
 * --refs DIR             reference checkouts from tools/fetch-refs.sh
 * --minecraft-jar PATH   the Minecraft client jar (vanilla Java assets)
 * --block-report PATH    Minecraft's generated reports/blocks.json (block state ids)
 * --asset-index PATH     Minecraft's asset index (the sound files Java ships)
 * --out DIR              output resource root (assets/ goes inside)
 * </pre>
 */
public final class Main {
	/** The subpack the conversion uses: "The La Nature experience" (the other is particles only). */
	static final String SUBPACK = "ln";

	public static void main(String[] args) throws IOException {
		Map<String, String> opts = new HashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2) {
			opts.put(args[i], args[i + 1]);
		}
		Path out = Path.of(require(opts, "--out"));
		Path refs = Path.of(require(opts, "--refs"));
		clean(out);
		Path assets = out.resolve("assets");
		Report report = new Report();

		FileTree packFile = FileTree.read(Path.of(require(opts, "--pack")));
		FileTree samples = FileTree.read(refs.resolve("bedrock-samples"));
		FileTree vanilla = samples.sub("resource_pack");
		BedrockPack pack = new BedrockPack(packFile.sub("subpacks/" + SUBPACK), packFile, vanilla);
		JavaAssets java = new JavaAssets(FileTree.read(Path.of(require(opts, "--minecraft-jar"))));
		JsonObject blockReport = Json.parse(Files.readAllBytes(Path.of(require(opts, "--block-report")))).getAsJsonObject();
		report.info("input", "pack " + packFile.paths().size() + " files; vanilla Bedrock " + vanilla.paths().size() + " files");

		TextureIdentity identity = new TextureIdentity(
			Json.parse(Files.readAllBytes(refs.resolve("pack-converter/converter/src/main/resources/mappings/textures.json"))).getAsJsonObject(),
			resource("texture_identity.json"), vanilla, java, report);
		GeyserBlocks geyser = new GeyserBlocks(refs.resolve("geyser-mappings/blocks.nbt"), blockReport);
		BedrockBlocks bedrockBlocks = new BedrockBlocks(
			Json.parse(vanilla.get("blocks.json")).getAsJsonObject(),
			merged(pack, "blocks.json"),
			Json.parse(vanilla.get("textures/terrain_texture.json")).getAsJsonObject().getAsJsonObject("texture_data"),
			mergedTiles(pack),
			report);
		TextureExporter textures = new TextureExporter(pack, assets, report);

		new BlockConverter(pack, bedrockBlocks, geyser, java, identity, textures,
			resource("blockshapes.json"), resource("legacy_data.json"), assets, report).run();

		new PathOverrides(pack, identity, java, assets, report).run();
		BindPoses bindPoses = new BindPoses(FileTree.read(refs.resolve("bedrock-samples-1.21.0.3")).sub("resource_pack/models"), report);
		RuntimeExporter runtime = new RuntimeExporter(pack, textures, bindPoses, assets, report);
		runtime.run();
		RuntimeExporter.vanillaEntityMaterials(refs.resolve("bridge-vanilla/static/vanilla/RP/materials/entity.material"), assets, report);
		BlockNames.run(geyser, runtime.blockNames(), assets, report);
		EntityScales.run(pack, samples.sub("behavior_pack/entities"), assets, report);
		new SoundConverter(pack, json(refs.resolve("geyser-mappings/sounds.json")),
			json(refs.resolve("pack-converter/converter/src/main/resources/mappings/sounds.json")),
			SoundConverter.javaSounds(json(Path.of(require(opts, "--asset-index")))), assets, report).run();
		LangConverter.run(pack, assets, report);
		PackIcon.run(pack, assets, report);
		Triggers.run(json(refs.resolve("geyser-mappings/particles.json")), resource("particle_events.json"), resource("trigger_skip.json"), packEffects(pack), assets, report);

		BiomeTags.run(samples, json(refs.resolve("geyser-mappings/biomes.json")), resource("biome_names.json"), assets, report);

		report.write(out.resolve("lanature-conversion-report.txt"));
	}

	/** A pack JSON file with the subpack's top-level keys over the root's. */
	static JsonObject merged(BedrockPack pack, String path) {
		JsonObject out = new JsonObject();
		for (FileTree layer : new FileTree[] {pack.root, pack.subpack}) {
			if (layer.has(path)) {
				Json.parse(layer.get(path)).getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
			}
		}
		out.remove("format_version");
		return out;
	}

	static JsonObject mergedTiles(BedrockPack pack) {
		JsonObject out = new JsonObject();
		for (FileTree layer : new FileTree[] {pack.root, pack.subpack}) {
			String path = "textures/terrain_texture.json";
			if (layer.has(path)) {
				Json.parse(layer.get(path)).getAsJsonObject().getAsJsonObject("texture_data").entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
			}
		}
		return out;
	}

	static JsonObject json(Path path) throws IOException {
		return Json.parse(Files.readAllBytes(path)).getAsJsonObject();
	}

	/** Identifiers of every particle effect the pack defines. */
	static java.util.Set<String> packEffects(BedrockPack pack) {
		java.util.Set<String> out = new java.util.HashSet<>();
		for (String path : pack.packPaths("particles/")) {
			if (path.endsWith(".json")) {
				out.add(Json.parse(pack.packFile(path)).getAsJsonObject().getAsJsonObject("particle_effect")
					.getAsJsonObject("description").get("identifier").getAsString());
			}
		}
		return out;
	}

	static JsonObject resource(String name) {
		try (InputStream in = Main.class.getResourceAsStream("/lanature/" + name)) {
			return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String require(Map<String, String> opts, String key) {
		String v = opts.get(key);
		if (v == null) {
			throw new IllegalArgumentException("missing " + key);
		}
		return v;
	}

	private static void clean(Path dir) throws IOException {
		if (Files.exists(dir)) {
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
					Files.delete(p);
				}
			}
		}
		Files.createDirectories(dir);
	}
}
