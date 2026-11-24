package justepicdimensions.end;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

public record JEDEndDefinition(Boolean dragonFight, boolean generateSpikes, boolean reconstructSpikes) {
    public static final JEDEndDefinition DEFAULT = new JEDEndDefinition(null,true,true);
    public static JEDEndDefinition decode(JsonElement value,String source) {
        var json=JEDJson.object(value,source); JEDJson.keys(json,Set.of("dragon_fight","generate_spikes","reconstruct_spikes"),source);
        return new JEDEndDefinition(bool(json,"dragon_fight",source),value(json,"generate_spikes",source,true),value(json,"reconstruct_spikes",source,true));
    }
    private static Boolean bool(JsonObject json,String key,String source) {
        if(!json.has(key))return null;var value=json.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(source+"."+key+": expected a boolean");
        return value.getAsBoolean();
    }
    private static boolean value(JsonObject json,String key,String source,boolean fallback){var v=bool(json,key,source);return v==null?fallback:v;}
}
