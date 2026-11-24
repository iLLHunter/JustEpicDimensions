package justepicdimensions.definition;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Gson's tree parser accepts duplicate keys. Read the tree ourselves so mistakes have a location. */
public final class JEDJson {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    public static JsonElement read(Path file) {
        try (var reader = Files.newBufferedReader(file)) { return read(reader, file.toString()); }
        catch (IOException error) { throw new JEDDefinitionException("Cannot read " + file, error); }
    }

    public static JsonElement read(Reader input, String source) {
        try {
            var reader = new JsonReader(input);
            reader.setLenient(false);
            var value = value(reader, source);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JEDDefinitionException(source + ": trailing JSON data");
            return value;
        } catch (IOException | NumberFormatException error) {
            throw new JEDDefinitionException(source + ": invalid JSON: " + error.getMessage(), error);
        }
    }

    /** Native registry packs use RegistryDataLoader's parser, including its comment support. */
    public static JsonElement readNative(Reader input, String source) {
        try { return JsonParser.parseReader(input); }
        catch (JsonParseException error) { throw new JEDDefinitionException(source + ": invalid native datapack JSON: " + error.getMessage(), error); }
    }

    /** Iterative tree construction keeps malformed/deep network JSON off the Java call stack. */
    private static JsonElement value(JsonReader reader, String source) throws IOException {
        var parents = new ArrayDeque<JsonElement>(); JsonElement root = null;
        while (true) {
            if (!parents.isEmpty() && !reader.hasNext()) {
                if (parents.pop().isJsonObject()) reader.endObject(); else reader.endArray();
                if (parents.isEmpty()) return root;
                continue;
            }
            JsonElement parent = parents.peek(); String key = null;
            if (parent != null && parent.isJsonObject()) {
                key = reader.nextName();
                if (parent.getAsJsonObject().has(key)) throw new JEDDefinitionException(source + ": duplicate key " + reader.getPath());
            }
            JsonElement next = switch (reader.peek()) {
                case BEGIN_OBJECT -> { reader.beginObject(); yield new JsonObject(); }
                case BEGIN_ARRAY -> { reader.beginArray(); yield new JsonArray(); }
                case STRING -> new JsonPrimitive(reader.nextString());
                case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
                case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
                default -> throw new JEDDefinitionException(source + ": expected JSON value at " + reader.getPath());
            };
            if (parent == null) root = next;
            else if (parent.isJsonObject()) parent.getAsJsonObject().add(key, next);
            else parent.getAsJsonArray().add(next);
            if (next.isJsonObject() || next.isJsonArray()) parents.push(next);
            else if (parents.isEmpty()) return root;
        }
    }

    public static JsonObject object(JsonElement value, String source) {
        if (value == null || !value.isJsonObject()) throw new JEDDefinitionException(source + ": expected an object");
        return value.getAsJsonObject();
    }

    public static void keys(JsonObject value, Set<String> allowed, String source) {
        for (String key : value.keySet()) if (!allowed.contains(key))
            throw new JEDDefinitionException(source + ": unknown property '" + key + "'");
    }

    public static String string(JsonElement value, String source) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new JEDDefinitionException(source + ": expected a string");
        return value.getAsString();
    }

    public static int integer(JsonElement value, String source) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) { throw new JEDDefinitionException(source + ": expected an integer"); }
    }

    /** Stable object ordering for fingerprints; array order and numeric values remain significant. */
    public static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key -> sorted.add(key, canonical(value.getAsJsonObject().get(key))));
            return sorted;
        }
        if (value.isJsonArray()) {
            var array = new JsonArray(); value.getAsJsonArray().forEach(entry -> array.add(canonical(entry))); return array;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            return new JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        return value.deepCopy();
    }

    public static void writeAtomic(Path file, JsonElement value) {
        Path temporary = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".jed-", ".tmp");
            Files.writeString(temporary, PRETTY.toJson(value) + "\n");
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw new JEDDefinitionException("Cannot save " + file, error); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }

    private JEDJson() {}
}
