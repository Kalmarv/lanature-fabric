package xyz.ivyhq.lanature.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Restores the {@code bind_pose_rotation} of vanilla geometry bones that the current samples lost.
 *
 * <p>Mojang's samples re-exported several legacy entity geometries (format 1.8 and 1.10) as format 1.21.0 without
 * their bones' bind pose rotations: the fox's and cat's bodies (90° about X), the parrot's body (28.287°). The game's
 * own animations still assume them (the fox's sitting animation turns its body by -60° to reach the 30° of Java's
 * sitting fox; {@code "-this"} in the fox's and parrot's setup animations cancels only animated rotation), so drawn
 * without them those bodies stand upright. The last release carrying them is v1.21.0.3. A bone gets the old bind pose
 * when the current bone has neither a rotation nor a bind pose and its cubes are unchanged (same origins, sizes and
 * UV), so a remodelled bone is never given another model's pose.
 */
public final class BindPoses {
	/** Geometry identifier → bone name → that bone in the old samples. */
	private final Map<String, Map<String, JsonObject>> legacy = new HashMap<>();
	private final Report report;

	BindPoses(FileTree legacyModels, Report report) {
		this.report = report;
		for (String path : legacyModels.paths()) {
			if (!path.endsWith(".json")) {
				continue;
			}
			JsonElement json;
			try {
				json = Json.parse(legacyModels.get(path));
			} catch (RuntimeException e) {
				continue;
			}
			if (!json.isJsonObject()) {
				continue;
			}
			JsonObject o = json.getAsJsonObject();
			if (o.has("minecraft:geometry") && o.get("minecraft:geometry").isJsonArray()) {
				for (JsonElement g : o.getAsJsonArray("minecraft:geometry")) {
					add(g.getAsJsonObject().getAsJsonObject("description").get("identifier").getAsString(), g.getAsJsonObject());
				}
			}
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
					add(e.getKey().split(":", 2)[0], e.getValue().getAsJsonObject());
				}
			}
		}
	}

	private void add(String id, JsonObject geometry) {
		Map<String, JsonObject> bones = new HashMap<>();
		if (geometry.has("bones") && geometry.get("bones").isJsonArray()) {
			for (JsonElement b : geometry.getAsJsonArray("bones")) {
				if (b.isJsonObject() && b.getAsJsonObject().has("name")) {
					bones.put(b.getAsJsonObject().get("name").getAsString(), b.getAsJsonObject());
				}
			}
		}
		legacy.put(id, bones);
	}

	/** Restores lost bind poses in a vanilla geometry file, in place; {@code path} names it in the report. */
	void restore(String path, JsonElement file) {
		if (!file.isJsonObject() || !file.getAsJsonObject().has("minecraft:geometry")) {
			return;
		}
		for (JsonElement g : file.getAsJsonObject().getAsJsonArray("minecraft:geometry")) {
			JsonObject geometry = g.getAsJsonObject();
			String id = geometry.getAsJsonObject("description").get("identifier").getAsString();
			Map<String, JsonObject> old = legacy.get(id);
			if (old == null || !geometry.has("bones")) {
				continue;
			}
			for (JsonElement b : geometry.getAsJsonArray("bones")) {
				JsonObject bone = b.getAsJsonObject();
				JsonObject before = old.get(bone.get("name").getAsString());
				if (before == null || bone.has("rotation") || bone.has("bind_pose_rotation") || !before.has("bind_pose_rotation")) {
					continue;
				}
				JsonArray pose = before.getAsJsonArray("bind_pose_rotation");
				if (isZero(pose) || !sameCubes(bone, before)) {
					continue;
				}
				bone.add("bind_pose_rotation", pose.deepCopy());
				report.info("runtime", "vanilla " + path + ": " + id + " bone " + bone.get("name").getAsString() + " gets its bind pose " + pose + " from v1.21.0.3");
			}
		}
	}

	private static boolean isZero(JsonArray a) {
		for (JsonElement e : a) {
			if (e.getAsDouble() != 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameCubes(JsonObject a, JsonObject b) {
		JsonArray ca = a.has("cubes") ? a.getAsJsonArray("cubes") : new JsonArray();
		JsonArray cb = b.has("cubes") ? b.getAsJsonArray("cubes") : new JsonArray();
		if (ca.size() != cb.size()) {
			return false;
		}
		for (int i = 0; i < ca.size(); i++) {
			JsonObject x = ca.get(i).getAsJsonObject();
			JsonObject y = cb.get(i).getAsJsonObject();
			for (String key : new String[] {"origin", "size", "uv"}) {
				if (!numbers(x.get(key)).equals(numbers(y.get(key)))) {
					return false;
				}
			}
		}
		return true;
	}

	/** A value's numbers as text, so 1 and 1.0 compare equal. */
	private static String numbers(JsonElement e) {
		if (e == null) {
			return "";
		}
		if (e.isJsonArray()) {
			StringBuilder sb = new StringBuilder();
			e.getAsJsonArray().forEach(x -> sb.append(numbers(x)).append(','));
			return sb.toString();
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
			return Double.toString(e.getAsDouble());
		}
		return e.toString();
	}
}
