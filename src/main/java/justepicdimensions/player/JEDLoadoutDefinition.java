package justepicdimensions.player;

import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Native 1.21 item components, explicit containers, and no legacy stack/NBT conversion. */
public record JEDLoadoutDefinition(ResourceLocation id, List<Container> containers) {
    public static final String DIRECTORY = "justepicdimensions/loadouts";
    public record Address(String backend, String container, boolean cosmetic) {}
    public record Entry(int index, ItemStack item) { public Entry { item = item.copy(); } @Override public ItemStack item() { return item.copy(); } }
    public record Container(Address address, List<Entry> slots) { public Container { slots = List.copyOf(slots); } }
    public JEDLoadoutDefinition { containers = List.copyOf(containers); }

    public static JEDLoadoutDefinition decode(ResourceLocation id, JEDSourceDocument source, DynamicOps<JsonElement> ops) {
        var root = JEDJson.object(source.value(), id.toString());
        JEDJson.keys(root, Set.of("format_version", "containers"), id.toString());
        if (JEDJson.integer(root.get("format_version"), id + ".format_version") != 1) throw new JEDDefinitionException(id + ": expected loadout format_version 1");
        if (!root.has("containers") || !root.get("containers").isJsonArray()) throw new JEDDefinitionException(id + ": containers must be an array");
        var containers = new ArrayList<Container>();
        var seen = new HashSet<Address>();
        for (var value : root.getAsJsonArray("containers")) {
            var object = JEDJson.object(value, id + ".containers");
            JEDJson.keys(object, Set.of("backend", "container", "cosmetic", "slots"), id + ".containers");
            String backend = JEDPlayerDefinition.choice(object, "backend", "minecraft", JEDInventoryAdapterRegistry.BACKENDS, id.toString());
            String name = JEDJson.string(object.get("container"), id + ".container");
            boolean cosmetic = JEDPlayerDefinition.flag(object, "cosmetic", false, id.toString());
            if (name.isBlank()) throw new JEDDefinitionException(id + ": container must not be blank");
            if (backend.equals("minecraft") && (!Set.of("hotbar", "main", "armor", "offhand", "ender_chest").contains(name) || cosmetic))
                throw new JEDDefinitionException(id + ": invalid vanilla container/cosmetic target " + name);
            var address = new Address(backend, name, cosmetic);
            if (!seen.add(address)) throw new JEDDefinitionException(id + ": duplicate container " + address);
            if (!object.has("slots") || !object.get("slots").isJsonArray()) throw new JEDDefinitionException(id + ": slots must be an array");
            var entries = new ArrayList<Entry>();
            var indices = new HashSet<Integer>();
            for (var raw : object.getAsJsonArray("slots")) {
                var slot = JEDJson.object(raw, id + ".slots");
                JEDJson.keys(slot, Set.of("index", "item"), id + ".slots");
                int index = JEDJson.integer(slot.get("index"), id + ".index");
                if (index < 0 || !indices.add(index)) throw new JEDDefinitionException(id + ": duplicate or negative slot index " + index);
                var item = ItemStack.OPTIONAL_CODEC.parse(ops, slot.get("item")).getOrThrow(message -> new JEDDefinitionException(id + ": invalid item: " + message));
                if (!item.isEmpty() && item.getCount() > item.getMaxStackSize()) throw new JEDDefinitionException(id + ": item count exceeds native stack size");
                entries.add(new Entry(index, item));
            }
            containers.add(new Container(address, entries));
        }
        return new JEDLoadoutDefinition(id, containers);
    }
}
