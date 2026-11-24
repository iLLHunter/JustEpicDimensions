package justepicdimensions.player;

import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** A detached reusable policy. Mutable JSON is copied at both construction and access boundaries. */
public record JEDPlayerGroupDefinition(JEDPlayerCategory category, ResourceLocation id, JsonElement initial,
        Respawn respawn, Health health, Effects effects) {
    public enum Respawn { RESTORE_SAVED, NORMAL }
    public enum Restore { ABSOLUTE, PERCENTAGE }
    public enum Maximum { FOLLOW_MODS, CAP, FIXED }
    public enum Clock { PAUSE, SERVER_TIME, REAL_TIME }
    public enum Vetoes { AUTHORITATIVE, RESPECT }
    public enum Carry { COMBINE, REPLACE }
    public record Health(Restore restore, Maximum maximum, double maximumHearts) {}
    public record Effects(Clock clock, Vetoes vetoes, List<JEDPlayerEquipmentRule> equipment,
            boolean carryOver, Carry carryMode, JsonArray firstEntry, JsonArray everyEntry) {
        public Effects { equipment = List.copyOf(equipment); firstEntry=firstEntry.deepCopy(); everyEntry=everyEntry.deepCopy(); }
        @Override public JsonArray firstEntry(){return firstEntry.deepCopy();}
        @Override public JsonArray everyEntry(){return everyEntry.deepCopy();}
    }

    public JEDPlayerGroupDefinition { initial = initial.deepCopy(); }
    public JEDPlayerGroupDefinition withId(ResourceLocation value){return new JEDPlayerGroupDefinition(category,value,initial,respawn,health,effects);}
    @Override public JsonElement initial() { return initial.deepCopy(); }

    public static JEDPlayerGroupDefinition defaults(JEDPlayerCategory category) {
        return new JEDPlayerGroupDefinition(category, null,
                category == JEDPlayerCategory.EFFECTS ? new JsonArray() : new JsonObject(),
                Respawn.RESTORE_SAVED, new Health(Restore.ABSOLUTE, Maximum.FOLLOW_MODS, 0),
                new Effects(Clock.PAUSE, Vetoes.AUTHORITATIVE, List.of(), false, Carry.COMBINE, new JsonArray(), new JsonArray()));
    }

    public static JEDPlayerGroupDefinition decode(JEDPlayerCategory category, ResourceLocation id,
            JEDSourceDocument document, DynamicOps<JsonElement> ops) {
        String source = category.resource(id) + " (" + document.origin("") + ")";
        var root = JEDJson.object(document.value(), source);
        var allowed = new HashSet<>(Set.of("format_version", "initial", "respawn"));
        if (category == JEDPlayerCategory.HEALTH) allowed.addAll(Set.of("restore", "maximum"));
        if (category == JEDPlayerCategory.EFFECTS) allowed.addAll(Set.of("clock", "mod_vetoes", "equipment_effects", "carry_over", "carry_mode", "first_entry", "every_entry"));
        JEDJson.keys(root, allowed, source);
        if (JEDJson.integer(root.get("format_version"), source + ".format_version") != 1)
            throw new JEDDefinitionException(source + ": unsupported format_version; expected 1");
        var initial = root.has("initial") ? root.get("initial").deepCopy()
                : category == JEDPlayerCategory.EFFECTS ? new JsonArray() : new JsonObject();
        validateInitial(category, initial, source + ".initial", ops);
        var respawn = Respawn.valueOf(choice(root, "respawn", "restore_saved", source,
                "restore_saved", "normal").toUpperCase(Locale.ROOT));
        var health = defaults(category).health();
        var effects = defaults(category).effects();
        if (category == JEDPlayerCategory.HEALTH) {
            var restore = Restore.valueOf(choice(root, "restore", "absolute", source,
                    "absolute", "percentage").toUpperCase(Locale.ROOT));
            Maximum maximum = Maximum.FOLLOW_MODS;
            double hearts = 0;
            if (root.has("maximum")) {
                var maximumObject = JEDJson.object(root.get("maximum"), source + ".maximum");
                JEDJson.keys(maximumObject, Set.of("mode", "hearts"), source + ".maximum");
                maximum = Maximum.valueOf(choice(maximumObject, "mode", "follow_mods", source + ".maximum",
                        "follow_mods", "cap", "fixed").toUpperCase(Locale.ROOT));
                if (maximum != Maximum.FOLLOW_MODS) {
                    hearts = number(maximumObject.get("hearts"), source + ".maximum.hearts", false);
                    double points = hearts * 2;
                    if (!Double.isFinite(points) || Attributes.MAX_HEALTH.value().sanitizeValue(points) != points)
                        throw new JEDDefinitionException(source + ".maximum.hearts: outside the native maximum-health attribute range");
                } else if (maximumObject.has("hearts")) {
                    throw new JEDDefinitionException(source + ".maximum.hearts: only cap and fixed modes accept a maximum");
                }
            }
            health = new Health(restore, maximum, hearts);
        }
        if (category == JEDPlayerCategory.EFFECTS) {
            effects = new Effects(Clock.valueOf(choice(root, "clock", "pause", source,
                    "pause", "server_time", "real_time").toUpperCase(Locale.ROOT)),
                    Vetoes.valueOf(choice(root, "mod_vetoes", "authoritative", source,
                            "authoritative", "respect").toUpperCase(Locale.ROOT)),
                    root.has("equipment_effects") ? JEDPlayerEquipmentRule.decode(root.get("equipment_effects"), source + ".equipment_effects") : List.of(),
                    JEDPlayerDefinition.flag(root,"carry_over",false,source),
                    Carry.valueOf(choice(root,"carry_mode","combine",source,"combine","replace").toUpperCase(Locale.ROOT)),
                    effectArray(root,"first_entry",source,ops),effectArray(root,"every_entry",source,ops));
        }
        return new JEDPlayerGroupDefinition(category, id, initial, respawn, health, effects);
    }

    private static JsonArray effectArray(JsonObject root,String key,String source,DynamicOps<JsonElement> ops){
        var array=root.has(key)?root.get(key):new JsonArray();
        validateInitial(JEDPlayerCategory.EFFECTS,array,source+"."+key,ops);
        return array.getAsJsonArray();
    }

    private static void validateInitial(JEDPlayerCategory category, JsonElement initial, String source,
            DynamicOps<JsonElement> ops) {
        if (category == JEDPlayerCategory.EFFECTS) {
            if (!initial.isJsonArray()) throw new JEDDefinitionException(source + ": expected a native effect array");
            var seen = new HashSet<ResourceLocation>();
            for (var entry : initial.getAsJsonArray()) {
                var effect = MobEffectInstance.CODEC.parse(ops, entry).getOrThrow(error ->
                        new JEDDefinitionException(source + ": " + error));
                var effectId = effect.getEffect().unwrapKey().orElseThrow().location();
                if (!seen.add(effectId)) throw new JEDDefinitionException(source + ": duplicate effect " + effectId);
                validateEffectDuration(entry, source);
            }
            return;
        }
        var object = JEDJson.object(initial, source);
        Set<String> keys = switch (category) {
            case XP -> Set.of("level", "progress", "total");
            case HEALTH -> Set.of("hearts", "percentage");
            case ABSORPTION -> Set.of("hearts");
            case FOOD -> Set.of("hunger", "saturation", "exhaustion");
            default -> throw new AssertionError(category);
        };
        JEDJson.keys(object, keys, source);
        for (var entry : object.entrySet()) {
            String where = source + "." + entry.getKey();
            if (Set.of("level", "total", "hunger").contains(entry.getKey())) {
                if (JEDJson.integer(entry.getValue(), where) < 0)
                    throw new JEDDefinitionException(where + ": expected a nonnegative integer");
            } else {
                boolean zero = category != JEDPlayerCategory.HEALTH;
                double value = number(entry.getValue(), where, zero);
                if (Set.of("progress", "percentage").contains(entry.getKey()) && value > 1)
                    throw new JEDDefinitionException(where + ": expected a fraction no greater than 1");
                if (!Double.isFinite(value * (entry.getKey().equals("hearts") ? 2 : 1))
                        || value > Float.MAX_VALUE / 2)
                    throw new JEDDefinitionException(where + ": outside the finite native player-state range");
            }
        }
        if (object.has("hearts") && object.has("percentage"))
            throw new JEDDefinitionException(source + ": choose hearts or percentage, not both");
    }

    private static void validateEffectDuration(JsonElement effect, String source) {
        var object = JEDJson.object(effect, source);
        if (object.has("duration")) {
            int duration = JEDJson.integer(object.get("duration"), source + ".duration");
            if (duration != -1 && duration <= 0)
                throw new JEDDefinitionException(source + ".duration: expected positive ticks or -1 for an infinite effect");
        }
        if (object.has("hidden_effect")) validateEffectDuration(object.get("hidden_effect"), source + ".hidden_effect");
    }

    static double number(JsonElement value, String source, boolean allowZero) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new JEDDefinitionException(source + ": expected a finite number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || (allowZero ? result < 0 : result <= 0))
            throw new JEDDefinitionException(source + ": expected a finite " + (allowZero ? "nonnegative" : "positive") + " number");
        return result;
    }

    private static String choice(JsonObject object, String key, String fallback, String source, String... choices) {
        return JEDPlayerDefinition.choice(object, key, fallback, Set.of(choices), source);
    }
}
