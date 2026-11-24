package justepicdimensions.player;

import com.google.gson.JsonArray;
import com.mojang.serialization.JsonOps;
import java.util.*;
import justepicdimensions.environment.JEDEnvironmentEffects;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/** Full native effect data, including hidden effects and NeoForge cures. No gameplay ticks run offline. */
public final class JEDPlayerEffectState {
    public static CompoundTag capture(ServerPlayer player,JEDPlayerGroupDefinition policy,Set<ResourceLocation> equipment,CompoundTag previous){
        var result=stamp(player,policy);var entries=new LinkedHashMap<String,CompoundTag>();
        var excluded=previous.getList("excluded",Tag.TAG_STRING);
        for(var effect:player.getActiveEffects()){
            String id=effect.getEffect().unwrapKey().orElseThrow().location().toString();
            if(!excluded.contains(StringTag.valueOf(id))){var entry=JEDEnvironmentEffects.snapshot(player,effect,equipment.contains(ResourceLocation.parse(id)));if(entry!=null)entries.put(id,entry);}
        }
        // Unavailable registry entries and vetoed restorations must survive subsequent saves.
        var pending=previous.getList("pending",Tag.TAG_COMPOUND);
        if(!pending.isEmpty()&&previous.contains("clock")){
            var waiting=previous.copy();waiting.put("values",pending.copy());
            pending=elapsed(waiting,JEDPlayerGroupClock.ticks(player.server),System.currentTimeMillis());
        }
        for(var entry:pending)entries.put(((CompoundTag)entry).getString("id"),((CompoundTag)entry).copy());
        var list=new ListTag();entries.values().forEach(list::add);result.put("values",list);
        if(!pending.isEmpty())result.put("pending",pending.copy());
        if(!excluded.isEmpty())result.put("excluded",excluded.copy());
        return result;
    }
    private static CompoundTag stamp(ServerPlayer player,JEDPlayerGroupDefinition policy){
        var result=new CompoundTag();result.putString("clock",policy.effects().clock().name());
        result.putLong("server_tick",JEDPlayerGroupClock.ticks(player.server));result.putLong("wall_ms",System.currentTimeMillis());return result;
    }
    public static CompoundTag initial(ServerPlayer player,JEDPlayerGroupDefinition policy){
        var result=stamp(player,policy);result.put("values",decode(policy.initial().getAsJsonArray()));return result;
    }
    public static ListTag decode(JsonArray array){
        var result=new ListTag();for(var json:array){
            var effect=MobEffectInstance.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();result.add(effect.save());
        }return result;
    }
    public static void validate(CompoundTag snapshot){
        if(!snapshot.contains("values",Tag.TAG_LIST)||!snapshot.contains("clock",Tag.TAG_STRING)
                ||!snapshot.contains("server_tick",Tag.TAG_LONG)||snapshot.getLong("server_tick")<0
                ||!snapshot.contains("wall_ms",Tag.TAG_LONG)||snapshot.getLong("wall_ms")<0)
            throw new IllegalStateException("Invalid saved effect-group snapshot; data retained");
        JEDPlayerGroupDefinition.Clock.valueOf(snapshot.getString("clock"));
        for(String key:List.of("values","pending","excluded"))if(snapshot.contains(key)){
            if(!(snapshot.get(key) instanceof ListTag list)||(!list.isEmpty()&&list.getElementType()!=(key.equals("excluded")?Tag.TAG_STRING:Tag.TAG_COMPOUND)))
                throw new IllegalStateException("Invalid saved effect list '"+key+"'; data retained");
        }
        var seen=new HashSet<String>();for(var raw:snapshot.getList("values",Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;
            if(!entry.contains("id",Tag.TAG_STRING)||ResourceLocation.tryParse(entry.getString("id"))==null||!seen.add(entry.getString("id")))
                throw new IllegalStateException("Invalid saved effect identifier; data retained");
            duration(entry);
        }
    }
    private static void duration(CompoundTag entry){
        if(entry.contains("duration")&&(!entry.contains("duration",Tag.TAG_INT)||entry.getInt("duration")< -1))
            throw new IllegalStateException("Invalid saved effect duration; data retained");
        if(entry.contains("hidden_effect")){
            if(!entry.contains("hidden_effect",Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid hidden effect; data retained");
            duration(entry.getCompound("hidden_effect"));
        }
    }
    public static ListTag elapsed(CompoundTag snapshot,long serverTick,long wallMillis){
        validate(snapshot);
        long ticks=switch(JEDPlayerGroupDefinition.Clock.valueOf(snapshot.getString("clock"))){
            case PAUSE->0;case SERVER_TIME->Math.max(0,serverTick-snapshot.getLong("server_tick"));
            case REAL_TIME->Math.max(0,wallMillis-snapshot.getLong("wall_ms"))/50;
        };
        var result=new ListTag();for(var raw:snapshot.getList("values",Tag.TAG_COMPOUND)){
            var aged=JEDEnvironmentEffects.ageSnapshot((CompoundTag)raw,ticks);if(aged!=null)result.add(aged);
        }return result;
    }
    public static CompoundTag ageNative(CompoundTag raw,long ticks){
        var result=raw.copy();var hidden=raw.contains("hidden_effect")?ageNative(raw.getCompound("hidden_effect"),ticks):null;
        result.remove("hidden_effect");if(hidden!=null)result.put("hidden_effect",hidden);
        int duration=raw.contains("duration")?raw.getInt("duration"):0;
        if(duration==-1)return result;
        if(ticks>=duration){if(hidden!=null&&raw.contains("id"))hidden.putString("id",raw.getString("id"));return hidden;}
        result.putInt("duration",(int)(duration-ticks));return result;
    }
    /** Duplicate effects use the native duration/amplifier/hidden-chain update operation. */
    public static ListTag combine(ListTag destination,ListTag incoming){
        var entries=new LinkedHashMap<String,CompoundTag>();
        for(var raw:destination)entries.put(((CompoundTag)raw).getString("id"),((CompoundTag)raw).copy());
        for(var raw:incoming){
            var tag=(CompoundTag)raw;String id=tag.getString("id");
            var old=entries.get(id);var added=MobEffectInstance.load(tag);
            var effect=old==null?null:MobEffectInstance.load(old);
            if(effect!=null&&added!=null){entries.put(id,JEDEnvironmentEffects.combine(old,tag));}
            else entries.put(id,tag.copy());
        }
        var result=new ListTag();entries.values().forEach(result::add);return result;
    }
    public static CompoundTag restore(ServerPlayer player,JEDPlayerGroupDefinition policy,ListTag desired,Set<ResourceLocation> equipment){
        return restore(player,policy,desired,equipment,true);
    }
    public static CompoundTag restoreExact(ServerPlayer player,JEDPlayerGroupDefinition policy,ListTag desired,Set<ResourceLocation> equipment){return restore(player,policy,desired,equipment,false);}
    private static CompoundTag restore(ServerPlayer player,JEDPlayerGroupDefinition policy,ListTag desired,Set<ResourceLocation> equipment,boolean preserveResidual){
        var plan=JEDEnvironmentEffects.restoring(player,desired,equipment,preserveResidual);desired=plan.desired();equipment=plan.equipment();
        var pending=new ListTag();var excluded=new ListTag();boolean authoritative=policy.effects().vetoes()==JEDPlayerGroupDefinition.Vetoes.AUTHORITATIVE;
        for(var effect:List.copyOf(player.getActiveEffects())){
            String id=effect.getEffect().unwrapKey().orElseThrow().location().toString();
            if(equipment.contains(ResourceLocation.parse(id)))continue;
            if(authoritative){JEDEnvironmentEffects.removeLoaded(player,effect);player.getPersistentData().getCompound(JEDEnvironmentEffects.DATA).remove(id);}
            else if(!player.removeEffect(effect.getEffect()))excluded.add(StringTag.valueOf(id));
        }
        for(var raw:desired){
            var tag=(CompoundTag)raw;var effect=MobEffectInstance.load(tag);
            if(equipment.contains(ResourceLocation.parse(tag.getString("id"))))continue;
            if(effect==null){pending.add(tag.copy());continue;}
            if(!authoritative&&excluded.contains(StringTag.valueOf(tag.getString("id")))){pending.add(tag.copy());continue;}
            if(authoritative){JEDEnvironmentEffects.installed(player,tag);player.getActiveEffectsMap().put(effect.getEffect(),effect);((LivingEntity)player).onEffectAdded(effect,null);}
            else {
                boolean permitted=net.neoforged.neoforge.common.CommonHooks.canMobEffectBeApplied(player,effect,null);
                if(permitted){var replaced=player.removeEffectNoUpdate(effect.getEffect());if(replaced!=null)((LivingEntity)player).onEffectRemoved(replaced);
                    JEDEnvironmentEffects.installed(player,tag);player.getActiveEffectsMap().put(effect.getEffect(),effect);((LivingEntity)player).onEffectAdded(effect,null);
                }else pending.add(tag.copy());
            }
        }
        var result=stamp(player,policy);result.put("values",desired.copy());result.put("pending",pending);result.put("excluded",excluded);
        if(!pending.isEmpty()||!excluded.isEmpty())LogUtils.getLogger().warn("JED effect group {} player {}: {} unapplied saved effects, {} blocked removals; saved data retained",policy.id(),player.getUUID(),pending.size(),excluded.size());
        return result;
    }
    public static void grant(ServerPlayer player,JsonArray effects){for(var raw:decode(effects)){var effect=MobEffectInstance.load((CompoundTag)raw);if(effect!=null)player.addEffect(effect);}}
    private JEDPlayerEffectState(){}
}
