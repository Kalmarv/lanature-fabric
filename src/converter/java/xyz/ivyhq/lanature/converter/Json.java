package xyz.ivyhq.lanature.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Bedrock pack JSON allows comments and trailing commas; this normalises it to strict JSON before parsing. */
public final class Json {
	public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
	private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");

	private Json() {
	}

	public static JsonElement parse(byte[] bytes) {
		return parse(new String(bytes, StandardCharsets.UTF_8));
	}

	public static JsonElement parse(String text) {
		if (!text.isEmpty() && text.charAt(0) == '﻿') {
			text = text.substring(1);
		}
		String strict = TRAILING_COMMA.matcher(stripComments(text)).replaceAll("$1");
		return JsonParser.parseString(strict);
	}

	/** Removes {@code //} and {@code /* *}{@code /} comments outside string literals. */
	static String stripComments(String text) {
		StringBuilder out = new StringBuilder(text.length());
		int n = text.length();
		boolean inString = false;
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			if (inString) {
				out.append(c);
				if (c == '\\' && i + 1 < n) {
					out.append(text.charAt(++i));
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
				out.append(c);
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
				int end = text.indexOf('\n', i);
				i = (end < 0 ? n : end) - 1;
			} else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 1;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	public static void write(Path path, JsonElement json) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, PRETTY.toJson(json) + "\n", StandardCharsets.UTF_8);
	}

	public static void writeCompact(Path path, JsonElement json) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, COMPACT.toJson(json), StandardCharsets.UTF_8);
	}
}
