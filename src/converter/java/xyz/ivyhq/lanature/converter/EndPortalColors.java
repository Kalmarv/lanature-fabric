package xyz.ivyhq.lanature.converter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack's end portal colours ({@code textures/environment/end_portal_colors}), as Java's end portal shader's layer
 * colours.
 *
 * <p>Bedrock draws an end portal as 16 star planes, deepest first, added together; plane {@code L} (1 to 16) reads
 * the colour texture at the centre of cell {@code L - 1} of a 4×4 grid, point-sampled (UV 0.125, 0.375, 0.625, 0.875
 * across and down), and is darkened by its depth to {@code L / 16} (the plane and UV layout of the restored MCPE 1.0
 * renderer, and the published end portal shader: {@code star × colour × (1 - depth)}). A texture larger than 4×4
 * keeps those UVs, so an 8×8 one is read at its odd texels. Java's shader also adds its layers, each star texture
 * times a constant {@code COLORS[i]} that brightens with depth the same way, so layer {@code i} takes plane
 * {@code i + 1}'s colour times its brightness; Java's star textures and parallax, which the pack does not change,
 * stay. The shader is Java's own with its colour table replaced.
 */
public final class EndPortalColors {
	public static final String REF = "textures/environment/end_portal_colors";
	private static final String SHADER = "shaders/core/rendertype_end_portal.fsh";
	private static final Pattern COLORS = Pattern.compile("const vec3\\[\\] COLORS = vec3\\[\\]\\((.*?)\\);", Pattern.DOTALL);
	private static final int PLANES = 16;

	private EndPortalColors() {
	}

	public static void run(BedrockPack pack, JavaAssets java, Path assets, Report report) throws IOException {
		BedrockPack.Resolved palette = pack.texture(REF);
		if (palette == null || !palette.fromPack()) {
			return;
		}
		byte[] shader = java.tree().get(SHADER);
		Matcher m = shader == null ? null : COLORS.matcher(new String(shader, StandardCharsets.UTF_8));
		if (m == null || !m.find()) {
			report.warn("end portal", "Java's " + SHADER + " has no COLORS table to replace; the pack's end portal colours are not used");
			return;
		}
		BufferedImage img = Images.decode(palette.path(), palette.bytes());
		StringBuilder table = new StringBuilder();
		StringBuilder summary = new StringBuilder();
		for (int plane = 1; plane <= PLANES; plane++) {
			int x = (int) (((plane - 1) % 4 * 0.25 + 0.125) * img.getWidth());
			int y = (int) (((plane - 1) / 4 * 0.25 + 0.125) * img.getHeight());
			int rgb = img.getRGB(x, y);
			double brightness = plane / (double) PLANES;
			table.append(String.format(Locale.ROOT, "%n    vec3(%.6f, %.6f, %.6f)%s", (rgb >> 16 & 255) / 255.0 * brightness,
				(rgb >> 8 & 255) / 255.0 * brightness, (rgb & 255) / 255.0 * brightness, plane < PLANES ? "," : ""));
			summary.append(String.format(Locale.ROOT, " %06X", rgb & 0xFFFFFF));
		}
		String text = m.replaceFirst(Matcher.quoteReplacement("const vec3[] COLORS = vec3[](" + table + "\n);"));
		Path out = assets.resolve("minecraft/" + SHADER);
		Files.createDirectories(out.getParent());
		Files.writeString(out, text);
		report.info("end portal", img.getWidth() + "x" + img.getHeight() + " colours; planes 1-16 read" + summary);
	}
}
