package justepicdimensions.definition;

import com.google.gson.*;
import java.util.*;

/** No defaults are inserted here: absent config fields must not overwrite datapack values. */
public final class JEDDefinitionResolver {
    public record Conflict(String resource, String property, String previousSource, String previousValue,
                           String selectedSource, String selectedValue) {
        @Override public String toString() {
            return resource + " — " + property + ": " + previousSource + "=" + previousValue
                + "; " + selectedSource + "=" + selectedValue + " wins";
        }
    }
    public record Result(JEDSourceDocument document, List<Conflict> conflicts) {
        public Result { conflicts = List.copyOf(conflicts); }
    }

    public static Result overlay(String resource, JEDSourceDocument lower, JEDSourceDocument higher, boolean jedProperties) {
        if (higher == null) return new Result(lower, List.of());
        var conflicts = new ArrayList<Conflict>();
        var origins = new TreeMap<String, String>();
        if (lower == null) origins.put("", higher.origin(""));
        JsonElement base = lower == null ? new JsonObject() : lower.value();
        if (lower != null) origins.putAll(lower.origins());
        var patch = higher.value();
        if (jedProperties && patch.isJsonObject()) {
            var object = patch.getAsJsonObject();
            if (object.has("reset")) {
                if (!object.get("reset").isJsonArray()) throw new JEDDefinitionException(resource + ": reset must be an array of property paths");
                var seen = new HashSet<String>();
                for (var entry : object.getAsJsonArray("reset")) {
                    String path = JEDJson.string(entry, resource + ": reset");
                    if (!seen.add(path)) throw new JEDDefinitionException(resource + ": duplicate reset path " + path);
                    JEDDimensionDefinition.validateReset(path, resource);
                    if (find(object, path) != null) throw new JEDDefinitionException(resource + ": reset and value both supplied for " + path);
                    JsonElement old = remove(base, path);
                    if (old != null) conflicts.add(new Conflict(resource, path, lower.origin(path), old.toString(), higher.origin("reset"), "<default>"));
                    erase(origins, path);
                    origins.put(path, higher.origin("reset") + " (reset to default)");
                }
                object.remove("reset");
            }
        }
        JsonElement merged = merge(resource, "", base, patch, lower, higher, origins, conflicts, resourcePath(resource).startsWith("dimension/"));
        return new Result(new JEDSourceDocument(merged, origins), conflicts);
    }

    private static JsonElement merge(String resource, String path, JsonElement base, JsonElement patch,
                                     JEDSourceDocument lower, JEDSourceDocument higher, Map<String, String> origins,
                                     List<Conflict> conflicts, boolean dimensionStem) {
        if (base.isJsonObject() && patch.isJsonObject() && (dimensionStem || !implementationChanged(base.getAsJsonObject(), patch.getAsJsonObject()))) {
            var result = base.getAsJsonObject().deepCopy();
            for (var entry : patch.getAsJsonObject().entrySet()) {
                String child = JEDSourceDocument.child(path, entry.getKey());
                var old = result.get(entry.getKey());
                if (old == null) {
                    result.add(entry.getKey(), entry.getValue().deepCopy());
                    JEDSourceDocument.visit(entry.getValue(), child, higher.origin(child), origins);
                } else result.add(entry.getKey(), merge(resource, child, old, entry.getValue(), lower, higher, origins, conflicts,
                    resourcePath(resource).startsWith("worldgen/world_preset/") && path.equals("dimensions")));
            }
            return result;
        }
        if (!base.equals(patch) && lower != null)
            conflicts.add(new Conflict(resource, path.isEmpty() ? "$" : path, lower.origin(path), base.toString(), higher.origin(path), patch.toString()));
        erase(origins, path);
        JEDSourceDocument.visit(patch, path, higher.origin(path), origins);
        return patch.deepCopy();
    }

    private static boolean implementationChanged(JsonObject base, JsonObject patch) {
        return base.has("type") && patch.has("type") && !base.get("type").equals(patch.get("type"));
    }
    private static String resourcePath(String resource) { return resource.substring(resource.indexOf(':') + 1); }
    private static void erase(Map<String, String> origins, String path) {
        origins.keySet().removeIf(key -> path.isEmpty() || key.equals(path) || key.startsWith(path + "."));
    }
    private static JsonElement find(JsonElement root, String path) {
        JsonElement current = root;
        for (String key : path.split("\\.")) {
            if (!current.isJsonObject()) return null;
            current = current.getAsJsonObject().get(key);
            if (current == null) return null;
        }
        return current;
    }
    private static JsonElement remove(JsonElement root, String path) {
        int split = path.lastIndexOf('.');
        var parent = split < 0 ? root : find(root, path.substring(0, split));
        return parent != null && parent.isJsonObject() ? parent.getAsJsonObject().remove(path.substring(split + 1)) : null;
    }
    private JEDDefinitionResolver() {}
}
