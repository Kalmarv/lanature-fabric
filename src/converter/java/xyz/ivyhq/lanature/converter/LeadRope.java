package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The pack's lead rope ({@code textures/entity/lead_rope}), as Java's two leash colours, written to
 * {@code bedrock/lead_rope.json} for the runtime.
 *
 * <p>Bedrock wraps its rope texture along a leash; vanilla's is 4×1, two texels of (127, 102, 76) and two of
 * (89, 71, 53). Java draws a leash in untextured segments alternating between exactly those two colours (0.5, 0.4, 0.3
 * and 0.7 times that), so Java's leash is vanilla's rope with one texture half per segment. The pack's rope is a
 * smooth twist rather than two flat halves, so (inferred, Bedrock's rope UV scale is not published) a light segment
 * takes the mean of the texture's first half and a dark segment the mean of its second; from vanilla's texture that
 * gives Java's own colours.
 */
public final class LeadRope {
	public static final String REF = "textures/entity/lead_rope";

	private LeadRope() {
	}

	public static void run(BedrockPack pack, Path assets, Report report) throws IOException {
		BedrockPack.Resolved r = pack.texture(REF);
		if (r == null || !r.fromPack()) {
			return;
		}
		BufferedImage rope = Images.decode(r.path(), r.bytes());
		int half = rope.getWidth() / 2;
		if (half == 0) {
			report.warn("lead", r.path() + " is narrower than 2 texels; leashes keep Java's colours");
			return;
		}
		JsonObject out = new JsonObject();
		out.add("light", mean(rope, 0, half));
		out.add("dark", mean(rope, half, rope.getWidth()));
		Json.write(assets.resolve("lanature/bedrock/lead_rope.json"), out);
		report.info("lead", "leash colours " + out.get("light") + " and " + out.get("dark") + " from " + r.path());
	}

	/** The mean colour (0–1 per channel) of columns {@code [from, to)} over every row. */
	private static JsonArray mean(BufferedImage img, int from, int to) {
		double[] sum = new double[3];
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = from; x < to; x++) {
				int argb = img.getRGB(x, y);
				sum[0] += argb >> 16 & 0xFF;
				sum[1] += argb >> 8 & 0xFF;
				sum[2] += argb & 0xFF;
				n++;
			}
		}
		JsonArray rgb = new JsonArray();
		for (double s : sum) {
			rgb.add(Math.round(s / n / 255.0 * 1000) / 1000.0);
		}
		return rgb;
	}
}
