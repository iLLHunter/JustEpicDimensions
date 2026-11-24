package justepicdimensions.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.dimension.DimensionType;

/** Detached file operations, called only after native save/close or before any levels are opened. */
public final class JEDDimensionFiles {
    public static void writeDurable(Path file, JsonElement value) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent()); temporary = Files.createTempFile(file.getParent(),".jed-journal-",".tmp");
            byte[] bytes = (new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { throw new JEDDefinitionException("Cannot persist lifecycle journal " + file,error); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
    public static Path directory(Path world, ResourceLocation id) {
        var result = DimensionType.getStorageFolder(ResourceKey.create(Registries.DIMENSION, id), world).toAbsolutePath().normalize();
        requireSafe(world, result, false); return result;
    }
    /** A namespaced path can be an ancestor of another dimension's native save directory. */
    public static void requireDisjoint(Path world, ResourceLocation target, Collection<ResourceLocation> dimensions) {
        var source = DimensionType.getStorageFolder(ResourceKey.create(Registries.DIMENSION,target),world).toAbsolutePath().normalize();
        for (var id : dimensions) if (!id.equals(target) && !id.equals(net.minecraft.world.level.Level.OVERWORLD.location())) {
            var other = DimensionType.getStorageFolder(ResourceKey.create(Registries.DIMENSION,id),world).toAbsolutePath().normalize();
            if (source.startsWith(other) || other.startsWith(source)) throw new JEDDefinitionException("Cannot reset/delete " + target + ": its native save path overlaps " + id + "; no files were moved");
        }
    }
    public static void protect(JsonObject operation, Collection<ResourceLocation> dimensions) {
        var values = new JsonArray(); new TreeSet<>(dimensions).forEach(id -> values.add(id.toString())); operation.add("protected_dimensions",values);
    }
    public static Path trash(Path world, String token) {
        if (!token.matches("[a-f0-9-]{36}")) throw new JEDDefinitionException("Invalid lifecycle operation token");
        var result = world.resolve(".jed-trash").resolve(token).toAbsolutePath().normalize(); requireSafe(world, result, false); return result;
    }
    /**
     * Rejects symbolic links among the components of {@code path} below {@code root}. The root and its ancestors are
     * operator-chosen locations (for example a world kept on another disk) and may themselves be links.
     */
    public static void requireSafe(Path root, Path path, boolean allowRoot) {
        root = root.toAbsolutePath().normalize(); path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root) || !allowRoot && path.equals(root)) throw new JEDDefinitionException("Refusing a path outside the intended directory: " + path);
        for (Path node = path; node != null && !node.equals(root); node = node.getParent()) {
            if (Files.isSymbolicLink(node)) throw new JEDDefinitionException("Symbolic links are not allowed in lifecycle paths: " + node);
        }
    }
    public static Map<UUID,JsonObject> playerReferences(Path world, ResourceLocation id, ResourceLocation fallback, boolean relocate) {
        var result = new TreeMap<UUID,JsonObject>(); Path players = world.resolve("playerdata"); requireSafe(world, players, false);
        try {
            if (Files.exists(players)) try (var files = Files.list(players)) {
                for (var file : files.filter(path -> path.getFileName().toString().endsWith(".dat")).toList()) {
                    requireSafe(players, file, false);
                    UUID uuid; try { uuid = UUID.fromString(file.getFileName().toString().replaceFirst("\\.dat$", "")); }
                    catch (IllegalArgumentException error) { throw new JEDDefinitionException("Unrecognized player data file: " + file); }
                    references(result, uuid, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), id, fallback, relocate);
                }
            }
            // The integrated server also saves its owner's player in level.dat.
            Path level = world.resolve("level.dat"); requireSafe(world, level, false);
            if (Files.exists(level)) {
                var data = NbtIo.readCompressed(level, NbtAccounter.unlimitedHeap()).getCompound("Data").getCompound("Player");
                if (data.hasUUID("UUID")) references(result, data.getUUID("UUID"), data, id, fallback, relocate);
            }
            // Native player data has only the active checkpoint. JED also preserves inactive
            // dimension-specific checkpoints, including for offline players and shutdown cleanup.
            Path checkpoints=world.resolve("data/justepicdimensions_players.dat");requireSafe(world,checkpoints,false);
            if(Files.exists(checkpoints))for(var item:NbtIo.readCompressed(checkpoints,NbtAccounter.unlimitedHeap()).getCompound("data").getList("players",Tag.TAG_COMPOUND)) {
                var player=(CompoundTag)item;for(var point:player.getList("points",Tag.TAG_COMPOUND)){var saved=(CompoundTag)point;if(id.toString().equals(saved.getString("dimension"))){var data=new CompoundTag();data.putString("SpawnDimension",id.toString());references(result,player.getUUID("uuid"),data,id,fallback,relocate);result.get(player.getUUID("uuid")).addProperty("checkpoint_token",saved.getString("token"));}}
            }
        } catch (IOException error) { throw new JEDDefinitionException("Cannot verify saved player destinations; lifecycle operation rejected", error); }
        return Map.copyOf(result);
    }
    private static void references(Map<UUID,JsonObject> result, UUID uuid, CompoundTag data, ResourceLocation id, ResourceLocation fallback, boolean relocate) {
        boolean position = id.toString().equals(data.getString("Dimension"));
        boolean respawn = id.toString().equals(data.getString("SpawnDimension"));
        if (!position && !respawn) return;
        if (!relocate) throw new JEDDefinitionException(id + " is referenced by saved player " + uuid + "; explicitly request relocation before resetting, unregistering or deleting it");
        var record = new JsonObject(); record.addProperty("from", id.toString()); record.addProperty("fallback", fallback.toString());
        var previous = result.get(uuid);
        record.addProperty("position", position || previous != null && previous.get("position").getAsBoolean());
        record.addProperty("respawn", respawn || previous != null && previous.get("respawn").getAsBoolean()); result.put(uuid, record);
    }
    public static void validateTemplate(Path template) {
        if (template == null) return;
        requireSafe(template.getParent(), template, false);
        if (!Files.isDirectory(template)) throw new JEDDefinitionException("Missing dimension template directory: " + template);
        try (var walk = Files.walk(template)) { walk.forEach(path -> requireSafe(template, path, true)); }
        catch (IOException error) { throw new JEDDefinitionException("Cannot inspect dimension template " + template, error); }
    }
    public static void copyTree(Path source, Path target) throws IOException {
        justepicdimensions.structure.JEDTemplateManager.validateClosed(source); requireSafe(target.getParent(), target, false);
        if(target.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize()))throw new JEDDefinitionException("Template destination cannot be inside its source: "+target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if(!justepicdimensions.structure.JEDTemplateManager.included(source.relativize(path)))return FileVisitResult.SKIP_SUBTREE;
                requireSafe(source, path, true); Files.createDirectories(target.resolve(source.relativize(path))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if(!justepicdimensions.structure.JEDTemplateManager.included(source.relativize(path)))return FileVisitResult.CONTINUE;
                requireSafe(source, path, false); if (!attributes.isRegularFile()) throw new IOException("Non-regular template file " + path);
                var copied=target.resolve(source.relativize(path));Files.copy(path,copied,StandardCopyOption.COPY_ATTRIBUTES);justepicdimensions.structure.JEDTemplateManager.sanitizeFile(copied);return FileVisitResult.CONTINUE;
            }
        });
    }
    /** Before first native level construction; an interrupted staging copy is never exposed as a world. */
    public static void initializeTemplate(Path world, ResourceLocation id, Path template) {
        if (template == null) return;
        var target = directory(world,id); if (Files.exists(target)) return;
        var root = world.resolve(".jed-template-staging"); var staged = root.resolve(java.util.UUID.nameUUIDFromBytes(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        requireSafe(world,staged,false);
        try {
            if (Files.exists(staged)) deleteTree(root,staged);
            Files.createDirectories(root); copyTree(template,staged);justepicdimensions.structure.JEDTemplateManager.rebase(staged,id);Files.writeString(staged.resolve(".jed-template-new"),template.toString());Files.createDirectories(target.getParent()); move(staged,target);
        } catch (IOException error) { throw new JEDDefinitionException("Cannot initialize dimension template " + id,error); }
    }
    /** Idempotent journal replay; an existing generation is never mistaken for the deleted generation. */
    public static JEDLifecycleStore execute(Path world, JEDLifecycleStore store) {
        var operation = store.operation(); if (operation == null) return store;
        String phase = operation.get("phase").getAsString();
        if (phase.equals("FILES_DONE") || phase.equals("COMMANDS_CLAIMED")) return store;
        String action = operation.get("action").getAsString();
        if (action.equals("unregister")) {
            if(operation.has("definition_removal")){
                var removal=operation.getAsJsonObject("definition_removal");
                if(!removal.get("dimension").equals(operation.get("dimension"))||!removal.get("token").equals(operation.get("token")))throw new JEDDefinitionException("Definition removal journal identity mismatch");
                JEDDefinitionRemoval.execute(world,removal);
            }
            return store.phase("FILES_DONE");
        }
        var id = ResourceLocation.parse(operation.get("dimension").getAsString());
        if (operation.has("protected_dimensions")) requireDisjoint(world,id,operation.getAsJsonArray("protected_dimensions").asList().stream().map(value -> ResourceLocation.parse(value.getAsString())).toList());
        var source = directory(world, id); var bin = trash(world, operation.get("token").getAsString());
        var old = bin.resolve("world"); var staged = bin.resolve("prepared");
        try {
            Files.createDirectories(bin);
            if (!Files.exists(bin.resolve("entry.json"))) JEDJson.writeAtomic(bin.resolve("entry.json"), operation);
            Path template = operation.has("template") ? Path.of(operation.get("template").getAsString()) : null;
            if (phase.equals("RESERVED")) {
                if (action.equals("reset") && template != null && !Files.exists(staged.resolve(".jed-generation"))) {
                    if (Files.exists(staged)) deleteTree(bin, staged);
                    copyTree(template, staged);justepicdimensions.structure.JEDTemplateManager.rebase(staged,id);Files.writeString(staged.resolve(".jed-template-new"),template.toString());Files.writeString(staged.resolve(".jed-generation"), operation.get("token").getAsString());
                }
                store = store.phase("PREPARED"); phase = "PREPARED";
            }
            if (phase.equals("PREPARED")) {
                // Both paths may exist after a crash immediately following template installation.
                if (!Files.exists(old) && Files.exists(source)) {
                    boolean installed = Files.exists(source.resolve(".jed-generation")) && Files.readString(source.resolve(".jed-generation")).equals(operation.get("token").getAsString());
                    if (!installed) move(source, old);
                }
                store = store.phase("TRASHED");
            }
            if (action.equals("reset") && template != null && Files.exists(staged)) {
                if (Files.exists(source)) throw new JEDDefinitionException("A different world generation appeared during reset: " + source);
                Files.createDirectories(source.getParent()); move(staged, source);
            }
            return store.phase("FILES_DONE");
        } catch (IOException error) { throw new JEDDefinitionException("Lifecycle file operation is journaled for recovery: " + id, error); }
    }
    private static void move(Path source, Path destination) throws IOException {
        requireSafe(source.getParent(), source, false); requireSafe(destination.getParent(), destination, false);
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, destination); }
    }
    public static void purge(Path world, String token) {
        var bin = trash(world, token);
        if (!Files.exists(bin.resolve("entry.json"))) throw new JEDDefinitionException("No recoverable trash entry " + token);
        var store = JEDLifecycleStore.read(world);
        if (store.operation() != null && token.equals(store.operation().get("token").getAsString())) throw new JEDDefinitionException("Cannot purge an unfinished operation");
        try { deleteTree(world.resolve(".jed-trash"), bin); }
        catch (IOException error) { throw new JEDDefinitionException("Could not purge " + token, error); }
    }
    public static void deleteTree(Path root, Path target) throws IOException {
        requireSafe(root, target, false);
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) { requireSafe(root, path, false); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException { requireSafe(root, path, false); Files.delete(path); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException { if (error != null) throw error; Files.delete(path); return FileVisitResult.CONTINUE; }
        });
    }
    private JEDDimensionFiles() {}
}
