package justepicdimensions.environment;

import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;

/** Read-only conditions over the entity's current loaded position and local dimension state. */
@FunctionalInterface
public interface JEDEnvironmentCondition {
    boolean test(LivingEntity entity);
    static JEDEnvironmentCondition decode(JsonElement raw,DynamicOps<JsonElement> ops,String at){return decode(raw,ops,at,0,new int[1]);}
    private static JEDEnvironmentCondition decode(JsonElement raw,DynamicOps<JsonElement> ops,String at,int depth,int[] count){
        if(raw==null)return entity->true;if(depth>32||++count[0]>256)throw new JEDDefinitionException(at+": condition tree exceeds depth 32 or 256 nodes");
        var j=JEDJson.object(raw,at);JEDJson.keys(j,Set.of("all","any","not","biomes","height","time","weather","sky"),at);if(j.size()!=1)throw new JEDDefinitionException(at+": expected one condition kind");
        String kind=j.keySet().iterator().next();var value=j.get(kind);
        if(kind.equals("all")||kind.equals("any")){
            if(!value.isJsonArray()||value.getAsJsonArray().isEmpty())throw new JEDDefinitionException(at+": groups must be nonempty arrays");var children=new ArrayList<JEDEnvironmentCondition>();int i=0;
            for(var child:value.getAsJsonArray())children.add(decode(child,ops,at+"."+kind+"["+i+++"]",depth+1,count));return kind.equals("all")?entity->{for(var child:children)if(!child.test(entity))return false;return true;}:entity->{for(var child:children)if(child.test(entity))return true;return false;};
        }
        if(kind.equals("not")){var child=decode(value,ops,at+".not",depth+1,count);return entity->!child.test(entity);}
        if(kind.equals("biomes")){var biomes=Biome.LIST_CODEC.parse(ops,value).getOrThrow(error->new JEDDefinitionException(at+": "+error));return entity->biomes.contains(entity.level().getBiome(entity.blockPosition()));}
        if(kind.equals("height")){var range=JEDJson.object(value,at);JEDJson.keys(range,Set.of("min","max"),at);if(range.isEmpty())throw new JEDDefinitionException(at+": height range is empty");double min=range.has("min")?finite(range.get("min"),at):Double.NEGATIVE_INFINITY,max=range.has("max")?finite(range.get("max"),at):Double.POSITIVE_INFINITY;if(min>max)throw new JEDDefinitionException(at+": height min exceeds max");return entity->entity.getY()>=min&&entity.getY()<=max;}
        if(kind.equals("time")){
            if(value.isJsonPrimitive()){String name=JEDJson.string(value,at);if(!Set.of("day","night").contains(name))throw new JEDDefinitionException(at+": time must be day, night or a day-time range");return entity->name.equals("day")?entity.level().isDay():entity.level().isNight();}
            var range=JEDJson.object(value,at);JEDJson.keys(range,Set.of("min","max"),at);int min=JEDEnvironmentPolicy.integer(range,"min",0,at),max=JEDEnvironmentPolicy.integer(range,"max",23999,at);if(min<0||min>23999||max<0||max>23999)throw new JEDDefinitionException(at+": day-time bounds must be 0..23999");return entity->{long t=Math.floorMod(entity.level().getDayTime(),24000);return min<=max?t>=min&&t<=max:t>=min||t<=max;};
        }
        if(kind.equals("weather")){String name=JEDJson.string(value,at);if(!Set.of("clear","rain","thunder").contains(name))throw new JEDDefinitionException(at+": weather must be clear, rain or thunder");return entity->switch(name){case "clear"->!entity.level().isRaining()&&!entity.level().isThundering();case "rain"->entity.level().isRaining();default->entity.level().isThundering();};}
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(at+": sky requires a boolean");boolean expected=value.getAsBoolean();return entity->entity.level().canSeeSky(entity.blockPosition())==expected;
    }
    private static double finite(JsonElement value,String at){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble()))throw new JEDDefinitionException(at+": expected a finite bound");return value.getAsDouble();}
}
