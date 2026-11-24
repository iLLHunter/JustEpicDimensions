package justepicdimensions.environment;

import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;

/** Strict reusable environmental rules. Unreferenced dimensions keep native behavior. */
public record JEDEnvironmentPolicy(ResourceLocation id,String selection,List<Rule> rules){
    public static final String DIRECTORY="justepicdimensions/environment";
    public record Targets(boolean players,Set<ResourceLocation> ids,Set<TagKey<EntityType<?>>> tags,boolean all){
        public boolean test(LivingEntity entity){return all||(players&&entity instanceof net.minecraft.world.entity.player.Player)||ids.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))||tags.stream().anyMatch(entity.getType()::is);}
    }
    public record Damage(float hearts,Holder<DamageType> type,boolean independent){}
    public record Rule(String id,Targets targets,Set<GameType> modes,JEDEnvironmentCondition when,int interval,int delay,
                       List<MobEffectInstance> effects,Damage damage,float healing,List<String> order,boolean immediateCleanup,boolean carryAcrossGroups,
                       JEDEnvironmentProtection protection,String signature){
        public String key(ResourceLocation policy){return policy+"/"+id;}
    }
    public static JEDEnvironmentPolicy decode(ResourceLocation id,JEDSourceDocument document,DynamicOps<JsonElement> ops){
        var j=JEDJson.object(document.value(),id.toString());JEDJson.keys(j,Set.of("format_version","selection","rules"),id.toString());
        if(JEDJson.integer(j.get("format_version"),id+".format_version")!=1)throw new JEDDefinitionException(id+": expected environment format_version 1");
        String selection=choice(j,"selection","all",Set.of("all","first"),id.toString());var rules=new ArrayList<Rule>();var names=new HashSet<String>();
        int i=0;for(var raw:array(j,"rules",id.toString())){
            String at=id+".rules["+i+++"]";var r=JEDJson.object(raw,at);JEDJson.keys(r,Set.of("id","targets","game_modes","when","interval","delay","effects","damage","heal_hearts","order","cleanup","effect_groups","protection"),at);
            String name=JEDJson.string(r.get("id"),at+".id");if(name.isEmpty()||!name.matches("[a-z0-9_./-]+")||!names.add(name))throw new JEDDefinitionException(at+": rule id must be unique and use lowercase resource-path characters");
            int interval=integer(r,"interval",20,at),delay=integer(r,"delay",0,at);if(interval<1||delay<0)throw new JEDDefinitionException(at+": interval must be positive and delay nonnegative (ticks)");
            var targets=targets(r.get("targets"),at+".targets");var modes=EnumSet.noneOf(GameType.class);
            if(!r.has("game_modes"))modes.addAll(List.of(GameType.SURVIVAL,GameType.ADVENTURE));
            else for(var mode:array(r,"game_modes",at)){String value=JEDJson.string(mode,at+".game_modes");modes.add(Arrays.stream(GameType.values()).filter(v->v.getName().equals(value)).findFirst().orElseThrow(()->new JEDDefinitionException(at+": unknown game mode "+value)));}
            var effects=new ArrayList<MobEffectInstance>();for(var effect:array(r,"effects",at))effects.add(effect(effect,ops,at+".effects"));
            Damage damage=null;if(r.has("damage")){
                var d=JEDJson.object(r.get("damage"),at+".damage");JEDJson.keys(d,Set.of("hearts","type","cooldown"),at+".damage");float amount=number(d.get("hearts"),at+".damage.hearts");if(amount<=0||!Float.isFinite(amount*2))throw new JEDDefinitionException(at+": damage hearts must be positive and finite in native health points");
                var type=RegistryFixedCodec.<DamageType>create(Registries.DAMAGE_TYPE).parse(ops,new JsonPrimitive(string(d,"type","minecraft:generic",at))).getOrThrow(error->new JEDDefinitionException(at+".damage.type: "+error));
                damage=new Damage(amount,type,choice(d,"cooldown","native",Set.of("native","independent"),at).equals("independent"));
            }
            float healing=r.has("heal_hearts")?number(r.get("heal_hearts"),at+".heal_hearts"):0;if(healing<0||!Float.isFinite(healing*2))throw new JEDDefinitionException(at+": healing must be nonnegative and finite in native health points");
            if(effects.isEmpty()&&damage==null&&healing==0)throw new JEDDefinitionException(at+": rule needs effects, damage or healing");
            var order=new ArrayList<String>();if(r.has("order"))for(var action:array(r,"order",at)){String actionName=JEDJson.string(action,at+".order");if(!Set.of("effects","healing","damage").contains(actionName)||order.contains(actionName))throw new JEDDefinitionException(at+": order requires effects, healing and damage once each");order.add(actionName);}else order.addAll(List.of("effects","healing","damage"));
            if(order.size()!=3)throw new JEDDefinitionException(at+": order must list effects, healing and damage once each");
            rules.add(new Rule(name,targets,Set.copyOf(modes),JEDEnvironmentCondition.decode(r.get("when"),ops,at+".when"),interval,delay,List.copyOf(effects),damage,healing,List.copyOf(order),
                choice(r,"cleanup","expire",Set.of("expire","immediate"),at).equals("immediate"),choice(r,"effect_groups","follow",Set.of("follow","carry"),at).equals("carry"),JEDEnvironmentProtection.decode(r.get("protection"),ops,at+".protection"),signature(r)));
        }
        return new JEDEnvironmentPolicy(id,selection,List.copyOf(rules));
    }
    private static String signature(JsonObject json){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical(json).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static JsonElement canonical(JsonElement value){if(value.isJsonObject()){var result=new JsonObject();new TreeSet<>(value.getAsJsonObject().keySet()).forEach(key->result.add(key,canonical(value.getAsJsonObject().get(key))));return result;}if(value.isJsonArray()){var result=new JsonArray();value.getAsJsonArray().forEach(child->result.add(canonical(child)));return result;}return value;}
    private static MobEffectInstance effect(JsonElement raw,DynamicOps<JsonElement> ops,String at){
        var json=JEDJson.object(raw,at);JEDJson.keys(json,Set.of("id","amplifier","duration","ambient","show_particles","show_icon","hidden_effect","neoforge:cures"),at);
        var result=MobEffectInstance.CODEC.parse(ops,json).getOrThrow(error->new JEDDefinitionException(at+": "+error));
        if(!json.has("neoforge:cures"))result.getEffect().value().fillEffectCures(result.getCures(),result);
        if(json.has("hidden_effect")){var child=JEDJson.object(json.get("hidden_effect"),at+".hidden_effect").deepCopy();child.add("id",json.get("id"));var tag=(net.minecraft.nbt.CompoundTag)result.save();tag.put("hidden_effect",effect(child,ops,at+".hidden_effect").save());result=Objects.requireNonNull(MobEffectInstance.load(tag));}
        return result;
    }
    private static Targets targets(JsonElement raw,String at){
        if(raw==null)return new Targets(true,Set.of(),Set.of(),false);var j=JEDJson.object(raw,at);JEDJson.keys(j,Set.of("players","entities"),at);
        var ids=new HashSet<ResourceLocation>();var tags=new HashSet<TagKey<EntityType<?>>>();boolean all=false;
        if(j.has("entities"))for(var value:entries(j.get("entities"))){String text=JEDJson.string(value,at+".entities");if(text.equals("*")){all=true;continue;}boolean tag=text.startsWith("#");var id=JEDDimensionDefinition.id(tag?text.substring(1):text,at);
            if(tag)tags.add(TagKey.create(Registries.ENTITY_TYPE,id));else{if(!BuiltInRegistries.ENTITY_TYPE.containsKey(id))throw new JEDDefinitionException(at+": unknown entity "+id);ids.add(id);}}
        return new Targets(flag(j,"players",true,at),Set.copyOf(ids),Set.copyOf(tags),all);
    }
    static JsonArray entries(JsonElement value){if(value.isJsonArray())return value.getAsJsonArray();var result=new JsonArray();result.add(value);return result;}
    static JsonArray array(JsonObject j,String key,String at){if(!j.has(key))return new JsonArray();var value=j.get(key);if(!value.isJsonArray())throw new JEDDefinitionException(at+"."+key+": expected an array");return value.getAsJsonArray();}
    static String string(JsonObject j,String key,String fallback,String at){return j.has(key)?JEDJson.string(j.get(key),at+"."+key):fallback;}
    static int integer(JsonObject j,String key,int fallback,String at){return j.has(key)?JEDJson.integer(j.get(key),at+"."+key):fallback;}
    static float number(JsonElement raw,String at){if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber()||!Float.isFinite(raw.getAsFloat()))throw new JEDDefinitionException(at+": expected a finite number");return raw.getAsFloat();}
    static boolean flag(JsonObject j,String key,boolean fallback,String at){return justepicdimensions.access.JEDAccessDefinition.flag(j,key,fallback,at);}
    static String choice(JsonObject j,String key,String fallback,Set<String> values,String at){return justepicdimensions.access.JEDAccessDefinition.choice(j,key,fallback,values,at);}
}
