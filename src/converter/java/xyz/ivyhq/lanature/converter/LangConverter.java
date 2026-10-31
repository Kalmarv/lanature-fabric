package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Bedrock {@code texts/*.lang} (root, then subpack) → Java {@code lang/*.json} under the lanature namespace. */
public final class LangConverter {
	private static final Map<String, String> LOCALES = Map.of("en_US", "en_us", "en_GB", "en_gb", "fr_FR", "fr_fr", "fr_CA", "fr_ca");

	private LangConverter() {
	}

	public static void run(BedrockPack pack, Path assets, Report report) throws IOException {
		for (Map.Entry<String, String> locale : LOCALES.entrySet()) {
			JsonObject out = new JsonObject();
			for (FileTree layer : new FileTree[] {pack.root, pack.subpack}) {
				byte[] b = layer.get("texts/" + locale.getKey() + ".lang");
				if (b != null) {
					parse(new String(b, StandardCharsets.UTF_8), out);
				}
			}
			if (!out.isEmpty()) {
				Json.write(assets.resolve("lanature/lang/" + locale.getValue().toLowerCase(Locale.ROOT) + ".json"), out);
				report.info("lang", locale.getValue() + ": " + out.size() + " strings");
			}
		}
	}

	/** {@code key=value}, with {@code ##} starting a comment and {@code \n} escapes kept as newlines. */
	static void parse(String text, JsonObject out) {
		for (String line : text.split("\r?\n")) {
			if (line.startsWith("﻿")) {
				line = line.substring(1);
			}
			int eq = line.indexOf('=');
			if (line.isBlank() || line.startsWith("#") || eq <= 0) {
				continue;
			}
			String value = line.substring(eq + 1);
			int comment = value.indexOf("##");
			if (comment >= 0) {
				value = value.substring(0, comment);
			}
			value = value.replaceAll("\\s+$", "").replace("\\n", "\n");
			out.addProperty(line.substring(0, eq).trim(), value);
		}
	}
}
