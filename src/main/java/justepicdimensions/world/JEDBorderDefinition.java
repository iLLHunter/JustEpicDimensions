package justepicdimensions.world;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

public record JEDBorderDefinition(boolean centerOnSpawn, String centering) {
    public static final JEDBorderDefinition DEFAULT = new JEDBorderDefinition(false, "on_load");
    public static JEDBorderDefinition decode(JsonElement value, String source) {
        var json = JEDJson.object(value, source); JEDJson.keys(json, Set.of("center_on_spawn", "centering"), source);
        boolean enabled = false;
        if (json.has("center_on_spawn")) {
            var v = json.get("center_on_spawn"); if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw new JEDDefinitionException(source + ".center_on_spawn: expected a boolean");
            enabled = v.getAsBoolean();
        }
        var mode = json.has("centering") ? JEDJson.string(json.get("centering"), source + ".centering") : "on_load";
        if (!Set.of("on_load", "follow_spawn").contains(mode)) throw new JEDDefinitionException(source + ".centering: expected on_load or follow_spawn");
        return new JEDBorderDefinition(enabled, mode);
    }
}
