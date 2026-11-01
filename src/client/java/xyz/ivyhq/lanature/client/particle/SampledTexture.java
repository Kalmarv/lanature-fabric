package xyz.ivyhq.lanature.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;

/**
 * A pack particle texture drawn on its own, with its material's sampler: Bedrock samples each particle texture alone,
 * so a linear material filters within it and clamps at its edges, which Java's particle atlas (sprites packed edge to
 * edge) cannot do without bleeding its neighbours in. Registered with the texture manager on first use, which reloads
 * it with the resources.
 */
final class SampledTexture extends SimpleTexture {
	private static final Set<Identifier> REGISTERED = ConcurrentHashMap.newKeySet();

	private final FilterMode filter;
	private final AddressMode wrap;

	private SampledTexture(Identifier file, FilterMode filter, AddressMode wrap) {
		super(file);
		this.filter = filter;
		this.wrap = wrap;
	}

	/**
	 * The texture to draw a particle-atlas sprite ({@code namespace:path}, the file {@code textures/particle/path.png})
	 * with a material's sampler, registered on first use (on the render thread).
	 */
	static Identifier of(Identifier sprite, Material material) {
		FilterMode filter = material.linear() ? FilterMode.LINEAR : FilterMode.NEAREST;
		AddressMode wrap = material.clamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		Identifier file = sprite.withPath(p -> "textures/particle/" + p + ".png");
		Identifier id = sprite.withPath(p -> "sampled/" + filter.name().toLowerCase(java.util.Locale.ROOT) + "_" + wrap.name().toLowerCase(java.util.Locale.ROOT) + "/" + p);
		if (REGISTERED.add(id)) {
			Minecraft.getInstance().getTextureManager().registerAndLoad(id, new SampledTexture(file, filter, wrap));
		}
		return id;
	}

	@Override
	protected void setSampler(TextureContents contents) {
		this.sampler = RenderSystem.getSamplerCache().getSampler(wrap, wrap, filter, filter, false);
	}
}
