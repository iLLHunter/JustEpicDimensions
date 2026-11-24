package justepicdimensions.definition;

import com.google.gson.JsonElement;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One preparation worker owns this bounded-by-current-inputs cache. Bytes are checked, never timestamps alone. */
public final class JEDDocumentCache {
    private record Entry(byte[] bytes, JsonElement json, boolean nativeRegistry) {}
    private final Map<String, Entry> entries = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private int hits, parses;
    public void begin() { seen.clear(); hits = parses = 0; }
    public JsonElement read(Reader reader, String source) throws IOException {
        return read(reader, source, false);
    }
    public JsonElement readNative(Reader reader, String source) throws IOException {
        return read(reader, source, true);
    }
    private JsonElement read(Reader reader, String source, boolean nativeRegistry) throws IOException {
        var text = new StringBuilder(); var buffer = new char[8192]; int count;
        while ((count = reader.read(buffer)) >= 0) { cancelled(); text.append(buffer, 0, count); }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8); seen.add(source);
        var previous = entries.get(source);
        if (previous != null && previous.nativeRegistry() == nativeRegistry && Arrays.equals(previous.bytes(), bytes)) { hits++; return previous.json().deepCopy(); }
        var json = nativeRegistry ? JEDJson.readNative(new StringReader(text.toString()), source) : JEDJson.read(new StringReader(text.toString()), source);
        entries.put(source, new Entry(bytes, json, nativeRegistry)); parses++; return json.deepCopy();
    }
    public void end() { entries.keySet().retainAll(seen); }
    public int hits() { return hits; }
    public int parses() { return parses; }
    public static void cancelled() { if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Dimension preparation interrupted"); }
}
