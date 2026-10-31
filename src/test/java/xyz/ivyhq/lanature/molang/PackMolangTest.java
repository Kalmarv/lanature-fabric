package xyz.ivyhq.lanature.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every Molang expression in the converted pack parses. Skipped when the pack has not been converted. */
class PackMolangTest {
	private static final Pattern MOLANG = Pattern.compile("(?i)\\b(q|query|v|variable|t|temp|math|c|context)\\.[a-z_]");
	/** Script arrays Bedrock runs as one program. */
	private static final Set<String> SCRIPTS = Set.of("initialize", "pre_animation", "on_entry", "on_exit");

	@Test
	void everyExpressionParses() throws IOException {
		String generated = System.getProperty("lanature.generated");
		Path root = generated == null ? null : Path.of(generated, "assets/lanature/bedrock");
		assumeTrue(root != null && Files.isDirectory(root), "pack not converted");
		List<String> failures = new ArrayList<>();
		int[] count = {0};
		try (Stream<Path> files = Files.walk(root)) {
			for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
				walk(JsonParser.parseString(Files.readString(p)), null, root.relativize(p).toString(), failures, count);
			}
		}
		failures.stream().limit(40).forEach(System.out::println);
		System.out.println(count[0] + " expressions, " + failures.size() + " failures");
		assertEquals(0, failures.size());
	}

	private static void walk(JsonElement e, String key, String file, List<String> failures, int[] count) {
		if (e.isJsonObject()) {
			for (var entry : e.getAsJsonObject().entrySet()) {
				walk(entry.getValue(), entry.getKey(), file, failures, count);
			}
		} else if (e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			if (key != null && SCRIPTS.contains(key) && a.asList().stream().allMatch(x -> x.isJsonPrimitive())) {
				List<String> lines = new ArrayList<>();
				a.forEach(x -> lines.add(x.getAsString()));
				check(String.join("\n", lines), file + " " + key, failures, count);
				return;
			}
			a.forEach(x -> walk(x, key, file, failures, count));
		} else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
			String s = e.getAsString();
			// Molang, not an identifier like "minecraft:x" (a ':' only counts inside a conditional)
			if (MOLANG.matcher(s).find() && (!s.contains(":") || s.contains("?"))) {
				check(s, file + " " + key, failures, count);
			}
		}
	}

	private static void check(String src, String where, List<String> failures, int[] count) {
		count[0]++;
		try {
			Molang.parseStrict(src);
		} catch (MolangException ex) {
			failures.add(where + ": " + ex.getMessage());
		}
	}
}
