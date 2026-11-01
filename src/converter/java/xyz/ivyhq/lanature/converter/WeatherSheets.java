package xyz.ivyhq.lanature.converter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The pack's rain and snow sprites ({@code textures/environment/weather}) in Java's rain and snow sheets.
 *
 * <p>Bedrock draws precipitation as particles, each showing one of eight sprites from the weather sheet: snow from
 * the cells along its top eighth, rain from the cells an eighth wide and half the sheet tall below it (as the
 * community renderer cinnabar reads the sheet). Java scrolls two pictures of streaks and flakes ({@code rain.png},
 * {@code snow.png}) around the camera, 64 sheet pixels to the block both ways (a sheet is 4 blocks tall). Java's
 * renderer and layout stay, as the pack changes only the pictures: at each streak of Java's rain sheet one of the
 * pack's rain sprites, a whole cell as Bedrock draws it over a particle, half a block long (Bedrock's streak length
 * at its rain's speed); at each flake of Java's snow sheet one of its snow sprites, a fifth of a block (likewise).
 * The sheets are drawn at eight times Java's size, which keeps the pack's sprites near their own resolution.
 */
public final class WeatherSheets {
	public static final String REF = "textures/environment/weather";
	private static final int SPRITES = 8;
	private static final int SCALE = 8;
	/** Java sheet pixels to the block. */
	private static final int PIXELS_PER_BLOCK = 64;
	/** The length Bedrock draws a sprite over, in blocks: rain 0.5, snow 0.2. */
	private static final double RAIN_LENGTH = 0.5;
	private static final double SNOW_LENGTH = 0.2;

	private WeatherSheets() {
	}

	public static void run(BedrockPack pack, JavaAssets java, Path assets, Report report) throws IOException {
		BedrockPack.Resolved sheet = pack.texture(REF);
		if (sheet == null || !sheet.fromPack()) {
			return;
		}
		BufferedImage img = Images.decode(sheet.path(), sheet.bytes());
		int cell = img.getWidth() / SPRITES;
		List<BufferedImage> rain = new ArrayList<>();
		List<BufferedImage> snow = new ArrayList<>();
		for (int k = 0; k < SPRITES; k++) {
			snow.add(img.getSubimage(k * cell, 0, cell, cell));
			rain.add(img.getSubimage(k * cell, cell, cell, img.getHeight() / 2));
		}
		int streaks = draw(java, "rain", rain, true, RAIN_LENGTH, assets);
		int flakes = draw(java, "snow", snow, false, SNOW_LENGTH, assets);
		report.info("weather", "rain.png: " + streaks + " streaks drawn with the pack's rain sprites; snow.png: " + flakes + " flakes with its snow sprites");
	}

	/** Redraws one of Java's sheets, each mark of it as a pack sprite; the number of marks. */
	private static int draw(JavaAssets java, String name, List<BufferedImage> sprites, boolean streaks, double length, Path assets)
		throws IOException {
		BufferedImage source = ImageIO.read(new ByteArrayInputStream(java.texture("environment/" + name)));
		int w = source.getWidth();
		int h = source.getHeight();
		BufferedImage out = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		List<int[]> marks = streaks ? streaks(source) : flakes(source);
		for (int[] m : marks) {
			// a mark's box in sheet pixels (x, y, width, height), its sprite picked by position as Bedrock's by particle
			BufferedImage sprite = sprites.get(Math.floorMod(m[0] * 31 + m[1] * 17, SPRITES));
			int sh = (int) Math.round(length * PIXELS_PER_BLOCK * SCALE);
			int sw = sh * sprite.getWidth() / sprite.getHeight();
			int x = (m[0] * 2 + m[2]) * SCALE / 2 - sw / 2;
			int y = (m[1] * 2 + m[3]) * SCALE / 2 - sh / 2;
			// the sheet tiles: a sprite crossing an edge shows again at the other
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					g.drawImage(sprite, x + dx * out.getWidth(), y + dy * out.getHeight(), sw, sh, null);
				}
			}
		}
		g.dispose();
		Path file = assets.resolve("minecraft/textures/environment/" + name + ".png");
		Files.createDirectories(file.getParent());
		Files.write(file, Images.png(out));
		return marks.size();
	}

	/** Each vertical run of drawn pixels, the sheet wrapping top to bottom: {x, top, 1, length}. */
	private static List<int[]> streaks(BufferedImage img) {
		List<int[]> out = new ArrayList<>();
		int h = img.getHeight();
		for (int x = 0; x < img.getWidth(); x++) {
			// begin at a gap, so a run across the bottom edge stays whole
			int start = -1;
			for (int y = 0; y < h && start < 0; y++) {
				if (!drawn(img, x, y)) {
					start = y;
				}
			}
			if (start < 0) {
				out.add(new int[] {x, 0, 1, h});
				continue;
			}
			for (int i = 0; i < h; ) {
				int y = (start + i) % h;
				if (!drawn(img, x, y)) {
					i++;
					continue;
				}
				int length = 0;
				while (i < h && drawn(img, x, (start + i) % h)) {
					length++;
					i++;
				}
				out.add(new int[] {x, y, 1, length});
			}
		}
		return out;
	}

	/** Each 8-connected group of drawn pixels: its bounding box {x, y, width, height}. */
	private static List<int[]> flakes(BufferedImage img) {
		int w = img.getWidth();
		int h = img.getHeight();
		boolean[] seen = new boolean[w * h];
		List<int[]> out = new ArrayList<>();
		for (int y0 = 0; y0 < h; y0++) {
			for (int x0 = 0; x0 < w; x0++) {
				if (seen[y0 * w + x0] || !drawn(img, x0, y0)) {
					continue;
				}
				int minX = x0, minY = y0, maxX = x0, maxY = y0;
				ArrayDeque<int[]> queue = new ArrayDeque<>();
				queue.add(new int[] {x0, y0});
				seen[y0 * w + x0] = true;
				while (!queue.isEmpty()) {
					int[] p = queue.poll();
					minX = Math.min(minX, p[0]);
					maxX = Math.max(maxX, p[0]);
					minY = Math.min(minY, p[1]);
					maxY = Math.max(maxY, p[1]);
					for (int dy = -1; dy <= 1; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							int x = p[0] + dx;
							int y = p[1] + dy;
							if (x >= 0 && y >= 0 && x < w && y < h && !seen[y * w + x] && drawn(img, x, y)) {
								seen[y * w + x] = true;
								queue.add(new int[] {x, y});
							}
						}
					}
				}
				out.add(new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1});
			}
		}
		return out;
	}

	private static boolean drawn(BufferedImage img, int x, int y) {
		return (img.getRGB(x, y) >>> 24) != 0;
	}
}
