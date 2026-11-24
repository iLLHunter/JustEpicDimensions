package justepicdimensions.provider;

import com.google.gson.*;
import com.mojang.serialization.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Extra provider behavior; native DimensionType owns skylight, height, and coordinate scale. */
public record JEDProviderSettings(Boolean surfaceWorld,Boolean waterVaporizes,Integer spawnHeight,Boolean fog,
        Set<ResourceLocation> foggyBiomes,boolean listedHaveFog,Double horizon) {
    public JEDProviderSettings {if(foggyBiomes!=null)foggyBiomes=Set.copyOf(foggyBiomes);}
    public static final JEDProviderSettings DEFAULT=new JEDProviderSettings(null,null,null,null,null,false,null);
    public static final Codec<JEDProviderSettings> CODEC=Codec.PASSTHROUGH.comapFlatMap(dynamic->{try{return DataResult.success(decode(dynamic.convert(JsonOps.INSTANCE).getValue(),"provider.settings"));}catch(JEDDefinitionException e){return DataResult.error(e::getMessage);}},settings->new Dynamic<>(JsonOps.INSTANCE,settings.json()));
    public boolean biomeFog(){return foggyBiomes!=null;}
    public static JEDProviderSettings decode(JsonElement value,String source) {
        var json=JEDJson.object(value,source);JEDJson.keys(json,Set.of("surface_world","water_vaporizes","spawn_height","fog","foggy_biomes","listed_have_fog","horizon"),source);
        Integer height=json.has("spawn_height")?JEDJson.integer(json.get("spawn_height"),source+".spawn_height"):null;
        if(height!=null&&(height<-2032||height>2031))throw new JEDDefinitionException(source+".spawn_height: outside supported dimension height");
        Double horizon=null;if(json.has("horizon")){var v=json.get("horizon");if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||!Double.isFinite(v.getAsDouble()))throw new JEDDefinitionException(source+".horizon: expected a finite number");horizon=v.getAsDouble();}
        var biomes=new LinkedHashSet<ResourceLocation>();if(json.has("foggy_biomes")){var list=json.get("foggy_biomes");if(!list.isJsonArray())throw new JEDDefinitionException(source+".foggy_biomes: expected an array");for(var b:list.getAsJsonArray())biomes.add(JEDDimensionDefinition.id(JEDJson.string(b,source),source));}
        var listed=bool(json,"listed_have_fog",source);
        return new JEDProviderSettings(bool(json,"surface_world",source),bool(json,"water_vaporizes",source),height,bool(json,"fog",source),json.has("foggy_biomes")?biomes:null,listed!=null&&listed,horizon);
    }
    private static Boolean bool(JsonObject json,String key,String source){if(!json.has(key))return null;var v=json.get(key);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(source+"."+key+": expected a boolean");return v.getAsBoolean();}
    public JsonObject json(){var json=new JsonObject();if(surfaceWorld!=null)json.addProperty("surface_world",surfaceWorld);if(waterVaporizes!=null)json.addProperty("water_vaporizes",waterVaporizes);if(spawnHeight!=null)json.addProperty("spawn_height",spawnHeight);if(fog!=null)json.addProperty("fog",fog);if(biomeFog()){var array=new JsonArray();foggyBiomes.stream().sorted().forEach(id->array.add(id.toString()));json.add("foggy_biomes",array);}json.addProperty("listed_have_fog",listedHaveFog);if(horizon!=null)json.addProperty("horizon",horizon);return json;}
}
