package xyz.ivyhq.lanature.converter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only view of a directory or zip by relative path (forward slashes). Zip contents are held in memory;
 * directory files are read on demand, so a large reference checkout costs only its file list.
 */
public final class FileTree {
	private final String name;
	private final NavigableMap<String, Object> files; // byte[] or Path

	private FileTree(String name, NavigableMap<String, Object> files) {
		this.name = name;
		this.files = files;
	}

	public static FileTree read(Path path) throws IOException {
		NavigableMap<String, Object> files = new TreeMap<>();
		if (Files.isDirectory(path)) {
			try (Stream<Path> walk = Files.walk(path)) {
				walk.filter(Files::isRegularFile).forEach(p -> files.put(path.relativize(p).toString().replace('\\', '/'), p));
			}
		} else {
			try (InputStream in = Files.newInputStream(path); ZipInputStream zip = new ZipInputStream(in)) {
				for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
					if (!e.isDirectory()) {
						files.put(e.getName().replace('\\', '/'), zip.readAllBytes());
					}
				}
			}
		}
		return new FileTree(path.getFileName().toString(), files);
	}

	/** The subtree under {@code prefix}, with paths relative to it. */
	public FileTree sub(String prefix) {
		String p = prefix.endsWith("/") ? prefix : prefix + "/";
		NavigableMap<String, Object> out = new TreeMap<>();
		files.subMap(p, true, p + Character.MAX_VALUE, true).forEach((k, v) -> out.put(k.substring(p.length()), v));
		return new FileTree(name + "/" + p, out);
	}

	public byte[] get(String path) {
		Object v = files.get(path);
		if (v instanceof Path p) {
			try {
				return Files.readAllBytes(p);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return (byte[]) v;
	}

	public boolean has(String path) {
		return files.containsKey(path);
	}

	/** Every path that starts with {@code prefix}, in order. */
	public NavigableSet<String> paths(String prefix) {
		return files.subMap(prefix, true, prefix + Character.MAX_VALUE, true).navigableKeySet();
	}

	public NavigableSet<String> paths() {
		return files.navigableKeySet();
	}

	public String name() {
		return name;
	}
}
