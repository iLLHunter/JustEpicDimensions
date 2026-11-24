package justepicdimensions.spawn;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

/** Server-only spawn retention; an absent radius follows the level's native gamerule. */
public record JEDSpawnChunksDefinition(Boolean enabled, Integer radius, Activity activity) {
    public enum Activity { NATIVE, CONTINUOUS }
    public static final JEDSpawnChunksDefinition DEFAULT = new JEDSpawnChunksDefinition(null, null, Activity.NATIVE);
    public static JEDSpawnChunksDefinition decode(JsonElement value, String source) {
        var json = JEDJson.object(value, source);
        JEDJson.keys(json, Set.of("enabled", "radius", "activity"), source);
        Boolean enabled = null;
        if (json.has("enabled")) {
            var flag = json.get("enabled");
            if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) throw new JEDDefinitionException(source + ".enabled: expected a boolean");
            enabled = flag.getAsBoolean();
        }
        Integer radius = json.has("radius") ? JEDJson.integer(json.get("radius"), source + ".radius") : null;
        if (radius != null && (radius < 0 || radius > 32)) throw new JEDDefinitionException(source + ".radius: expected 0..32, matching spawnChunkRadius");
        Activity activity = Activity.NATIVE;
        if (json.has("activity")) activity = switch (JEDJson.string(json.get("activity"), source + ".activity")) {
            case "native" -> Activity.NATIVE;
            case "continuous" -> Activity.CONTINUOUS;
            default -> throw new JEDDefinitionException(source + ".activity: expected native or continuous");
        };
        return new JEDSpawnChunksDefinition(enabled, radius, activity);
    }
    public boolean retains(boolean keepLoaded, boolean explicitlyConfigured) {
        return enabled != null ? enabled : keepLoaded || explicitlyConfigured;
    }
}
