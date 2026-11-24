package justepicdimensions.mob;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

/** Null means inherit the native/world-state spawn setting, rather than an authored allow. */
public record JEDMobSpawnDefinition(Boolean hostile,Boolean peaceful,net.minecraft.resources.ResourceLocation policy,Integer creatureCap) {
    public JEDMobSpawnDefinition(Boolean hostile,Boolean peaceful){this(hostile,peaceful,null,null);}
    public JEDMobSpawnDefinition(Boolean hostile,Boolean peaceful,net.minecraft.resources.ResourceLocation policy){this(hostile,peaceful,policy,null);}
    public static JEDMobSpawnDefinition decode(JsonElement value,String path) {
        var json=JEDJson.object(value,path);JEDJson.keys(json,Set.of("hostile","peaceful","policy","creature_cap"),path);
        Integer cap=json.has("creature_cap")?JEDJson.integer(json.get("creature_cap"),path+".creature_cap"):null;
        if(cap!=null&&cap<0)throw new JEDDefinitionException(path+".creature_cap: expected a nonnegative integer");
        return new JEDMobSpawnDefinition(flag(json,"hostile",path),flag(json,"peaceful",path),json.has("policy")?JEDDimensionDefinition.id(JEDJson.string(json.get("policy"),path+".policy"),path):null,cap);
    }
    private static Boolean flag(JsonObject json,String key,String path) {
        if(!json.has(key))return null;var value=json.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(path+"."+key+": expected a boolean");
        return value.getAsBoolean();
    }
}
