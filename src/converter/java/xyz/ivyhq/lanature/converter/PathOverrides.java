package xyz.ivyhq.lanature.converter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Pack textures that replace a vanilla Bedrock texture at the same path, written over the Java texture that is the
 * same picture. Entity textures are left to the entity port, since the pack draws most of them on its own geometry,
 * particle sheets to the particle runtime, potion pictures to {@link PotionModels} (Java has no texture per potion),
 * colour maps Java has no picture for to {@link Colormaps}, the end portal colours (constants of Java's shader) to
 * {@link EndPortalColors}, and the weather sprites (Java has whole sheets) to {@link WeatherSheets}.
 */
public final class PathOverrides {
	private static final List<String> FOLDERS = List.of("textures/items/", "textures/blocks/", "textures/environment/",
		"textures/colormap/", "textures/misc/", "textures/ui/");
	/** Bedrock's moon sheet is 4×2 phases; Java has one file per phase, in the same order. */
	/**
	 * Pictures of states Bedrock draws and Java has none of: the food bar's flashes as the bar drops (plain and under
	 * Hunger) and its blink. Java's food bar does not flash (it shakes when saturation runs out), so nothing in Java
	 * shows them.
	 */
	private static final Set<String> BEDROCK_ONLY = Set.of("textures/ui/hunger_blink", "textures/ui/hunger_flash_full", "textures/ui/hunger_flash_half",
		"textures/ui/hunger_effect_flash_full", "textures/ui/hunger_effect_flash_half");
	private static final List<String> MOON_PHASES = List.of("full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
		"new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous");

	private final BedrockPack pack;
	private final TextureIdentity identity;
	private final JavaAssets java;
	private final Path assets;
	private final Report report;

	public PathOverrides(BedrockPack pack, TextureIdentity identity, JavaAssets java, Path assets, Report report) {
		this.pack = pack;
		this.identity = identity;
		this.java = java;
		this.assets = assets;
		this.report = report;
	}

	public void run() throws IOException {
		int written = 0;
		for (String folder : FOLDERS) {
			for (String path : pack.packPaths(folder)) {
				String ext = path.substring(path.lastIndexOf('.'));
				if (!BedrockPack.TEXTURE_EXTENSIONS.contains(ext)) {
					continue;
				}
				String ref = path.substring(0, path.length() - ext.length());
				if (PotionModels.PREFIX.stream().anyMatch(ref::startsWith) || Colormaps.handles(identity, ref) || ref.equals(EndPortalColors.REF)
					|| ref.equals(WeatherSheets.REF)) {
					continue;
				}
				BedrockPack.Resolved winner = pack.texture(ref);
				if (winner == null || !winner.path().equals(path) || !(pack.vanilla.has(ref + ".png") || pack.vanilla.has(ref + ".tga"))) {
					continue; // a new texture, not a replacement (or shadowed by the other extension)
				}
				BufferedImage img = Images.decode(path, winner.bytes());
				if (ref.equals("textures/environment/moon_phases")) {
					written += moon(img);
					continue;
				}
				Set<String> javas = identity.javaFor(ref);
				if (BEDROCK_ONLY.contains(ref)) {
					report.info("overrides", ref + " is a state Java does not draw; unused");
					continue;
				}
				if (javas.isEmpty()) {
					report.warn("overrides", "no Java texture is the same picture as " + ref);
					continue;
				}
				for (String javaTex : javas) {
					BufferedImage vanilla = Images.decode(javaTex + ".png", java.texture(javaTex));
					if (vanilla.getWidth() * img.getHeight() != vanilla.getHeight() * img.getWidth()) {
						report.warn("overrides", ref + " is " + img.getWidth() + "x" + img.getHeight() + " but Java " + javaTex + " is "
							+ vanilla.getWidth() + "x" + vanilla.getHeight() + "; skipped");
						continue;
					}
					write("minecraft/textures/" + javaTex + ".png", img);
					written++;
				}
			}
		}
		report.info("overrides", written + " vanilla textures replaced");
	}

	private int moon(BufferedImage sheet) throws IOException {
		int w = sheet.getWidth() / 4;
		int h = sheet.getHeight() / 2;
		for (int i = 0; i < MOON_PHASES.size(); i++) {
			write("minecraft/textures/environment/celestial/moon/" + MOON_PHASES.get(i) + ".png", sheet.getSubimage((i % 4) * w, (i / 4) * h, w, h));
		}
		return MOON_PHASES.size();
	}

	private void write(String rel, BufferedImage img) throws IOException {
		Path out = assets.resolve(rel);
		Files.createDirectories(out.getParent());
		Files.write(out, Images.png(img));
	}
}
