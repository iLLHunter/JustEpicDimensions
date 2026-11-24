package justepicdimensions.showcase;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Opt-in installation before native dimension discovery; authored files always win. */
public final class JEDShowcaseInstaller {
    private static final String ARCHIVE = "/justepicdimensions/showcase/showcase.zip";
    private static final int FILE_LIMIT = 512, BYTE_LIMIT = 64 * 1024 * 1024, ENTRY_LIMIT = 4 * 1024 * 1024;
    public static synchronized int install(Path root, boolean enabled) {
        if (!enabled) return 0;
        root = root.toAbsolutePath().normalize();
        try (var guard = justepicdimensions.builder.JEDBuilderFiles.guard(root);
             var input = JEDShowcaseInstaller.class.getResourceAsStream(ARCHIVE)) {
            if (input == null) throw new IOException("Bundled showcase is missing");
            return unpack(root, input);
        } catch (IOException error) { throw new JEDDefinitionException("Cannot install the JED showcase into " + root + ": " + error.getMessage(), error); }
    }
    /** Public for archive/path failure contracts; only the bundled archive is used by startup. */
    public static int unpack(Path root, InputStream archive) throws IOException {
        root = root.toAbsolutePath().normalize();
        safe(root, root, true);
        var entries = new LinkedHashMap<Path,byte[]>(); int total = 0, count = 0;
        try (var zip = new ZipInputStream(archive)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++count > FILE_LIMIT) throw new IOException("Showcase has too many entries");
                String name = entry.getName();
                if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || !name.startsWith("data/") || name.contains("//")) throw new IOException("Invalid showcase path: " + name);
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root.resolve("data")) || (target.equals(root.resolve("data")) && !entry.isDirectory())) throw new IOException("Showcase path escapes data: " + name);
                for (Path part : Path.of(name)) if (part.toString().equals("..") || part.toString().equals(".")) throw new IOException("Invalid showcase path: " + name);
                safe(root, target, entry.isDirectory());
                if (entry.isDirectory()) continue;
                byte[] bytes = zip.readNBytes(ENTRY_LIMIT + 1);
                if (bytes.length > ENTRY_LIMIT || (total += bytes.length) > BYTE_LIMIT) throw new IOException("Showcase archive exceeds its size limit");
                if (entries.putIfAbsent(target, bytes) != null) throw new IOException("Duplicate showcase path: " + name);
            }
        }
        if (entries.isEmpty()) throw new IOException("Showcase archive has no files");
        var created = new ArrayList<Path>();
        try {
            for (var entry : entries.entrySet()) {
                Path target = entry.getKey(); safe(root, target, false);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.createDirectories(target.getParent()); safe(root, target, false);
                Path stage = Files.createTempFile(target.getParent(), ".jed-showcase-", ".tmp");
                try {
                    Files.write(stage, entry.getValue());
                    // A non-replacing move preserves a file created by another writer meanwhile.
                    try { Files.move(stage, target); created.add(target); }
                    catch (FileAlreadyExistsException ignored) { safe(root, target, false); }
                } finally { Files.deleteIfExists(stage); }
            }
        } catch (IOException | RuntimeException error) {
            for (Path path : created.reversed()) try {
                if (Arrays.equals(Files.readAllBytes(path), entries.get(path))) Files.delete(path);
            } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        if (!created.isEmpty()) com.mojang.logging.LogUtils.getLogger().info("JED installed {} missing showcase files into {}", created.size(), root.resolve("data"));
        return created.size();
    }
    /** Links are rejected below the configuration root; the root and its ancestors are operator-chosen and may be links. */
    private static void safe(Path root, Path path, boolean directory) throws IOException {
        for (Path at = path; at != null && !at.equals(root); at = at.getParent()) {
            if (Files.isSymbolicLink(at)) throw new IOException("Showcase paths must not contain symbolic links: " + at);
            if (!at.equals(path) && Files.exists(at) && !Files.isDirectory(at)) throw new IOException("Showcase parent is not a directory: " + at);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && (directory ? !Files.isDirectory(path) : !Files.isRegularFile(path))) throw new IOException("Wrong showcase path type: " + path);
    }
    /** The tour's optional Chunky demonstration never rewrites the installed JSON or an explicit author selection. */
    public static void optionalIntegrations(Map<ResourceLocation,JEDSourceDocument> documents, boolean enabled, boolean chunky) {
        if (!enabled || !chunky) return;
        var id = ResourceLocation.parse("justepicdimensions:justepicdimensions/dimension_properties/showcase_scheduled_quarry.json");
        var policy = ResourceLocation.parse("justepicdimensions:justepicdimensions/pregeneration/showcase_quarry.json");
        var document = documents.get(id);
        if (document == null || !documents.containsKey(policy)) return;
        var value = document.value().getAsJsonObject();
        if (value.has("pregeneration")) return;
        var binding = new JsonObject(); binding.addProperty("policy", "justepicdimensions:showcase_quarry"); value.add("pregeneration", binding);
        var origins = new TreeMap<>(document.origins()); origins.put("pregeneration", "useShowcase: optional installed Chunky demonstration");
        documents.put(id, new JEDSourceDocument(value, origins));
    }
    private JEDShowcaseInstaller() {}
}
