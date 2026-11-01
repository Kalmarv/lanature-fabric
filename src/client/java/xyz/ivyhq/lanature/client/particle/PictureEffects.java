package xyz.ivyhq.lanature.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import xyz.ivyhq.lanature.molang.Variables;

/**
 * The variables Bedrock's engine hands the effects that draw a block's or an item's own picture from its block or item
 * atlas ({@code atlas.terrain}, {@code atlas.items}): the picture's place in the atlas, and a colour. The same pictures
 * are in Java's atlases, so they get Java's UVs; the colour is the one Java gives the same particle (its terrain
 * particle's 0.6 grey times the block's tint).
 */
public final class PictureEffects {
	private static final String ITEM_ICON = "minecraft:breaking_item_icon";
	private static final String ITEM_TERRAIN = "minecraft:breaking_item_terrain";
	private static final ItemStackRenderState SCRATCH = new ItemStackRenderState();
	private static final RandomSource RANDOM = RandomSource.create();

	private PictureEffects() {
	}

	/** A block's picture as {@code block_destruct} and {@code block_slide} read it, with its count, spread and speed. */
	static Variables block(ClientLevel level, BlockPos pos, BlockState state, int count, double radius, double speed) {
		Variables v = new Variables();
		TextureAtlasSprite sprite = sprite(state);
		v.set("emitter_texture_coordinate", uv(sprite.getU0(), sprite.getV0()));
		v.set("emitter_texture_size", uv(sprite.getU1() - sprite.getU0(), sprite.getV1() - sprite.getV0()));
		v.set("emitter_particles_count", (double) count);
		v.set("emitter_radius", radius);
		v.set("velocity_scalar", speed);
		v.set("color", terrainColor(level, pos, state));
		return v;
	}

	/**
	 * {@code q.surface_particle_*}: the block an entity stands on, as {@link #surface(ClientLevel, BlockPos, BlockState)}
	 * gives it (the block Java's warden digs its particles from).
	 */
	public static Variables surface(Entity entity) {
		BlockPos pos = entity.getOnPos();
		return surface((ClientLevel) entity.level(), pos, entity.level().getBlockState(pos));
	}

	/** The ground block's picture as the mace's {@code smash_ground_particle} effects read it. */
	static Variables surface(ClientLevel level, BlockPos pos, BlockState state) {
		Variables v = new Variables();
		TextureAtlasSprite sprite = sprite(state);
		v.set("surface_particle_texture_coordinate", uv(sprite.getU0(), sprite.getV0()));
		v.set("surface_particle_texture_size", uv(sprite.getU1() - sprite.getU0(), sprite.getV1() - sprite.getV0()));
		v.set("surface_particle_tint", terrainColor(level, pos, state));
		return v;
	}

	/**
	 * The effect for a Java {@code item} particle and its variables: Bedrock's icon effect, or its terrain one for an
	 * item whose picture is in the block atlas (a block-drawn item), with that picture and one particle; null when the
	 * pack has no such effect.
	 */
	static @Nullable String item(ClientLevel level, ItemParticleOption options, Variables out) {
		Minecraft.getInstance().getItemModelResolver().updateForTopItem(SCRATCH, options.getItem().create(), ItemDisplayContext.GROUND, level, null, 0);
		Material.Baked material = SCRATCH.pickParticleMaterial(RANDOM);
		if (material == null) {
			return null;
		}
		TextureAtlasSprite sprite = material.sprite();
		String effect = sprite.atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS) ? ITEM_TERRAIN : ITEM_ICON;
		if (!ParticleSystem.get().hasEffect(effect)) {
			return null;
		}
		out.set("emittertexturecoord", uv(sprite.getU0(), sprite.getV0()));
		out.set("emittertexturesize", uv(sprite.getU1() - sprite.getU0(), sprite.getV1() - sprite.getV0()));
		out.set("num_particles", 1.0);
		out.set("emitter_radius", 0.0);
		out.set("size_modifier", 1.0);
		out.set("speed_modifier", 1.0);
		return effect;
	}

	private static TextureAtlasSprite sprite(BlockState state) {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
	}

	/** Java's terrain particle colour: 0.6 grey times the block's particle tint. */
	private static Variables.Struct terrainColor(ClientLevel level, BlockPos pos, BlockState state) {
		float r = 0.6F;
		float g = 0.6F;
		float b = 0.6F;
		BlockTintSource tint = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
		if (tint != null) {
			int col = tint.colorAsTerrainParticle(state, level, pos);
			r *= (col >> 16 & 0xFF) / 255.0F;
			g *= (col >> 8 & 0xFF) / 255.0F;
			b *= (col & 0xFF) / 255.0F;
		}
		Variables.Struct color = new Variables.Struct();
		color.set("r", (double) r);
		color.set("g", (double) g);
		color.set("b", (double) b);
		color.set("a", 1.0);
		return color;
	}

	private static Variables.Struct uv(float u, float v) {
		Variables.Struct s = new Variables.Struct();
		s.set("u", (double) u);
		s.set("v", (double) v);
		return s;
	}
}
