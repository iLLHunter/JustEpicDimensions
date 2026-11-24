package justepicdimensions.config;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.world.level.GameType;

/** The screen describes existing TOML keys; it does not introduce a second configuration format. */
public final class JEDConfigFields {
    public enum Kind { BOOLEAN, INTEGER, DECIMAL, STRING, GAME_MODE, RESOURCE, ITEM_LIST }
    public enum Timing { CLIENT, RELOAD, RESTART, SELECTION, NEW_WORLD }
    public record Field(String key, String category, Kind kind, Object fallback, double min, double max, Timing timing) {
        public String translation() { return "justepicdimensions.config.field." + key; }
        public JsonElement defaultValue() { return GSON.toJsonTree(fallback); }
        public JsonElement parse(String text) {
            try {
                JsonElement value = switch (kind) {
                    case BOOLEAN -> { if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException(); yield new JsonPrimitive(Boolean.parseBoolean(text)); }
                    case INTEGER, DECIMAL -> new JsonPrimitive(new java.math.BigDecimal(text));
                    case ITEM_LIST -> JsonParser.parseString(text);
                    default -> new JsonPrimitive(text);
                };
                validate(value); return value;
            } catch (Exception error) { throw justepicdimensions.JEDText.failure("config.invalid",key,text); }
        }
        public Object validate(JsonElement value) {
            if(kind==Kind.ITEM_LIST){
                if(value==null||!value.isJsonArray())throw new JEDDefinitionException(key+": expected an array of full item registry IDs");
                var items=new ArrayList<String>();
                for(var item:value.getAsJsonArray()){
                    if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw new JEDDefinitionException(key+": every item must be a full registry ID string");
                    String id=item.getAsString();JEDDimensionDefinition.id(id,key);items.add(id);
                }
                return List.copyOf(items);
            }
            if (value == null || !value.isJsonPrimitive()) throw new JEDDefinitionException(key + ": expected " + kind.name().toLowerCase(Locale.ROOT));
            var p = value.getAsJsonPrimitive();
            return switch (kind) {
                case BOOLEAN -> { if (!p.isBoolean()) throw new JEDDefinitionException(key + ": expected true or false"); yield p.getAsBoolean(); }
                case INTEGER -> {
                    int number = JEDJson.integer(p, key);
                    if (number < min || number > max) throw new JEDDefinitionException(key + ": expected " + (long) min + " to " + (long) max);
                    yield number;
                }
                case DECIMAL -> {
                    if (!p.isNumber()) throw new JEDDefinitionException(key + ": expected a number");
                    double number = p.getAsDouble();
                    if (!Double.isFinite(number) || number < min || number > max) throw new JEDDefinitionException(key + ": number outside its allowed range");
                    yield number;
                }
                default -> {
                    if (!p.isString() || p.getAsString().length() > 1024) throw new JEDDefinitionException(key + ": expected text of at most 1024 characters");
                    String text = p.getAsString();
                    if (kind == Kind.GAME_MODE && GameType.byName(text, null) == null) throw new JEDDefinitionException(key + ": unknown game mode");
                    if (kind == Kind.RESOURCE && !text.isEmpty()) JEDDimensionDefinition.id(text, key);
                    yield text;
                }
            };
        }
        public String text(JsonElement value) { return value == null ? "" : value.isJsonPrimitive()?value.getAsString():value.toString(); }
    }
    private static final Gson GSON = new Gson();
    public static final List<Field> MAIN = List.of(
        bool("useShowcase", "files", false, Timing.SELECTION),
        bool("copyDimensionConfigToWorld", "files", true, Timing.SELECTION),
        bool("usePerWorldDimensionConfig", "files", false, Timing.SELECTION),
        bool("copyMainConfigToWorld", "files", true, Timing.SELECTION),
        bool("usePerWorldMainConfig", "files", false, Timing.SELECTION),
        text("config_id", "files", "__default", Kind.STRING, Timing.SELECTION),
        number("config_version", "files", 0, 0, Integer.MAX_VALUE, Timing.SELECTION),
        bool("enableReplacingRegisteredDimensions", "dimensions", true, Timing.RESTART),
        bool("enableUnregisteringDimensions", "dimensions", false, Timing.RESTART),
        bool("monitoringEnabled", "dimensions", false, Timing.RELOAD),
        text("monitoringPolicy", "dimensions", "", Kind.RESOURCE, Timing.RELOAD),
        number("dimensionSyncTimeoutSeconds", "dimensions", 600, 0, 86400, Timing.RELOAD),
        number("instanceRegisteredLimit", "dimensions", 256, 1, 65536, Timing.RELOAD),
        number("instanceLoadedLimit", "dimensions", 16, 1, 65536, Timing.RELOAD),
        number("pregenerationConcurrentJobs", "dimensions", 1, 1, 32, Timing.RELOAD),
        bool("automaticUnloadDimensions", "dimensions", false, Timing.RELOAD),
        number("automaticUnloadIdleSeconds", "dimensions", 300, 1, 31536000, Timing.RELOAD),
        bool("enableOverrideWorldProvider", "dimensions", true, Timing.RELOAD),
        bool("enableOverrideBiomeProvider", "dimensions", true, Timing.RESTART),
        text("world_preset", "dimensions", "", Kind.RESOURCE, Timing.NEW_WORLD),
        bool("enableSeparateWorldInfo", "world", true, Timing.RESTART),
        bool("enableSeparateWorldBorders", "world", true, Timing.RESTART),
        bool("enableCommandRedirecting", "world", true, Timing.RELOAD),
        text("initial_spawn_dimension", "players", "", Kind.RESOURCE, Timing.RELOAD),
        bool("enablePlayerInventoryGroups", "players", true, Timing.RELOAD),
        new Field("globalInventoryItems", "players", Kind.ITEM_LIST, justepicdimensions.player.JEDPlayerOptions.DEFAULT_GLOBAL_ITEMS, 0, 0, Timing.RELOAD),
        bool("enableForcedGameModes", "players", true, Timing.RELOAD),
        text("normalGameMode", "players", "survival", Kind.GAME_MODE, Timing.RELOAD),
        bool("tpjAdminOnly", "permissions", true, Timing.RELOAD),
        number("tpjAdminLevel", "permissions", 2, 0, 4, Timing.RELOAD),
        bool("enableLoggingInfo", "logging", false, Timing.RELOAD));
    public static final List<Field> CLIENT = List.of(
        bool("enableColorOverrides", "appearance", true, Timing.CLIENT),
        number("client_light_verification.positions_per_tick", "lighting", 512, 1, Integer.MAX_VALUE, Timing.CLIENT),
        number("client_light_verification.submissions_per_tick", "lighting", 512, 1, Integer.MAX_VALUE, Timing.CLIENT),
        new Field("client_light_verification.own_time_ms", "lighting", Kind.DECIMAL, 0.5, 0.000001, Double.MAX_VALUE / 1_000_000, Timing.CLIENT),
        new Field("client_light_verification.drain_pressure_ms", "lighting", Kind.DECIMAL, 2.0, 0.000001, Double.MAX_VALUE / 1_000_000, Timing.CLIENT));
    public static List<Field> fields(boolean client) { return client ? CLIENT : MAIN; }
    public static List<String> choices(Field field){return switch(field.kind()){case BOOLEAN->List.of("false","true");case GAME_MODE->List.of("survival","creative","adventure","spectator");default->field.key().equals("tpjAdminLevel")?List.of("0","1","2","3","4"):List.of();};}
    public static JsonObject defaults(boolean client) {
        var result = new JsonObject(); fields(client).forEach(f -> result.add(f.key(), f.defaultValue())); return result;
    }
    public static void validate(JsonObject values, boolean client) {
        var keys = new HashSet<String>(); for (var field : fields(client)) { keys.add(field.key()); field.validate(values.get(field.key())); }
        JEDJson.keys(values, keys, "configuration");
    }
    private static Field bool(String key, String group, boolean value, Timing timing) { return new Field(key, group, Kind.BOOLEAN, value, 0, 0, timing); }
    private static Field number(String key, String group, int value, int min, int max, Timing timing) { return new Field(key, group, Kind.INTEGER, value, min, max, timing); }
    private static Field text(String key, String group, String value, Kind kind, Timing timing) { return new Field(key, group, kind, value, 0, 0, timing); }
    private JEDConfigFields() {}
}
