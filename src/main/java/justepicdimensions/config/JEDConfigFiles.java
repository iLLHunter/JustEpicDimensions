package justepicdimensions.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import justepicdimensions.definition.JEDDefinitionException;

/** Bounded TOML editor. Paths are chosen by the owning side, never supplied by a packet. */
public final class JEDConfigFiles {
    public record Document(Path path, String stamp, JsonObject values, boolean exists) {
        public JsonObject json() {
            var json = new JsonObject(); json.addProperty("path", path.toString()); json.addProperty("stamp", stamp);
            json.addProperty("exists", exists); json.add("values", values.deepCopy()); return json;
        }
    }
    public record Saved(Document document, Path backup) {}
    public static final int MAX_BYTES = 262144;
    private static final Map<Path,Object> LOCKS = new ConcurrentHashMap<>();
    private static Path safe(Path input) {
        Path path = input.toAbsolutePath().normalize();
        // The TOML itself must not be a link; the operator-chosen directories that contain it may be.
        if (Files.isSymbolicLink(path)) throw new JEDDefinitionException("Configuration symlinks are not supported: " + path);
        if (Files.exists(path) && !Files.isRegularFile(path)) throw new JEDDefinitionException("Not a configuration file: " + path);
        return path;
    }
    public static String stamp(Path input) {
        Path path = safe(input);
        try {
            if (!Files.exists(path)) return "missing";
            if (Files.size(path) > MAX_BYTES) throw new JEDDefinitionException("Configuration exceeds 256 KiB: " + path);
            return justepicdimensions.dimension.JEDDimensionSync.digest(Files.readAllBytes(path));
        } catch (IOException error) { throw new JEDDefinitionException("Cannot read configuration " + path, error); }
    }
    public static Document read(Path input, boolean client) {
        Path path = safe(input);
        synchronized (LOCKS.computeIfAbsent(path, p -> new Object())) {
            String before = stamp(path); var values = JEDConfigFields.defaults(client);
            if (!before.equals("missing")) try (var config = CommentedFileConfig.builder(path).sync().build()) {
                config.load();
                for (var field : JEDConfigFields.fields(client)) if (config.contains(field.key())) values.add(field.key(), new Gson().toJsonTree(config.get(field.key())));
            } catch (RuntimeException error) { throw new JEDDefinitionException("Cannot read TOML " + path + ": " + error.getMessage(), error); }
            if (!before.equals(stamp(path))) throw new JEDDefinitionException("Configuration changed while reading; refresh before editing");
            return new Document(path, before, values, !before.equals("missing"));
        }
    }
    /** Observe existing loader semantics without imposing screen file limits on world startup. */
    public static JsonObject capture(Path path, boolean client) {
        var values=JEDConfigFields.defaults(client);
        if(Files.exists(path))try(var config=CommentedFileConfig.builder(path).sync().build()){
            config.load();for(var field:JEDConfigFields.fields(client))if(config.contains(field.key()))values.add(field.key(),new Gson().toJsonTree(config.get(field.key())));
        }
        return values;
    }
    public static Saved save(Path input, String expected, JsonObject values, boolean client) {
        Path path = safe(input); JEDConfigFields.validate(values, client);
        synchronized (LOCKS.computeIfAbsent(path, p -> new Object())) {
            Path stage = null;
            try {
                if (!expected.equals(stamp(path))) throw new JEDDefinitionException("Configuration changed outside this editor. Your edits were kept; refresh before saving again.");
                Files.createDirectories(path.getParent()); safe(path);
                stage = Files.createTempFile(path.getParent(), ".jed-screen-", ".toml");
                if (Files.exists(path)) Files.copy(path, stage, StandardCopyOption.REPLACE_EXISTING);
                // NightConfig's default file config queues saves asynchronously. The staging file must
                // be fully written before force(), digest checks and the final rename.
                try (var config = CommentedFileConfig.builder(stage).sync().build()) {
                    config.load();
                    for (var field : JEDConfigFields.fields(client)) config.set(field.key(), field.validate(values.get(field.key())));
                    JEDConfigComments.populateMissing(config,client);
                    config.save();
                }
                if (Files.size(stage) > MAX_BYTES) throw new JEDDefinitionException("Edited configuration exceeds 256 KiB");
                try (var channel = FileChannel.open(stage, StandardOpenOption.WRITE)) { channel.force(true); }
                if (!expected.equals(stamp(path))) throw new JEDDefinitionException("Configuration changed while saving. Your edits were kept; refresh before saving again.");
                Path backup = null;
                if (Files.exists(path)) {
                    backup = path.resolveSibling(path.getFileName() + ".backup-" + UUID.randomUUID());
                    Files.copy(path, backup);
                    if (!expected.equals(stamp(path))) throw new JEDDefinitionException("Configuration changed while backing up; no edited file was installed");
                }
                try { Files.move(stage, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(stage, path, StandardCopyOption.REPLACE_EXISTING); }
                return new Saved(read(path, client), backup);
            } catch (IOException error) { throw new JEDDefinitionException("Cannot save configuration " + path + ": " + error.getMessage(), error); }
            finally { if (stage != null) try { Files.deleteIfExists(stage); } catch (IOException ignored) {} }
        }
    }
    private JEDConfigFiles() {}
}
