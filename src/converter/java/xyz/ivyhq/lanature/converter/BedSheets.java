package xyz.ivyhq.lanature.converter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pack's beds. Bedrock draws a bed as an entity, one 64×64 sheet per colour ({@code textures/entity/bed/<colour>});
 * Java draws it as two block models whose faces are textures cut from that same sheet layout: per colour the head's
 * and foot's top and sides and the foot's end, shared by every colour the underside and the head's end. Each Java
 * face is matched back to the vanilla Bedrock sheet (red), piece by piece: the largest connected run of its pixels
 * that equals the sheet under one of the 8 square orientations and an offset, then the next, down to
 * {@value #MIN_PIECE} pixels. A pack sheet then gives each face the pixels at the same places; pixels no piece covers
 * keep Java's. Only colours the pack retextures are written; the shared faces come from its white sheet (Bedrock's
 * default bed texture).
 */
public final class BedSheets {
	private static final int MIN_PIECE = 6;
	private static final String SHEETS = "textures/entity/bed/";
	/** Bedrock sheet name → Java colour. */
	private static final Map<String, String> COLOURS = new LinkedHashMap<>();
	private static final List<String> FACES = List.of("head_east", "head_west", "head_up", "foot_east", "foot_west", "foot_south", "foot_up");
	private static final List<String> SHARED = List.of("bed_down", "bed_head_north");

	static {
		for (String c : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown",
			"green", "red", "black")) {
			COLOURS.put(c, c);
		}
		COLOURS.put("silver", "light_gray");
	}

	private BedSheets() {
	}

	/** Where each covered pixel of a Java face lies on the sheet: {@code x + y * width} of the sheet, or -1. */
	private record Layout(int[] source) {
	}

	public static void run(BedrockPack pack, JavaAssets java, Path assets, Report report) throws IOException {
		if (!pack.packPaths(SHEETS).stream().anyMatch(p -> p.endsWith(".png") || p.endsWith(".tga"))) {
			return;
		}
		BufferedImage vanilla = vanillaSheet(pack, "red");
		if (vanilla == null) {
			report.warn("beds", "no vanilla Bedrock red bed sheet to match Java's bed faces against");
			return;
		}
		Map<String, Layout> layouts = new LinkedHashMap<>();
		for (String face : FACES) {
			layouts.put(face, layout(face, java, "block/red_bed_" + face, vanilla, report));
		}
		for (String face : SHARED) {
			layouts.put(face, layout(face, java, "block/" + face, vanilla, report));
		}
		int written = 0;
		for (Map.Entry<String, String> c : COLOURS.entrySet()) {
			BufferedImage sheet = packSheet(pack, c.getKey(), vanilla, report);
			if (sheet == null) {
				continue;
			}
			for (String face : FACES) {
				written += write(java, "block/" + c.getValue() + "_bed_" + face, layouts.get(face), sheet, assets);
			}
			if (c.getKey().equals("white")) {
				for (String face : SHARED) {
					written += write(java, "block/" + face, layouts.get(face), sheet, assets);
				}
			}
		}
		report.info("beds", written + " Java bed face textures cut from the pack's bed sheets");
	}

	private static BufferedImage vanillaSheet(BedrockPack pack, String name) throws IOException {
		for (String ext : BedrockPack.TEXTURE_EXTENSIONS) {
			byte[] b = pack.vanilla.get(SHEETS + name + ext);
			if (b != null) {
				return Images.decode(ext, b);
			}
		}
		return null;
	}

	/** The pack's sheet for a colour, or null when the pack leaves it vanilla or it does not fit the vanilla layout. */
	private static BufferedImage packSheet(BedrockPack pack, String name, BufferedImage vanilla, Report report) throws IOException {
		BedrockPack.Resolved r = pack.texture(SHEETS + name);
		if (r == null || !r.fromPack()) {
			return null;
		}
		BufferedImage sheet = Images.decode(r.path(), r.bytes());
		if (sheet.getWidth() != vanilla.getWidth() || sheet.getHeight() != vanilla.getHeight()) {
			report.warn("beds", r.path() + " is " + sheet.getWidth() + "x" + sheet.getHeight() + ", not the vanilla sheet's size; skipped");
			return null;
		}
		return sheet;
	}

	private static int write(JavaAssets java, String texture, Layout layout, BufferedImage sheet, Path assets) throws IOException {
		byte[] bytes = java.texture(texture);
		if (layout == null || bytes == null) {
			return 0;
		}
		BufferedImage face = Images.decode(texture + ".png", bytes);
		BufferedImage out = new BufferedImage(face.getWidth(), face.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < face.getHeight(); y++) {
			for (int x = 0; x < face.getWidth(); x++) {
				int s = layout.source()[x + y * face.getWidth()];
				out.setRGB(x, y, s < 0 ? face.getRGB(x, y) : sheet.getRGB(s % sheet.getWidth(), s / sheet.getWidth()));
			}
		}
		Path file = assets.resolve("minecraft/textures/" + texture + ".png");
		Files.createDirectories(file.getParent());
		Files.write(file, Images.png(out));
		return 1;
	}

	/** Matches a Java face back to the vanilla sheet; null when Java has no such texture. */
	private static Layout layout(String name, JavaAssets java, String texture, BufferedImage sheet, Report report) throws IOException {
		byte[] bytes = java.texture(texture);
		if (bytes == null) {
			report.warn("beds", "Java has no " + texture);
			return null;
		}
		BufferedImage face = Images.decode(texture + ".png", bytes);
		int n = face.getWidth();
		if (face.getHeight() != n) {
			report.warn("beds", texture + " is not square; skipped");
			return null;
		}
		int sw = sheet.getWidth();
		int sh = sheet.getHeight();
		int[] sheetPx = sheet.getRGB(0, 0, sw, sh, null, 0, sw);
		int[] facePx = face.getRGB(0, 0, n, n, null, 0, n);
		int[] source = new int[n * n];
		Arrays.fill(source, -1);
		int opaque = 0;
		for (int p : facePx) {
			opaque += p >>> 24 != 0 ? 1 : 0;
		}
		int covered = 0;
		int pieces = 0;
		while (true) {
			List<Integer> best = null;
			int bestOrient = 0;
			int bestDx = 0;
			int bestDy = 0;
			for (int o = 0; o < 8; o++) {
				for (int dx = -n + 1; dx < sw; dx++) {
					for (int dy = -n + 1; dy < sh; dy++) {
						Set<Integer> match = new HashSet<>();
						for (int i = 0; i < n * n; i++) {
							if (source[i] >= 0 || facePx[i] >>> 24 == 0) {
								continue;
							}
							int s = sheetIndex(i % n, i / n, o, n, dx, dy, sw, sh);
							if (s >= 0 && sheetPx[s] == facePx[i]) {
								match.add(i);
							}
						}
						if (match.size() < MIN_PIECE || best != null && match.size() <= best.size()) {
							continue;
						}
						List<Integer> piece = largestComponent(match, n);
						if (piece.size() >= MIN_PIECE && (best == null || piece.size() > best.size())) {
							best = piece;
							bestOrient = o;
							bestDx = dx;
							bestDy = dy;
						}
					}
				}
			}
			if (best == null) {
				break;
			}
			for (int i : best) {
				source[i] = sheetIndex(i % n, i / n, bestOrient, n, bestDx, bestDy, sw, sh);
			}
			covered += best.size();
			pieces++;
		}
		report.info("beds", name + ": " + covered + " of " + opaque + " pixels found on the sheet in " + pieces + " pieces");
		return new Layout(source);
	}

	/** The sheet index a face pixel lands on under orientation {@code o} (of the 8 of a square) and an offset; -1 off the sheet. */
	private static int sheetIndex(int x, int y, int o, int n, int dx, int dy, int sw, int sh) {
		int a;
		int b;
		switch (o) {
			case 0 -> { a = x; b = y; }
			case 1 -> { a = n - 1 - y; b = x; }
			case 2 -> { a = n - 1 - x; b = n - 1 - y; }
			case 3 -> { a = y; b = n - 1 - x; }
			case 4 -> { a = n - 1 - x; b = y; }
			case 5 -> { a = x; b = n - 1 - y; }
			case 6 -> { a = y; b = x; }
			default -> { a = n - 1 - y; b = n - 1 - x; }
		}
		int sx = a + dx;
		int sy = b + dy;
		return sx < 0 || sy < 0 || sx >= sw || sy >= sh ? -1 : sx + sy * sw;
	}

	private static List<Integer> largestComponent(Set<Integer> cells, int n) {
		Set<Integer> seen = new HashSet<>();
		List<Integer> best = List.of();
		for (int start : cells) {
			if (!seen.add(start)) {
				continue;
			}
			List<Integer> comp = new ArrayList<>();
			List<Integer> stack = new ArrayList<>(List.of(start));
			while (!stack.isEmpty()) {
				int c = stack.removeLast();
				comp.add(c);
				int x = c % n;
				int y = c / n;
				for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					int nx = x + d[0];
					int ny = y + d[1];
					int k = nx + ny * n;
					if (nx >= 0 && ny >= 0 && nx < n && ny < n && cells.contains(k) && seen.add(k)) {
						stack.add(k);
					}
				}
			}
			if (comp.size() > best.size()) {
				best = comp;
			}
		}
		return best;
	}
}
