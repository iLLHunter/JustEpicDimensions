package justepicdimensions.structure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.*;

/** Detached Sponge v3 decoding. Registry resolution and world changes belong to the placer. */
public final class JEDSchematicReader {
    public record Container(Map<Integer, String> palette, int[] cells) {
        public Container {
            palette = Collections.unmodifiableMap(new TreeMap<>(palette));
            cells = cells.clone();
        }
        @Override public int[] cells() { return cells.clone(); }
    }

    public record Schematic(int dataVersion, Vec3i size, BlockPos offset,
                            Container blocks, List<CompoundTag> blockEntities,
                            Container biomes, List<CompoundTag> entities) {
        public Schematic {
            size = new Vec3i(size.getX(), size.getY(), size.getZ());
            offset = offset.immutable();
            blockEntities = copy(blockEntities);
            entities = copy(entities);
        }
        @Override public List<CompoundTag> blockEntities() { return copy(blockEntities); }
        @Override public List<CompoundTag> entities() { return copy(entities); }
    }

    public static Schematic read(Path file, NbtAccounter budget) {
        try { return decode(NbtIo.readCompressed(file, budget), file.toString()); }
        catch (IOException error) { throw new JEDDefinitionException("Cannot read schematic " + file, error); }
    }

    public static Schematic decode(CompoundTag root, String source) {
        String where = source + ": Schematic";
        require(root, "Schematic", Tag.TAG_COMPOUND, source);
        CompoundTag tag = root.getCompound("Schematic");
        require(tag, "Version", Tag.TAG_INT, where);
        if (tag.getInt("Version") != 3) throw invalid(where, "expected Sponge schematic version 3");
        require(tag, "DataVersion", Tag.TAG_INT, where);
        int version = tag.getInt("DataVersion");
        if (version < 0) throw invalid(where, "DataVersion must be nonnegative");
        int width = dimension(tag, "Width", where);
        int height = dimension(tag, "Height", where);
        int length = dimension(tag, "Length", where);
        long volume = (long) width * height * length;
        if (volume > Integer.MAX_VALUE) throw invalid(where, "volume exceeds Java array indexing");
        int[] offset = tag.contains("Offset") ? coordinates(tag, "Offset", where) : new int[3];
        Container blocks = container(tag, "Blocks", (int) volume, where);
        Container biomes = container(tag, "Biomes", (int) volume, where);
        var blockEntities = new ArrayList<CompoundTag>();
        if (blocks != null) {
            var entries = compounds(tag.getCompound("Blocks"), "BlockEntities", where + ".Blocks");
            var positions = new HashSet<BlockPos>();
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.get(i);
                String at = where + ".Blocks.BlockEntities[" + i + "]";
                int[] pos = coordinates(entry, "Pos", at);
                if (pos[0] < 0 || pos[0] >= width || pos[1] < 0 || pos[1] >= height || pos[2] < 0 || pos[2] >= length)
                    throw invalid(at, "position lies outside the schematic");
                if (!positions.add(new BlockPos(pos[0], pos[1], pos[2]))) throw invalid(at, "duplicate block entity position");
                extra(entry, at);
                blockEntities.add(entry);
            }
        }
        var entities = compounds(tag, "Entities", where);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entry = entities.get(i);
            String at = where + ".Entities[" + i + "]";
            require(entry, "Pos", Tag.TAG_LIST, at);
            ListTag pos = entry.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() != 3) throw invalid(at, "Pos must contain exactly three doubles");
            for (int axis = 0; axis < 3; axis++) if (!Double.isFinite(pos.getDouble(axis)))
                throw invalid(at, "entity position must be finite");
            extra(entry, at);
        }
        return new Schematic(version, new Vec3i(width, height, length), new BlockPos(offset[0], offset[1], offset[2]),
            blocks, blockEntities, biomes, entities);
    }

    private static Container container(CompoundTag tag, String key, int volume, String where) {
        if (!tag.contains(key)) return null; // The specification permits biome-only and entity-only schematics.
        require(tag, key, Tag.TAG_COMPOUND, where);
        CompoundTag container = tag.getCompound(key);
        String at = where + "." + key;
        require(container, "Palette", Tag.TAG_COMPOUND, at);
        require(container, "Data", Tag.TAG_BYTE_ARRAY, at);
        var palette = new TreeMap<Integer, String>();
        CompoundTag values = container.getCompound("Palette");
        for (String state : values.getAllKeys()) {
            require(values, state, Tag.TAG_INT, at + ".Palette");
            int id = values.getInt(state);
            if (id < 0 || state.isBlank()) throw invalid(at, "palette keys must be nonempty and indices nonnegative");
            if (palette.putIfAbsent(id, state) != null) throw invalid(at, "duplicate palette index " + id);
        }
        if (palette.isEmpty()) throw invalid(at, "palette is empty");
        byte[] bytes = container.getByteArray("Data");
        // Each nonnegative varint occupies at least one byte. Check before allocating the cell array.
        if (bytes.length < volume) throw invalid(at, "truncated palette data");
        int[] cells = new int[volume];
        int cursor = 0;
        for (int cell = 0; cell < volume; cell++) {
            int value = 0;
            boolean complete = false;
            for (int part = 0; part < 5; part++) {
                if (cursor >= bytes.length) throw invalid(at, "truncated varint at cell " + cell);
                int next = bytes[cursor++] & 255;
                if (part == 4 && (next & 0xf8) != 0) throw invalid(at, "palette varint exceeds a nonnegative int");
                value |= (next & 127) << (part * 7);
                if ((next & 128) == 0) { complete = true; break; }
            }
            if (!complete) throw invalid(at, "palette varint is longer than five bytes");
            if (!palette.containsKey(value)) throw invalid(at, "unknown palette index " + value + " at cell " + cell);
            cells[cell] = value;
        }
        if (cursor != bytes.length) throw invalid(at, "extra palette data after the expected " + volume + " cells");
        return new Container(palette, cells);
    }

    private static int dimension(CompoundTag tag, String key, String where) {
        require(tag, key, Tag.TAG_SHORT, where);
        int result = tag.getShort(key) & 65535;
        if (result == 0) throw invalid(where, key + " must be positive");
        return result;
    }

    private static int[] coordinates(CompoundTag tag, String key, String where) {
        require(tag, key, Tag.TAG_INT_ARRAY, where);
        int[] pos = tag.getIntArray(key);
        if (pos.length != 3) throw invalid(where, key + " must contain exactly three integers");
        return pos;
    }

    private static List<CompoundTag> compounds(CompoundTag tag, String key, String where) {
        if (!tag.contains(key)) return List.of();
        require(tag, key, Tag.TAG_LIST, where);
        ListTag list = (ListTag) tag.get(key);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw invalid(where, key + " must contain compounds");
        var result = new ArrayList<CompoundTag>();
        for (Tag item : list) result.add(((CompoundTag) item).copy());
        return result;
    }

    private static void extra(CompoundTag entry, String where) {
        require(entry, "Id", Tag.TAG_STRING, where);
        if (entry.getString("Id").isBlank()) throw invalid(where, "Id must not be empty");
        if (entry.contains("Data")) require(entry, "Data", Tag.TAG_COMPOUND, where);
    }

    private static void require(CompoundTag tag, String key, int type, String where) {
        if (!tag.contains(key, type)) throw invalid(where, key + " is missing or has the wrong NBT type");
    }

    private static JEDDefinitionException invalid(String where, String reason) {
        return new JEDDefinitionException(where + ": " + reason);
    }

    private static List<CompoundTag> copy(List<CompoundTag> values) {
        return values.stream().map(CompoundTag::copy).toList();
    }

    private JEDSchematicReader() {}
}
