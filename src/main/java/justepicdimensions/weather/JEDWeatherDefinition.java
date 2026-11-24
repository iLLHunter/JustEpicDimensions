package justepicdimensions.weather;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

/** Authored fields stay nullable so provider defaults do not become explicit biome overrides. */
public record JEDWeatherDefinition(Boolean naturalLightning, Boolean precipitationBlockEffects,
                                   Mode snow, Mode ice, boolean preserveOnSleep) {
    public enum Mode { NATIVE, DISABLED, IGNORE_TEMPERATURE }
    public static JEDWeatherDefinition decode(JsonElement value,String path) {
        var json=JEDJson.object(value,path);
        JEDJson.keys(json,Set.of("natural_lightning","precipitation_block_effects","snow","ice","preserve_on_sleep"),path);
        var preserve=flag(json,"preserve_on_sleep",path);
        return new JEDWeatherDefinition(flag(json,"natural_lightning",path),flag(json,"precipitation_block_effects",path),
            mode(json,"snow",path),mode(json,"ice",path),Boolean.TRUE.equals(preserve));
    }
    private static Boolean flag(JsonObject json,String key,String path) {
        if(!json.has(key))return null;
        var value=json.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(path+"."+key+": expected a boolean");
        return value.getAsBoolean();
    }
    private static Mode mode(JsonObject json,String key,String path) {
        if(!json.has(key))return null;
        return switch(JEDJson.string(json.get(key),path+"."+key)) {
            case "native" -> Mode.NATIVE;
            case "disabled" -> Mode.DISABLED;
            case "ignore_temperature" -> Mode.IGNORE_TEMPERATURE;
            default -> throw new JEDDefinitionException(path+"."+key+": expected native, disabled or ignore_temperature");
        };
    }
}
