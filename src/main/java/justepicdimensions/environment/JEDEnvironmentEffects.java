package justepicdimensions.environment;

import java.util.*;
import justepicdimensions.player.JEDPlayerEffectState;
import net.minecraft.core.Holder;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.LivingEntity;

/** Native effects retain their gameplay implementation; this sidecar records who contributed them. */
public final class JEDEnvironmentEffects {
    public static final String META="jed_environment", DATA="jed_environment_effects";
    private record Application(LivingEntity entity,MobEffectInstance instance,CompoundTag source){}
    private static final ThreadLocal<Application> APPLICATION=new ThreadLocal<>();
    private static final ThreadLocal<Set<MobEffectInstance>> REMOVING=ThreadLocal.withInitial(()->Collections.newSetFromMap(new IdentityHashMap<>()));
    private static CompoundTag ledger(LivingEntity entity){var data=entity.getPersistentData();if(!data.contains(DATA,Tag.TAG_COMPOUND))data.put(DATA,new CompoundTag());return data.getCompound(DATA);}
    private static String id(MobEffectInstance effect){return effect.getEffect().unwrapKey().orElseThrow().location().toString();}
    private static MobEffectInstance copy(MobEffectInstance effect){return Objects.requireNonNull(MobEffectInstance.load((CompoundTag)effect.save()));}
    private static CompoundTag save(MobEffectInstance effect){return (CompoundTag)effect.save();}
    private static CompoundTag plain(CompoundTag tag){var result=tag.copy();result.remove(META);return result;}
    private static CompoundTag initial(MobEffectInstance active){var state=new CompoundTag();if(active!=null)state.put("base",save(active));state.put("sources",new ListTag());return state;}
    public static void apply(LivingEntity entity,JEDEnvironmentPolicy policy,JEDEnvironmentPolicy.Rule rule,MobEffectInstance template){
        var effect=copy(template);var source=new CompoundTag();source.putString("key",rule.key(policy.id()));source.putString("dimension",entity.level().dimension().location().toString());source.putBoolean("immediate",rule.immediateCleanup());source.putBoolean("carry",rule.carryAcrossGroups());
        var previous=APPLICATION.get();APPLICATION.set(new Application(entity,effect,source));try{entity.addEffect(effect);}finally{if(previous==null)APPLICATION.remove();else APPLICATION.set(previous);}
    }
    /** Called after the Added event has completed, before native put/update. Vetoed additions never reach it. */
    public static void accepted(LivingEntity entity,MobEffectInstance effect){
        if(entity.level().isClientSide)return;var own=APPLICATION.get();boolean environmental=own!=null&&own.entity()==entity&&own.instance()==effect;
        var data=entity.getPersistentData().getCompound(DATA);String id=id(effect);if(!environmental&&!data.contains(id))return;
        data=ledger(entity);var state=data.contains(id)?data.getCompound(id):initial(entity.getEffect(effect.getEffect()));
        if(environmental){
            var sources=state.getList("sources",Tag.TAG_COMPOUND);CompoundTag source=null;for(var raw:sources){var s=(CompoundTag)raw;if(s.getString("key").equals(own.source().getString("key"))&&s.getString("dimension").equals(own.source().getString("dimension"))){source=s;break;}}
            if(source==null){source=own.source().copy();sources.add(source);}source.putBoolean("immediate",own.source().getBoolean("immediate"));source.putBoolean("carry",own.source().getBoolean("carry"));source.put("effect",merge(source.contains("effect")?source.getCompound("effect"):null,save(effect)));source.putLong("order",next(state));state.put("sources",sources);
        }else{state.put("base",merge(state.contains("base")?state.getCompound("base"):null,save(effect)));state.putLong("base_order",next(state));}
        data.put(id,state);
    }
    private static long next(CompoundTag state){long next=state.getLong("next")+1;state.putLong("next",next);return next;}
    /** Native force replacement has no Added event; it replaces provenance too. */
    public static void forced(LivingEntity entity,MobEffectInstance effect){if(!entity.level().isClientSide&&entity.getEffect(effect.getEffect())==effect)entity.getPersistentData().getCompound(DATA).remove(id(effect));}
    public static void removed(LivingEntity entity,MobEffectInstance effect){if(!entity.level().isClientSide&&!REMOVING.get().contains(effect))entity.getPersistentData().getCompound(DATA).remove(id(effect));}
    public static void removedWithoutUpdate(MobEffectInstance effect,LivingEntity entity){if(effect!=null)removed(entity,effect);}
    /** One call per native active-root tick, including hidden-chain aging, with no offline gameplay. */
    public static void tick(MobEffectInstance root,LivingEntity entity){
        if(entity.level().isClientSide||entity.getEffect(root.getEffect())!=root)return;var data=entity.getPersistentData().getCompound(DATA);String id=id(root);if(!data.contains(id))return;var state=data.getCompound(id);ageState(state,1);
    }
    private static void ageState(CompoundTag state,long ticks){
        if(state.contains("base")){var base=JEDPlayerEffectState.ageNative(state.getCompound("base"),ticks);if(base==null)state.remove("base");else state.put("base",base);}
        var sources=state.getList("sources",Tag.TAG_COMPOUND);for(int i=sources.size()-1;i>=0;i--){var source=sources.getCompound(i);var value=JEDPlayerEffectState.ageNative(source.getCompound("effect"),ticks);if(value==null)sources.remove(i);else source.put("effect",value);}state.put("sources",sources);
    }
    private static CompoundTag merge(CompoundTag old,CompoundTag added){if(old==null)return plain(added);var before=MobEffectInstance.load(old);var after=MobEffectInstance.load(added);if(before==null||after==null)return plain(added);before.update(after);return save(before);}
    public static CompoundTag compose(CompoundTag state){
        record Entry(long order,CompoundTag effect){}var entries=new ArrayList<Entry>();if(state.contains("base"))entries.add(new Entry(state.getLong("base_order"),state.getCompound("base")));
        for(var raw:state.getList("sources",Tag.TAG_COMPOUND)){var s=(CompoundTag)raw;entries.add(new Entry(s.getLong("order"),s.getCompound("effect")));}entries.sort(Comparator.comparingLong(Entry::order));CompoundTag result=null;for(var entry:entries)result=merge(result,entry.effect());if(result!=null&&!state.getList("sources",Tag.TAG_COMPOUND).isEmpty())result.put(META,state.copy());return result;
    }
    public static CompoundTag snapshot(LivingEntity entity,MobEffectInstance effect,boolean equipment){
        var data=entity.getPersistentData().getCompound(DATA);String id=id(effect);if(!data.contains(id))return equipment?null:save(effect);var state=data.getCompound(id).copy();if(equipment)state.remove("base");return compose(state);
    }
    public static CompoundTag ageSnapshot(CompoundTag raw,long ticks){if(!raw.contains(META))return JEDPlayerEffectState.ageNative(raw,ticks);var state=raw.getCompound(META).copy();ageState(state,ticks);return compose(state);}
    public static CompoundTag combine(CompoundTag first,CompoundTag second){
        if(!first.contains(META)&&!second.contains(META))return merge(first,second);
        var a=first.contains(META)?first.getCompound(META).copy():initial(MobEffectInstance.load(first));var b=second.contains(META)?second.getCompound(META):initial(MobEffectInstance.load(second));
        if(b.contains("base")){a.put("base",merge(a.contains("base")?a.getCompound("base"):null,b.getCompound("base")));a.putLong("base_order",next(a));}
        var sources=a.getList("sources",Tag.TAG_COMPOUND);for(var raw:b.getList("sources",Tag.TAG_COMPOUND)){var incoming=((CompoundTag)raw).copy();for(int i=sources.size()-1;i>=0;i--){var old=sources.getCompound(i);if(old.getString("key").equals(incoming.getString("key"))&&old.getString("dimension").equals(incoming.getString("dimension"))){incoming.put("effect",merge(old.getCompound("effect"),incoming.getCompound("effect")));sources.remove(i);}}incoming.putLong("order",next(a));sources.add(incoming);}a.put("sources",sources);return compose(a);
    }
    public static void departure(CompoundTag snapshot){
        for(String key:List.of("values","pending"))if(snapshot.contains(key)){
            var result=new ListTag();for(var raw:snapshot.getList(key,Tag.TAG_COMPOUND)){var value=(CompoundTag)raw;if(value.contains(META)){var state=value.getCompound(META).copy();var sources=state.getList("sources",Tag.TAG_COMPOUND);sources.removeIf(s->((CompoundTag)s).getBoolean("immediate")||((CompoundTag)s).getBoolean("carry"));state.put("sources",sources);value=compose(state);}if(value!=null)result.add(value.copy());}snapshot.put(key,result);
        }
    }
    public static ListTag carry(LivingEntity entity){
        var result=new ListTag();var data=entity.getPersistentData().getCompound(DATA);for(String id:data.getAllKeys()){var state=data.getCompound(id).copy();state.remove("base");var sources=state.getList("sources",Tag.TAG_COMPOUND);sources.removeIf(s->!((CompoundTag)s).getBoolean("carry")||((CompoundTag)s).getBoolean("immediate"));state.put("sources",sources);var value=compose(state);if(value!=null)result.add(value);}return result;
    }
    public record Restoration(ListTag desired,Set<ResourceLocation> equipment){}
    public static Restoration restoring(LivingEntity entity,ListTag desired,Set<ResourceLocation> equipment,boolean preserveResidual){
        var values=preserveResidual?JEDPlayerEffectState.combine(desired,carry(entity)):desired.copy();var touched=new HashSet<String>();
        for(var effect:entity.getActiveEffects())if(entity.getPersistentData().getCompound(DATA).contains(id(effect)))touched.add(id(effect));
        for(var raw:values)if(((CompoundTag)raw).contains(META))touched.add(((CompoundTag)raw).getString("id"));
        var retained=new ListTag();for(var effect:entity.getActiveEffects())if(touched.contains(id(effect))&&equipment.contains(ResourceLocation.parse(id(effect)))){
            var state=entity.getPersistentData().getCompound(DATA).getCompound(id(effect));var base=state.contains("base")?state.getCompound("base").copy():entity.getPersistentData().getCompound(DATA).contains(id(effect))?null:save(effect);if(base!=null)retained.add(base);
        }
        values=JEDPlayerEffectState.combine(retained,values);var remaining=new HashSet<>(equipment);remaining.removeIf(id->touched.contains(id.toString()));return new Restoration(values,Set.copyOf(remaining));
    }
    public static void installed(LivingEntity entity,CompoundTag tag){var data=ledger(entity);String id=tag.getString("id");if(tag.contains(META))data.put(id,tag.getCompound(META).copy());else data.remove(id);}
    public static void removeLoaded(LivingEntity entity,MobEffectInstance effect){
        var set=REMOVING.get();boolean added=set.add(effect);try{entity.removeEffectNoUpdate(effect.getEffect());entity.onEffectRemoved(effect);}finally{if(added)set.remove(effect);if(set.isEmpty())REMOVING.remove();}
    }
    /** Return false when a native removal/application veto keeps the old contribution active. */
    private static boolean replace(LivingEntity entity,Holder<MobEffect> type,CompoundTag state){
        var value=compose(state);var desired=value==null?null:MobEffectInstance.load(value);var old=entity.getEffect(type);
        if(desired!=null&&!net.neoforged.neoforge.common.CommonHooks.canMobEffectBeApplied(entity,desired,null))return blocked(entity,type,"application");
        if(old!=null&&net.neoforged.neoforge.event.EventHooks.onEffectRemoved(entity,type,null))return blocked(entity,type,"removal");
        state.remove("cleanup_blocked");
        if(desired==null&&old!=null)removeLoaded(entity,old);var data=ledger(entity);String id=type.unwrapKey().orElseThrow().location().toString();
        if(value==null||!value.contains(META))data.remove(id);else data.put(id,state);
        if(desired!=null){entity.getActiveEffectsMap().put(type,desired);if(old==null)entity.onEffectAdded(desired,null);else{desired.copyBlendState(old);entity.onEffectUpdated(desired,old.getAmplifier()!=desired.getAmplifier(),null);}}return true;
    }
    private static boolean blocked(LivingEntity entity,Holder<MobEffect> type,String stage){
        String id=type.unwrapKey().orElseThrow().location().toString();var state=ledger(entity).getCompound(id);
        if(!state.getBoolean("cleanup_blocked")){state.putBoolean("cleanup_blocked",true);com.mojang.logging.LogUtils.getLogger().warn("JED environmental cleanup blocked by effect {} veto: effect {}, entity {} in {}; contribution retained for retry",stage,id,entity.getUUID(),entity.level().dimension().location());}return false;
    }
    public static void cleanup(LivingEntity entity,Set<String> active){
        var data=entity.getPersistentData().getCompound(DATA);for(String id:List.copyOf(data.getAllKeys())){
            var state=data.getCompound(id).copy();var sources=state.getList("sources",Tag.TAG_COMPOUND);boolean changed=sources.removeIf(raw->{var s=(CompoundTag)raw;return s.getBoolean("immediate")&&(!s.getString("dimension").equals(entity.level().dimension().location().toString())||!active.contains(s.getString("key")));});
            if(!changed)continue;state.put("sources",sources);var effect=entity.getActiveEffects().stream().filter(e->id(e).equals(id)).findFirst().orElse(null);if(effect!=null)replace(entity,effect.getEffect(),state);else data.remove(id);
        }
    }
    private JEDEnvironmentEffects(){}
}
