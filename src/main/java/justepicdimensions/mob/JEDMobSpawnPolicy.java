package justepicdimensions.mob;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.*;

/** Reusable spawning settings; absence of a policy preserves the native spawning pipeline. */
public record JEDMobSpawnPolicy(Set<JEDMobSpawnSource> sources,Selector allowed,Selector denied,List<Rule> rules,List<Addition> additions) {
    public static final String DIRECTORY="justepicdimensions/mob_spawning";
    public record Selector(boolean all,Set<ResourceLocation> ids,Set<TagKey<EntityType<?>>> tags){
        public boolean matches(EntityType<?> type){return all||ids.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type))||tags.stream().anyMatch(type::is);}
    }
    public record Cap(int limit,String scope,Double radius){}
    public record Rule(Selector entities,Set<JEDMobSpawnSource> sources,boolean allow,double multiplier,List<Cap> caps){public boolean matches(EntityType<?> type,JEDMobSpawnSource source){return sources.contains(source)&&entities.matches(type);}}
    public record Addition(EntityType<?> entity,int weight,int minCount,int maxCount){}
    public boolean permits(EntityType<?> entity,JEDMobSpawnSource source){
        if(sources.contains(source)&&(!allowed.matches(entity)||denied.matches(entity)))return false;
        for(var rule:rules)if(rule.matches(entity,source)&&!rule.allow())return false;
        return true;
    }
    public java.math.BigDecimal multiplier(EntityType<?> entity,JEDMobSpawnSource source){var value=java.math.BigDecimal.ONE;for(var rule:rules)if(rule.matches(entity,source))value=value.multiply(java.math.BigDecimal.valueOf(rule.multiplier()),java.math.MathContext.DECIMAL128);return value;}
    public boolean changesLists(JEDMobSpawnSource source){
        if(sources.contains(source)&&(!allowed.all()||denied.all()||!denied.ids().isEmpty()||!denied.tags().isEmpty()||!additions.isEmpty()))return true;
        return rules.stream().anyMatch(rule->rule.sources().contains(source)&&(!rule.allow()||rule.multiplier()!=1));
    }
    public boolean applies(JEDMobSpawnSource source){return sources.contains(source)||rules.stream().anyMatch(rule->rule.sources().contains(source));}
    public static JEDMobSpawnPolicy decode(ResourceLocation id,JEDSourceDocument document){
        String at=id.toString();var root=JEDJson.object(document.value(),at);JEDJson.keys(root,Set.of("format_version","sources","allow","deny","rules","additions"),at);
        if(JEDJson.integer(root.get("format_version"),at+".format_version")!=1)throw new JEDDefinitionException(at+": expected mob spawning format_version 1");
        var sources=sources(root.get("sources"),Set.of(JEDMobSpawnSource.NATURAL),at+".sources");
        var allowed=selector(root.get("allow"),true,at+".allow");var denied=selector(root.get("deny"),false,at+".deny");
        var rules=new ArrayList<Rule>();int index=0;
        for(var value:array(root,"rules",at)){
            String here=at+".rules["+index+++"]";var rule=JEDJson.object(value,here);JEDJson.keys(rule,Set.of("entities","sources","allow","weight_multiplier","caps"),here);
            var caps=new ArrayList<Cap>();int capIndex=0;
            for(var capValue:array(rule,"caps",here)){
                String where=here+".caps["+capIndex+++"]";var cap=JEDJson.object(capValue,where);JEDJson.keys(cap,Set.of("limit","scope","radius"),where);
                int limit=JEDJson.integer(cap.get("limit"),where+".limit");if(limit<0)throw new JEDDefinitionException(where+": limit must be nonnegative");
                String scope=string(cap,"scope","dimension",where);if(!Set.of("dimension","player").contains(scope))throw new JEDDefinitionException(where+": scope must be dimension or player");
                Double radius=cap.has("radius")?number(cap.get("radius"),where+".radius"):null;
                if(scope.equals("player")&&(radius==null||radius<=0||!Double.isFinite(radius*2)))throw new JEDDefinitionException(where+": player caps require a positive radius");
                if(scope.equals("dimension")&&radius!=null)throw new JEDDefinitionException(where+": radius only applies to player caps");
                caps.add(new Cap(limit,scope,radius));
            }
            double multiplier=rule.has("weight_multiplier")?number(rule.get("weight_multiplier"),here+".weight_multiplier"):1;
            if(multiplier<0)throw new JEDDefinitionException(here+": weight multiplier must be nonnegative");
            rules.add(new Rule(selector(rule.get("entities"),true,here+".entities"),sources(rule.get("sources"),sources,here+".sources"),flag(rule,"allow",true,here),multiplier,List.copyOf(caps)));
        }
        var additions=new ArrayList<Addition>();index=0;
        for(var value:array(root,"additions",at)){
            String here=at+".additions["+index+++"]";var addition=JEDJson.object(value,here);JEDJson.keys(addition,Set.of("entity","weight","min_count","max_count"),here);
            var entity=id(addition.get("entity"),here+".entity");if(!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(entity))throw new JEDDefinitionException(here+": unknown entity "+entity);var type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(entity);
            if(type==null)throw new JEDDefinitionException(here+": unknown entity "+entity);
            if(type.getCategory()==MobCategory.MISC)throw new JEDDefinitionException(here+": entity "+entity+" has no native natural-spawning category");
            int weight=integer(addition,"weight",1,here),min=integer(addition,"min_count",1,here),max=integer(addition,"max_count",min,here);
            if(weight<1||min<1||max<min)throw new JEDDefinitionException(here+": require positive weight and 1 <= min_count <= max_count");
            additions.add(new Addition(type,weight,min,max));
        }
        return new JEDMobSpawnPolicy(sources,allowed,denied,List.copyOf(rules),List.copyOf(additions));
    }
    private static Selector selector(JsonElement value,boolean missingAll,String at){
        if(value==null)return new Selector(missingAll,Set.of(),Set.of());
        var entries=value.isJsonArray()?value.getAsJsonArray():new JsonArray();if(!value.isJsonArray())entries.add(value);
        var ids=new HashSet<ResourceLocation>();var tags=new HashSet<TagKey<EntityType<?>>>();
        for(var entry:entries){String text=JEDJson.string(entry,at);boolean tag=text.startsWith("#");var id=JEDDimensionDefinition.id(tag?text.substring(1):text,at);
            if(tag)tags.add(TagKey.create(Registries.ENTITY_TYPE,id));
            else{if(!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(id))throw new JEDDefinitionException(at+": unknown entity "+id);ids.add(id);}}
        return new Selector(false,Set.copyOf(ids),Set.copyOf(tags));
    }
    private static Set<JEDMobSpawnSource> sources(JsonElement value,Set<JEDMobSpawnSource> fallback,String at){
        if(value==null)return fallback;if(!value.isJsonArray())throw new JEDDefinitionException(at+": expected a source array");var result=EnumSet.noneOf(JEDMobSpawnSource.class);
        for(var entry:value.getAsJsonArray()){String text=JEDJson.string(entry,at);if(text.equals("*")){result.addAll(EnumSet.allOf(JEDMobSpawnSource.class));continue;}try{result.add(JEDMobSpawnSource.valueOf(text.toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException error){throw new JEDDefinitionException(at+": unknown spawn source "+text);}}
        return Collections.unmodifiableSet(result);
    }
    private static ResourceLocation id(JsonElement value,String at){return JEDDimensionDefinition.id(JEDJson.string(value,at),at);}
    private static int integer(JsonObject j,String key,int fallback,String at){return j.has(key)?JEDJson.integer(j.get(key),at+"."+key):fallback;}
    private static String string(JsonObject j,String key,String fallback,String at){return j.has(key)?JEDJson.string(j.get(key),at+"."+key):fallback;}
    private static boolean flag(JsonObject j,String key,boolean fallback,String at){return justepicdimensions.access.JEDAccessDefinition.flag(j,key,fallback,at);}
    private static JsonArray array(JsonObject j,String key,String at){if(!j.has(key))return new JsonArray();var value=j.get(key);if(!value.isJsonArray())throw new JEDDefinitionException(at+"."+key+": expected an array");return value.getAsJsonArray();}
    private static double number(JsonElement value,String at){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble()))throw new JEDDefinitionException(at+": expected a finite number");return value.getAsDouble();}
}
