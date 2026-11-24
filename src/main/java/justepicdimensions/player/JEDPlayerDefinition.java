package justepicdimensions.player;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

public record JEDPlayerDefinition(String inventoryGroup, boolean forceGameMode, Map<String, Rule> loadouts,
                                  Map<JEDPlayerCategory, ResourceLocation> groups, boolean removeGlobalInventoryItems) {
    public JEDPlayerDefinition(String inventoryGroup,boolean forceGameMode,Map<String,Rule> loadouts,Map<JEDPlayerCategory,ResourceLocation> groups){this(inventoryGroup,forceGameMode,loadouts,groups,false);}
    public static final String DEFAULT_GROUP = "__default";
    public enum Mode { FILL_EMPTY, TOP_UP, REPLACE_SLOTS, REPLACE_CONTAINERS }
    public record Rule(ResourceLocation kit, Mode mode, boolean dimensionScope, boolean applyWithKeepInventory,
                       boolean grantWithGrave, boolean graveAcrossGroups, boolean grantWithUnknownGrave) {}
    public static final JEDPlayerDefinition DEFAULT = new JEDPlayerDefinition(DEFAULT_GROUP, false, Map.of(), Map.of());
    public JEDPlayerDefinition { loadouts = Map.copyOf(loadouts); groups = Map.copyOf(groups); }

    public static JEDPlayerDefinition decode(JsonElement value, String source) {
        var root = JEDJson.object(value, source);
        JEDJson.keys(root, Set.of("inventory_group", "force_game_mode", "loadouts", "groups", "remove_global_inventory_items"), source);
        String group = root.has("inventory_group") ? JEDJson.string(root.get("inventory_group"), source + ".inventory_group") : DEFAULT_GROUP;
        if (group.isBlank()) throw new JEDDefinitionException(source + ": inventory_group must not be blank");
        var rules = new LinkedHashMap<String, Rule>();
        if (root.has("loadouts")) {
            var loadouts = JEDJson.object(root.get("loadouts"), source + ".loadouts");
            JEDJson.keys(loadouts, Set.of("first_entry", "respawn", "every_entry"), source + ".loadouts");
            loadouts.entrySet().forEach(entry -> {
                String where = source + ".loadouts." + entry.getKey();
                var rule = JEDJson.object(entry.getValue(), where);
                var keys = new HashSet<>(Set.of("kit", "mode"));
                if (entry.getKey().equals("first_entry")) keys.add("scope");
                if (entry.getKey().equals("respawn")) keys.addAll(Set.of("keep_inventory", "grave_policy", "grave_scope", "unknown_grave"));
                JEDJson.keys(rule, keys, where);
                var kit = JEDDimensionDefinition.id(JEDJson.string(rule.get("kit"), where + ".kit"), where);
                String modeName = choice(rule, "mode", "fill_empty", Set.of("fill_empty", "top_up", "replace_slots", "replace_containers"), where);
                rules.put(entry.getKey(), new Rule(kit, Mode.valueOf(modeName.toUpperCase(Locale.ROOT)),
                    choice(rule, "scope", "group", Set.of("group", "dimension"), where).equals("dimension"),
                    choice(rule, "keep_inventory", "skip", Set.of("skip", "apply"), where).equals("apply"),
                    choice(rule, "grave_policy", "skip", Set.of("skip", "grant"), where).equals("grant"),
                    choice(rule, "grave_scope", "inventory_group", Set.of("inventory_group", "death"), where).equals("death"),
                    choice(rule, "unknown_grave", "skip", Set.of("skip", "grant"), where).equals("grant")));
            });
        }
        var groups = new EnumMap<JEDPlayerCategory, ResourceLocation>(JEDPlayerCategory.class);
        if (root.has("groups")) {
            var references = JEDJson.object(root.get("groups"), source + ".groups");
            references.entrySet().forEach(entry -> {
                var where = source + ".groups." + entry.getKey();
                var category = JEDPlayerCategory.parse(entry.getKey(), where);
                groups.put(category, JEDDimensionDefinition.id(JEDJson.string(entry.getValue(), where), where));
            });
        }
        return new JEDPlayerDefinition(group, flag(root, "force_game_mode", false, source), rules, groups, flag(root,"remove_global_inventory_items",false,source));
    }
    static String choice(JsonObject root, String key, String fallback, Set<String> choices, String source) {
        String value = root.has(key) ? JEDJson.string(root.get(key), source + "." + key) : fallback;
        if (!choices.contains(value)) throw new JEDDefinitionException(source + "." + key + ": expected one of " + new TreeSet<>(choices));
        return value;
    }
    static boolean flag(JsonObject root, String key, boolean fallback, String source) {
        if (!root.has(key)) return fallback;
        var value = root.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new JEDDefinitionException(source + "." + key + ": expected a boolean");
        return value.getAsBoolean();
    }
}
