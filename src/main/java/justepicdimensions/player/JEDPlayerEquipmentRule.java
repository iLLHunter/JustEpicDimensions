package justepicdimensions.player;

import com.google.gson.JsonElement;
import java.util.*;
import justepicdimensions.definition.*;

/** An association identifies an item's effects; it does not grant an effect or alter the item. */
public record JEDPlayerEquipmentRule(List<JEDPlayerSelector> items, List<JEDPlayerSelector> effects) {
    public JEDPlayerEquipmentRule { items = List.copyOf(items); effects = List.copyOf(effects); }
    public static List<JEDPlayerEquipmentRule> decode(JsonElement value, String source) {
        if (value == null || !value.isJsonArray()) throw new JEDDefinitionException(source + ": expected an equipment-association array");
        var result = new ArrayList<JEDPlayerEquipmentRule>();
        for (var entry : value.getAsJsonArray()) {
            var object = JEDJson.object(entry, source); JEDJson.keys(object, Set.of("items", "effects"), source);
            var items = JEDPlayerSelector.decode(object.get("items"), source + ".items", false);
            var effects = JEDPlayerSelector.decode(object.get("effects"), source + ".effects", true);
            if (items.isEmpty() || effects.isEmpty()) throw new JEDDefinitionException(source + ": equipment associations need items and effects");
            result.add(new JEDPlayerEquipmentRule(items, effects));
        }
        return List.copyOf(result);
    }
}
