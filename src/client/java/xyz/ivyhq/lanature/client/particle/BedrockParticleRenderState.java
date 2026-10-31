package xyz.ivyhq.lanature.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/**
 * Bedrock billboards for vanilla's quad particle renderer. Vanilla's state holds square quads; Bedrock's have their own
 * width, height and facing, so each quad is stored as its four camera-relative corners.
 */
public final class BedrockParticleRenderState extends QuadParticleRenderState {
	private static final int FLOATS = 20;
	private final Map<SingleQuadParticle.Layer, Storage> layers = new IdentityHashMap<>();
	private int count;

	void add(SingleQuadParticle.Layer layer, float[] corners, float u0, float v0, float u1, float v1, int color, int light) {
		layers.computeIfAbsent(layer, l -> new Storage()).add(corners, u0, v0, u1, v1, color, light);
		count++;
	}

	@Override
	public void clear() {
		layers.values().forEach(Storage::clear);
		count = 0;
	}

	@Override
	public boolean isEmpty() {
		return count == 0;
	}

	@Override
	public Set<SingleQuadParticle.Layer> layers() {
		return layers.keySet();
	}

	@Override
	public void buildLayer(SingleQuadParticle.Layer layer, VertexConsumer builder) {
		Storage s = layers.get(layer);
		if (s != null) {
			s.build(builder);
		}
	}

	@Override
	public void submit(SubmitNodeCollector collector, CameraRenderState camera) {
		if (count > 0) {
			collector.submitQuadParticleGroup(this);
		}
	}

	private static final class Storage {
		private float[] floats = new float[FLOATS * 256];
		private int[] ints = new int[2 * 256];
		private int n;

		/** {@code corners}: top-left, top-right, bottom-right, bottom-left, as x, y, z each. */
		void add(float[] c, float u0, float v0, float u1, float v1, int color, int light) {
			if ((n + 1) * FLOATS > floats.length) {
				floats = Arrays.copyOf(floats, floats.length * 2);
				ints = Arrays.copyOf(ints, ints.length * 2);
			}
			int i = n * FLOATS;
			System.arraycopy(c, 0, floats, i, 12);
			floats[i + 12] = u0;
			floats[i + 13] = v0;
			floats[i + 14] = u1;
			floats[i + 15] = v1;
			ints[n * 2] = color;
			ints[n * 2 + 1] = light;
			n++;
		}

		void build(VertexConsumer b) {
			for (int q = 0; q < n; q++) {
				int i = q * FLOATS;
				float u0 = floats[i + 12];
				float v0 = floats[i + 13];
				float u1 = floats[i + 14];
				float v1 = floats[i + 15];
				int color = ints[q * 2];
				int light = ints[q * 2 + 1];
				b.addVertex(floats[i], floats[i + 1], floats[i + 2]).setUv(u0, v0).setColor(color).setLight(light);
				b.addVertex(floats[i + 9], floats[i + 10], floats[i + 11]).setUv(u0, v1).setColor(color).setLight(light);
				b.addVertex(floats[i + 6], floats[i + 7], floats[i + 8]).setUv(u1, v1).setColor(color).setLight(light);
				b.addVertex(floats[i + 3], floats[i + 4], floats[i + 5]).setUv(u1, v0).setColor(color).setLight(light);
			}
		}

		void clear() {
			n = 0;
		}
	}
}
