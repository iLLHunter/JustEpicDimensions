package justepicdimensions.definition;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** File selection is resolved before native registry decoding, including integrated-server saves. */
public final class JEDConfigRepository {
    public record Version(String id, int version) {}
    public record Options(boolean copyDimensions, boolean useWorldDimensions, boolean copyMain, boolean useWorldMain, Version version, boolean useShowcase) {}
    public record Selection(Path source, Path world, List<Copy> copies, Path activeSource) {
        public Selection { copies = List.copyOf(copies); }
        public Selection(Path source, Path world, List<Copy> copies) { this(source, world, copies, source); }
        public void commitCopies() { copies.forEach(Copy::commit); }
    }
    public record Copy(Path from, Path to, boolean directory) {
        public void commit() {
            try {
                Files.createDirectories(to.toAbsolutePath().getParent());
                if (!directory) {
                    Path stage = Files.createTempFile(to.toAbsolutePath().getParent(), ".jed-main-", ".toml");
                    Files.copy(from, stage, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.exists(to)) Files.copy(to, to.resolveSibling(to.getFileName() + ".backup"), StandardCopyOption.REPLACE_EXISTING);
                    move(stage, to, true);
                    return;
                }
                Path stage = Files.createTempDirectory(to.toAbsolutePath().getParent(), ".jed-config-");
                copyDirectory(from, stage, to);
                Path backup = to.resolveSibling(to.getFileName() + ".backup-" + UUID.randomUUID());
                boolean hadPrevious = Files.exists(to);
                if (hadPrevious) move(to, backup, false);
                try { move(stage, to, false); }
                catch (IOException error) { if (hadPrevious) move(backup, to, false); throw error; }
            } catch (IOException error) { throw new JEDDefinitionException("Cannot copy JED configuration from " + from + " to " + to, error); }
        }
    }

    static void copyDirectory(Path from,Path stage,Path previous) throws IOException {
        try(var paths=Files.walk(from)){
            for(var path:paths.sorted().toList()){
                JEDDocumentCache.cancelled();
                if(Files.isSymbolicLink(path))throw new JEDDefinitionException("Configuration symlink is not supported: "+path);
                Path relative=from.relativize(path);
                if(justepicdimensions.config.JEDConfigPaths.toml(relative))continue;
                Path destination=stage.resolve(relative).normalize();
                if(!destination.startsWith(stage))throw new JEDDefinitionException("Configuration path escapes source: "+path);
                if(Files.isDirectory(path))Files.createDirectories(destination);else Files.copy(path,destination);
            }
        }
        if(Files.isDirectory(previous))try(var files=Files.list(previous)){
            for(var path:files.toList())if(justepicdimensions.config.JEDConfigPaths.toml(previous.relativize(path))){
                if(Files.isSymbolicLink(path)||!Files.isRegularFile(path))throw new JEDDefinitionException("Not a regular configuration file: "+path);
                Files.copy(path,stage.resolve(path.getFileName()));
            }
        }
    }
    public static Selection select(Path world) { return select(FMLPaths.CONFIGDIR.get(), world); }
    public static Selection select(Path configDirectory, Path world) {
        justepicdimensions.config.JEDConfigPaths.migrateGlobal(configDirectory);
        Path global = justepicdimensions.config.JEDConfigPaths.global(configDirectory);
        Path main = justepicdimensions.config.JEDConfigPaths.main(configDirectory);
        var options = options(main);
        var copies = new ArrayList<Copy>();
        if (world == null) { justepicdimensions.showcase.JEDShowcaseInstaller.install(global, options.useShowcase()); return new Selection(global, null, copies); }
        world = world.toAbsolutePath().normalize();
        Path perWorld = world.resolve("serverconfig/justepicdimensions");
        justepicdimensions.config.JEDConfigPaths.migrateWorld(world);
        Path worldMain = justepicdimensions.config.JEDConfigPaths.worldMain(world);
        boolean copyMain = Files.isRegularFile(main) && options.copyMain()
            && (!Files.exists(worldMain) || newer(options.version(), options(worldMain).version()));
        
        if (options.useWorldMain() && Files.isRegularFile(worldMain) && !copyMain) options = options(worldMain);
        justepicdimensions.showcase.JEDShowcaseInstaller.install(global, options.useShowcase());
        boolean globalExists = Files.isDirectory(global.resolve("data")) || Files.isRegularFile(global.resolve("manifest.json"));
        boolean worldExists = Files.isDirectory(perWorld.resolve("data")) || Files.isRegularFile(perWorld.resolve("manifest.json"));
        boolean copyDimensions = globalExists && options.copyDimensions()
            && (!worldExists || newer(version(global), version(perWorld)));
        if (copyDimensions) copies.add(new Copy(global, perWorld, true));
        if (copyMain) copies.add(new Copy(main, worldMain, false));
        Path source = options.useWorldDimensions() && worldExists && !copyDimensions ? perWorld : global;
        return new Selection(source, world, copies, options.useWorldDimensions() && (worldExists || copyDimensions) ? perWorld : global);
    }

    private static boolean newer(Version incoming, Version existing) {
        return incoming.id().equals(existing.id()) && incoming.version() > existing.version();
    }
    public static Path mainFile(Selection selection){
        Path global=justepicdimensions.config.JEDConfigPaths.main(FMLPaths.CONFIGDIR.get());
        if(selection.world()==null || !options(global).useWorldMain())return global;
        Path local=justepicdimensions.config.JEDConfigPaths.worldMain(selection.world());
        if(selection.copies().stream().anyMatch(copy->copy.to().equals(local)))return global;
        return Files.isRegularFile(local)?local:global;
    }

    public static Version version(Path root) {
        Path file = root.resolve("manifest.json");
        if (!Files.exists(file)) return new Version("__default", 0);
        var json = JEDJson.object(JEDJson.read(file), file.toString());
        JEDJson.keys(json, Set.of("format_version", "config_id", "config_version"), file.toString());
        if (JEDJson.integer(json.get("format_version"), file + ".format_version") != 1)
            throw new JEDDefinitionException(file + ": unsupported format_version");
        String id = JEDJson.string(json.get("config_id"), file + ".config_id");
        int version = JEDJson.integer(json.get("config_version"), file + ".config_version");
        if (version < 0) throw new JEDDefinitionException(file + ": config_version cannot be negative");
        return new Version(id, version);
    }

    public static Map<ResourceLocation, JEDSourceDocument> read(Selection selection) {
        return read(selection, null);
    }
    public static Map<ResourceLocation, JEDSourceDocument> read(Selection selection, JEDDocumentCache cache) {
        try(var ignored=justepicdimensions.builder.JEDBuilderFiles.guard(selection.source())) {
        justepicdimensions.builder.JEDBuilderFiles.recover(selection.source());
        justepicdimensions.bundle.JEDBundleFiles.recover(selection.source());
        version(selection.source());
        Path data = selection.source().resolve("data");
        var files = new TreeMap<ResourceLocation, JEDSourceDocument>();
        if (!Files.exists(data)) return Map.of();
        try (var paths = Files.walk(data)) {
            for (var file : paths.sorted().toList()) {
                JEDDocumentCache.cancelled();
                if (Files.isSymbolicLink(file)) throw new JEDDefinitionException("Configuration symlink is not supported: " + file);
                if (!Files.isRegularFile(file) || !file.toString().endsWith(".json")) continue;
                Path relative = data.relativize(file);
                if (relative.getNameCount() < 2) throw new JEDDefinitionException(file + ": expected data/<namespace>/<resource>.json");
                String namespace = relative.getName(0).toString();
                String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                var resource = JEDDimensionDefinition.id(namespace + ":" + path, file.toString());
                JsonElement value;
                try (var reader = Files.newBufferedReader(file)) { value = cache == null ? JEDJson.read(reader, file.toString()) : cache.read(reader, file.toString()); }
                if (files.putIfAbsent(resource, new JEDSourceDocument(value, "JSON " + file.toAbsolutePath())) != null)
                    throw new JEDDefinitionException("Duplicate JSON resource " + resource);
            }
        } catch (IOException error) { throw new JEDDefinitionException("Cannot enumerate " + data, error); }
        return Collections.unmodifiableMap(files);
        }
    }

    private static Options options(Path file) {
        if (!Files.exists(file)) return new Options(true, false, true, false, new Version("__default", 0), false);
        try (var config = CommentedFileConfig.of(file)) {
            config.load();
            String id = config.getOrElse("config_id", "__default");
            Object rawVersion = config.getOrElse("config_version", 0);
            if (!(rawVersion instanceof Number number) || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE
                || number.doubleValue() != number.intValue()) throw new JEDDefinitionException(file + ": invalid config_version");
            return new Options(bool(config.getOrElse("copyDimensionConfigToWorld", true), file),
                bool(config.getOrElse("usePerWorldDimensionConfig", false), file),
                bool(config.getOrElse("copyMainConfigToWorld", true), file),
                bool(config.getOrElse("usePerWorldMainConfig", false), file), new Version(id, number.intValue()), bool(config.getOrElse("useShowcase", false), file));
        } catch (JEDDefinitionException error) { throw error; }
        catch (RuntimeException error) { throw new JEDDefinitionException("Cannot read JED TOML " + file + ": " + error.getMessage(), error); }
    }
    private static boolean bool(Object value, Path file) {
        if (!(value instanceof Boolean result)) throw new JEDDefinitionException(file + ": configuration switches must be booleans");
        return result;
    }
    private static void move(Path from, Path to, boolean replace) throws IOException {
        try {
            if (replace) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replace) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); else Files.move(from, to);
        }
    }
    private JEDConfigRepository() {}
}
