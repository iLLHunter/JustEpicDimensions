package justepicdimensions.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import justepicdimensions.definition.JEDDefinitionException;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** Small versioned controller records in the dimension-owned data directory. */
public final class JEDDimensionStore {
    public static Path file(Path world, ResourceLocation id, String name) {
        if (!name.matches("[a-z_]+")) throw new IllegalArgumentException(name);
        var file = JEDWorldStore.file(world, id).resolveSibling(name + ".dat");
        JEDDimensionFiles.requireSafe(world, file, false); return file;
    }
    public static CompoundTag read(Path world, ResourceLocation id, String name) {
        var file = file(world, id, name); if (!Files.exists(file)) return null;
        try {
            var tag = NbtIo.readCompressed(file, NbtAccounter.create(16L * 1024 * 1024));
            if (tag.getInt("format_version") != 1 || !tag.getString("dimension").equals(id.toString()) || !tag.contains("state", Tag.TAG_COMPOUND))
                throw new JEDDefinitionException("Incompatible JED " + name + " record: " + file);
            return tag.getCompound("state");
        } catch (IOException e) { throw new JEDDefinitionException("Cannot read " + file, e); }
    }
    public static void save(Path world, ResourceLocation id, String name, CompoundTag state) {
        var file = file(world, id, name); Path temporary = null;
        try {
            Files.createDirectories(file.getParent()); temporary = Files.createTempFile(file.getParent(), ".jed-" + name, ".tmp");
            var root = new CompoundTag(); root.putInt("format_version", 1); root.putString("dimension", id.toString()); root.put("state", state.copy());
            NbtIo.writeCompressed(root, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new JEDDefinitionException("Cannot save " + file, e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
    private JEDDimensionStore() {}
}
