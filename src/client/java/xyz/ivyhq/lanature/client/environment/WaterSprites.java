package xyz.ivyhq.lanature.client.environment;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An atlas sprite source ({@code lanature:alpha_normalised}, in {@code minecraft:atlases/blocks.json}) giving copies of
 * Java's water sprites whose alpha is divided by the water textures' top alpha (Java's 180 of 255), so that the copies
 * reach full opacity and the opacity is the tint's to give ({@link WaterOpacity}): Java's own at the top alpha, and
 * above it where the pack's water is more opaque than vanilla's. Animation and texture metadata are the originals'.
 */
public record WaterSprites(Map<Identifier, Identifier> textures) implements SpriteSource {
	private static final Logger LOGGER = LoggerFactory.getLogger("lanature/environment");
	public static final MapCodec<WaterSprites> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Codec.unboundedMap(Identifier.CODEC, Identifier.CODEC).fieldOf("textures").forGetter(WaterSprites::textures)).apply(i, WaterSprites::new));
	/** The highest alpha among the water textures at the last reload, 1 to 255. */
	private static volatile int topAlpha = 255;

	public static int topAlpha() {
		return topAlpha;
	}

	@Override
	public void run(ResourceManager manager, Output output) {
		int top = 0;
		for (Identifier texture : textures.keySet()) {
			Optional<Resource> resource = manager.getResource(TEXTURE_ID_CONVERTER.idToFile(texture));
			if (resource.isEmpty()) {
				continue;
			}
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				for (int pixel : image.getPixelsABGR()) {
					top = Math.max(top, pixel >>> 24);
				}
			} catch (IOException e) {
				LOGGER.error("Cannot read water texture {}", texture, e);
			}
		}
		int scale = top == 0 ? 255 : top;
		topAlpha = scale;
		textures.forEach((texture, copy) -> {
			Optional<Resource> resource = manager.getResource(TEXTURE_ID_CONVERTER.idToFile(texture));
			if (resource.isEmpty()) {
				LOGGER.warn("Missing water texture {}", texture);
				return;
			}
			output.add(copy, loader -> load(copy, resource.get(), scale));
		});
	}

	private static @Nullable SpriteContents load(Identifier id, Resource resource, int top) {
		Optional<AnimationMetadataSection> animation;
		Optional<TextureMetadataSection> texture;
		NativeImage scaled;
		try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
			animation = resource.metadata().getSection(AnimationMetadataSection.TYPE);
			texture = resource.metadata().getSection(TextureMetadataSection.TYPE);
			scaled = image.mappedCopy(argb -> ARGB.color(Math.min(255, Math.round(ARGB.alpha(argb) * 255f / top)), argb));
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Cannot copy water texture for {}", id, e);
			return null;
		}
		FrameSize frame = animation.map(a -> a.calculateFrameSize(scaled.getWidth(), scaled.getHeight()))
			.orElse(new FrameSize(scaled.getWidth(), scaled.getHeight()));
		if (!Mth.isMultipleOf(scaled.getWidth(), frame.width()) || !Mth.isMultipleOf(scaled.getHeight(), frame.height())) {
			LOGGER.error("Water texture {} is not a whole number of frames", id);
			scaled.close();
			return null;
		}
		return new SpriteContents(id, frame, scaled, animation, List.of(), texture);
	}

	@Override
	public MapCodec<WaterSprites> codec() {
		return MAP_CODEC;
	}
}
