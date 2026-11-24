package justepicdimensions.definition;

import com.google.gson.JsonElement;
import java.util.*;

/** The resource tree and its field origins travel together until decoding finishes. */
public final class JEDSourceDocument {
    private final JsonElement value;
    private final Map<String, String> origins;

    public JEDSourceDocument(JsonElement value, String source) {
        this.value = value.deepCopy();
        var fields = new TreeMap<String, String>();
        visit(this.value, "", source, fields);
        origins = Map.copyOf(fields);
    }

    public JEDSourceDocument(JsonElement value, Map<String, String> origins) {
        this.value = value.deepCopy(); this.origins = Map.copyOf(origins);
    }

    static void visit(JsonElement value, String path, String source, Map<String, String> fields) {
        fields.put(path, source);
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry ->
            visit(entry.getValue(), child(path, entry.getKey()), source, fields));
    }

    static String child(String path, String key) { return path.isEmpty() ? key : path + "." + key; }
    public JsonElement value() { return value.deepCopy(); }
    public Map<String, String> origins() { return origins; }
    public String origin(String path) {
        String key = path;
        while (!origins.containsKey(key) && key.contains(".")) key = key.substring(0, key.lastIndexOf('.'));
        return origins.getOrDefault(key, origins.getOrDefault("", "unknown source"));
    }
}
