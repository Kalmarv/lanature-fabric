package xyz.ivyhq.lanature.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What the conversion did and what it could not convert, written beside the output. */
public final class Report {
	private final List<String> lines = new ArrayList<>();
	private final Map<String, Integer> warnings = new TreeMap<>();

	public synchronized void info(String step, String message) {
		lines.add("[" + step + "] " + message);
		System.out.println("[" + step + "] " + message);
	}

	public synchronized void warn(String step, String message) {
		lines.add("[" + step + "] WARN " + message);
		warnings.merge(step, 1, Integer::sum);
	}

	public void write(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		List<String> out = new ArrayList<>(lines);
		out.add("");
		warnings.forEach((k, v) -> out.add("warnings in " + k + ": " + v));
		Files.write(path, out);
		warnings.forEach((k, v) -> System.out.println("[" + k + "] " + v + " warnings (see " + path.getFileName() + ")"));
	}
}
