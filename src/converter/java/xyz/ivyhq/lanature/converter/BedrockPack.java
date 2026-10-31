package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The resource layers Bedrock resolves a path through, highest priority first: the active subpack, the pack root,
 * then the vanilla resource pack. Texture references carry no extension; Bedrock tries {@code .png} then
 * {@code .tga}.
 */
public final class BedrockPack {
	public static final List<String> TEXTURE_EXTENSIONS = List.of(".png", ".tga");

	public final FileTree subpack;
	public final FileTree root;
	public final FileTree vanilla;
	private final List<FileTree> packLayers;
	private final List<FileTree> allLayers;

	public BedrockPack(FileTree subpack, FileTree root, FileTree vanilla) {
		this.subpack = subpack;
		this.root = root;
		this.vanilla = vanilla;
		this.packLayers = List.of(subpack, root);
		this.allLayers = List.of(subpack, root, vanilla);
	}

	/** The pack's own file (subpack over root), ignoring vanilla. */
	public byte[] packFile(String path) {
		for (FileTree layer : packLayers) {
			if (layer.has(path)) {
				return layer.get(path);
			}
		}
		return null;
	}

	public byte[] file(String path) {
		for (FileTree layer : allLayers) {
			if (layer.has(path)) {
				return layer.get(path);
			}
		}
		return null;
	}

	public JsonElement packJson(String path) {
		byte[] b = packFile(path);
		return b == null ? null : Json.parse(b);
	}

	/** Resolves an extensionless texture reference to the file path that wins, or null. */
	public Resolved texture(String ref) {
		for (FileTree layer : allLayers) {
			for (String ext : TEXTURE_EXTENSIONS) {
				if (layer.has(ref + ext)) {
					return new Resolved(ref, ref + ext, layer != vanilla, layer);
				}
			}
		}
		return null;
	}

	/** Paths under {@code prefix} in the pack layers (subpack and root), deduplicated. */
	public Set<String> packPaths(String prefix) {
		Set<String> out = new LinkedHashSet<>();
		for (FileTree layer : packLayers) {
			out.addAll(layer.paths(prefix));
		}
		return out;
	}

	public List<FileTree> packLayers() {
		return new ArrayList<>(packLayers);
	}

	public record Resolved(String ref, String path, boolean fromPack, FileTree layer) {
		public byte[] bytes() {
			return layer.get(path);
		}
	}
}
