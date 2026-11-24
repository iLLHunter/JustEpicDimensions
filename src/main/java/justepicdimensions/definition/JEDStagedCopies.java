package justepicdimensions.definition;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.dimension.JEDDimensionSync;

/** Worker-owned copies. Final installation consumes captured bytes, never rereads a changing source. */
public final class JEDStagedCopies implements AutoCloseable {
    private record Item(JEDConfigRepository.Copy copy, Path stage, Map<String,String> source, Map<String,String> destination) {}
    private final List<Item> items = new ArrayList<>();
    private Path stagingRoot;
    public static JEDStagedCopies prepare(JEDConfigRepository.Selection selection) {
        var result = new JEDStagedCopies();
        result.stagingRoot=selection.world().resolve("serverconfig").toAbsolutePath().normalize();
        try {
            for (var copy : selection.copies()) {
                var before = stamp(copy.from()); var target = stamp(copy.to()); var parent = result.stagingRoot; Files.createDirectories(parent);
                Path stage = copy.directory() ? Files.createTempDirectory(parent, ".jed-preparation-") : Files.createTempFile(parent, ".jed-preparation-", ".tmp");
                result.items.add(new Item(copy, stage, before, target));
                if (copy.directory()) JEDConfigRepository.copyDirectory(copy.from(),stage,copy.to());
                else Files.copy(copy.from(),stage,StandardCopyOption.REPLACE_EXISTING);
                if (!expected(copy,before,target).equals(stamp(stage)) || !before.equals(stamp(copy.from())) || !target.equals(stamp(copy.to()))) throw new JEDDefinitionException("Configuration changed while staging: " + copy.from());
            }
            return result;
        } catch (IOException | RuntimeException error) { result.close(); throw error instanceof RuntimeException runtime ? runtime : new JEDDefinitionException("Cannot stage dimension configuration", error); }
    }
    private static Map<String,String> expected(JEDConfigRepository.Copy copy,Map<String,String> source,Map<String,String> destination){
        if(!copy.directory())return source;
        var result=new TreeMap<>(source);
        result.keySet().removeIf(path->justepicdimensions.config.JEDConfigPaths.toml(Path.of(path)));
        destination.forEach((path,value)->{if(justepicdimensions.config.JEDConfigPaths.toml(Path.of(path)))result.put(path,value);});
        return result;
    }
    public void verify() {
        for (var item : items) if (!item.source().equals(stamp(item.copy().from())) || !expected(item.copy(),item.source(),item.destination()).equals(stamp(item.stage())) || !item.destination().equals(stamp(item.copy().to())))
            throw new JEDDefinitionException("Configuration changed before installation: " + item.copy().from());
    }
    public void commit() {
        verify();
        for (var item : items) try {
            var target = item.copy().to();
            if (!item.copy().directory()) {
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) Files.copy(target, target.resolveSibling(target.getFileName() + ".backup"), StandardCopyOption.REPLACE_EXISTING);
                move(item.stage(), target, true); continue;
            }
            var backup = target.resolveSibling(target.getFileName() + ".backup-" + UUID.randomUUID()); boolean previous = Files.exists(target);
            if (previous) move(target, backup, false);
            try { move(item.stage(), target, false); }
            catch (IOException error) { if (previous) move(backup, target, false); throw error; }
        } catch (IOException error) { throw new JEDDefinitionException("Cannot install staged configuration", error); }
    }
    /** Content hashes detect same-size edits with unchanged timestamps, including names and empty directories. */
    public static Map<String,String> stamp(Path root) {
        var result = new TreeMap<String,String>();
        if (!Files.exists(root)) return Map.of();
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted().toList()) {
                JEDDocumentCache.cancelled(); if (Files.isSymbolicLink(path)) throw new JEDDefinitionException("Configuration symlink is not supported: " + path);
                result.put(root.relativize(path).toString(), Files.isDirectory(path) ? "directory" : JEDDimensionSync.digest(Files.readAllBytes(path)));
            }
        } catch (IOException error) { throw new JEDDefinitionException("Cannot fingerprint " + root, error); }
        return Map.copyOf(result);
    }
    public static String metadata(Path config, Path world) {
        var result = new StringBuilder();
        for (var path : List.of(justepicdimensions.config.JEDConfigPaths.main(config), config.resolve("justepicdimensions/manifest.json"),
            justepicdimensions.config.JEDConfigPaths.worldMain(world), world.resolve("serverconfig/justepicdimensions/manifest.json"))) result.append(path).append(new TreeMap<>(stamp(path)));
        return result.toString();
    }
    private static void move(Path from, Path to, boolean replace) throws IOException {
        try { if (replace) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); else Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { if (replace) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); else Files.move(from, to); }
    }
    public void close() {
        boolean interrupted = Thread.interrupted();
        for (var item : items) if (Files.exists(item.stage())) try {
            var stage = item.stage().toRealPath(); var parent = stagingRoot.toRealPath();
            if (!stage.startsWith(parent) || !stage.getFileName().toString().startsWith(".jed-preparation-") || Files.isSymbolicLink(item.stage()))
                throw new JEDDefinitionException("Refusing to remove a staging path outside its verified parent: " + stage);
            try (var paths = Files.walk(stage)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        } catch (IOException error) { com.mojang.logging.LogUtils.getLogger().warn("JED could not clean its staging path {}", item.stage(), error); }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
