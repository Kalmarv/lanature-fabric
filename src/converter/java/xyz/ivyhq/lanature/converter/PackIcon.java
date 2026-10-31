package xyz.ivyhq.lanature.converter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's icon ({@code assets/lanature/icon.png}, named by {@code fabric.mod.json}): the pack's own
 * {@code pack_icon.png}, the subpack's when it has one, scaled to {@value #SIZE} pixels square. The pack's is far
 * larger (1360 pixels) than a mod list draws it; halving in steps keeps the scaled image smooth.
 */
public final class PackIcon {
	private static final int SIZE = 256;

	private PackIcon() {
	}

	public static void run(BedrockPack pack, Path assets, Report report) throws IOException {
		byte[] bytes = pack.packFile("pack_icon.png");
		if (bytes == null) {
			report.warn("icon", "the pack has no pack_icon.png; the mod has no icon");
			return;
		}
		BufferedImage source = Images.decode("pack_icon.png", bytes);
		BufferedImage icon = scale(source, SIZE);
		Path out = assets.resolve("lanature/icon.png");
		Files.createDirectories(out.getParent());
		Files.write(out, Images.png(icon));
		report.info("icon", "pack_icon.png " + source.getWidth() + "x" + source.getHeight() + " -> icon.png " + SIZE + "x" + SIZE);
	}

	/** The image scaled to {@code size} square, halving with bilinear filtering while it is more than twice as large. */
	private static BufferedImage scale(BufferedImage img, int size) {
		BufferedImage current = img;
		int w = img.getWidth();
		int h = img.getHeight();
		while (w != size || h != size) {
			w = w / 2 >= size ? w / 2 : size;
			h = h / 2 >= size ? h / 2 : size;
			BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(current, 0, 0, w, h, null);
			g.dispose();
			current = next;
		}
		return current;
	}
}
