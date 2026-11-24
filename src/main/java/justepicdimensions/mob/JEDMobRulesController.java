package justepicdimensions.mob;

import java.util.*;
import java.lang.ref.WeakReference;
import justepicdimensions.definition.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.*;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Admission of new mobs only. Existing loads/transfers remain outside spawn policy enforcement. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDMobRulesController {
    public static final String FIRST_LOAD="justepicdimensions:generated_mob_pending";
    public record Prepared(Map<ResourceLocation,JEDMobSpawnPolicy> dimensions){}
    private record Intent(JEDMobSpawnSource source,UUID replaces){}
    private static volatile Map<MinecraftServer,Prepared> SERVERS=Map.of();
    private static final Map<Mob,Intent> INTENTS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Entity> RESTORED=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<ServerLevel,Map<EntityType<?>,Map<UUID,WeakReference<Mob>>>> COUNTS=new WeakHashMap<>();
    private static final Map<Mob,WeakReference<ServerLevel>> OWNERS=new WeakHashMap<>();
    public static Prepared prepare(JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var policies=candidate.mobSpawnPolicies();var dimensions=new HashMap<ResourceLocation,JEDMobSpawnPolicy>();
        definitions.forEach((id,d)->{if(d.mobs()!=null&&d.mobs().policy()!=null){var p=policies.get(d.mobs().policy());if(p==null)throw new JEDDefinitionException(id+": unknown mob spawning policy "+d.mobs().policy());dimensions.put(id,p);}});
        return new Prepared(Map.copyOf(dimensions));
    }
    public static synchronized void publish(MinecraftServer server,Prepared policy){var next=new IdentityHashMap<>(SERVERS);next.put(server,policy);SERVERS=Collections.unmodifiableMap(next);}
    public static JEDMobSpawnPolicy policy(ServerLevel level){var state=SERVERS.get(level.getServer());return state==null?null:state.dimensions().get(level.dimension().location());}
    private static boolean present(Mob mob){
        // NeoForge's legacy-load loop calls onAddedToLevel even after a canceled addition.
        // Registry membership, rather than that advisory flag, identifies an existing mob.
        return mob.level() instanceof ServerLevel level&&level.getServer().isSameThread()?level.entityManager.isLoaded(mob.getUUID()):mob.isAddedToLevel();
    }
    private static CompoundTag existingData(Mob mob){if(mob instanceof JEDMobPersistentAccess access)return access.jed$mobPersistentData();throw new IllegalStateException("JED requires matching Arbitrage mob-spawning hooks");}
    private static JEDMobSpawnSource firstSource(Mob mob){var data=existingData(mob);if(data==null||!data.contains(FIRST_LOAD,8))return null;try{return JEDMobSpawnSource.valueOf(data.getString(FIRST_LOAD));}catch(IllegalArgumentException ignored){return null;}}
    private static void attempt(Mob mob,JEDMobSpawnSource source,UUID replaces){attempt(mob,source,replaces,false);}
    private static void attempt(Mob mob,JEDMobSpawnSource source,UUID replaces,boolean generating){
        if(present(mob)||!(mob.level() instanceof ServerLevel level))return;
        var p=policy(level);if(p==null)return;RESTORED.remove(mob);INTENTS.put(mob,new Intent(source,replaces));
        if(generating&&p.rules().stream().anyMatch(r->r.matches(mob.getType(),source)&&!r.caps().isEmpty()))mob.getPersistentData().putString(FIRST_LOAD,source.name());
    }
    private static Map<EntityType<?>,Map<UUID,WeakReference<Mob>>> counts(ServerLevel level){return COUNTS.computeIfAbsent(level,key->new IdentityHashMap<>());}
    /** Observe committed UUID/section membership, including loaded but not yet visible chunks.
     * Record before creation callbacks can recursively attempt another spawn. */
    public static void registered(Object candidate,boolean added){
        if(!(candidate instanceof Mob mob))return;
        if(added){
            if(!(mob.level() instanceof ServerLevel level))return;
            OWNERS.put(mob,new WeakReference<>(level));counts(level).computeIfAbsent(mob.getType(),type->new HashMap<>()).put(mob.getUUID(),new WeakReference<>(mob));
            INTENTS.remove(mob);RESTORED.remove(mob);var data=existingData(mob);if(data!=null)data.remove(FIRST_LOAD);
        }else{
            var owner=OWNERS.remove(mob);var level=owner==null?null:owner.get();if(level==null)return;var entries=COUNTS.get(level);if(entries==null)return;
            var values=entries.get(mob.getType());if(values!=null){var reference=values.get(mob.getUUID());if(reference!=null&&reference.get()==mob)values.remove(mob.getUUID());if(values.isEmpty())entries.remove(mob.getType());}
        }
    }
    /** The native teleport admission API identifies existing arrivals; copying NBT alone does not. */
    public static void restored(Entity entity){if(entity instanceof Mob){INTENTS.remove(entity);RESTORED.add(entity);}}
    private static Entity registeredEntity(ServerLevel level,UUID uuid){for(var values:counts(level).values()){var reference=values.get(uuid);if(reference!=null)return reference.get();}return null;}
    public static int population(ServerLevel level,JEDMobSpawnPolicy.Selector selector){int total=0;for(var entry:counts(level).entrySet())if(selector.matches(entry.getKey()))total+=entry.getValue().size();return total;}
    public static boolean permits(ServerLevel level,EntityType<?> type,Vec3 position,JEDMobSpawnSource source,UUID replaces,boolean filters,boolean caps){
        var policy=policy(level);if(policy==null)return true;if(filters&&!policy.permits(type,source))return false;if(!caps||policy.rules().stream().noneMatch(rule->rule.matches(type,source)&&!rule.caps().isEmpty()))return true;
        if(!level.getServer().isSameThread())throw new IllegalStateException("Population caps require main-thread mob admission");
        for(var rule:policy.rules())if(rule.matches(type,source))for(var cap:rule.caps()){
            if(cap.scope().equals("dimension")){
                int count=population(level,rule.entities());var old=replaces==null?null:registeredEntity(level,replaces);
                if(old instanceof Mob&&rule.entities().matches(old.getType())&&counts(level).getOrDefault(old.getType(),Map.of()).containsKey(replaces))count--;
                if(count>=cap.limit())return false;
            }else{
                double radius=cap.radius(),squared=radius*radius;
                for(var player:level.players())if(!player.isSpectator()&&player.position().distanceToSqr(position)<=squared){
                    if(cap.limit()==0)return false;int count=0;
                    // Iterate matching registered types, including hidden sections. This avoids
                    // degenerate tiny boxes and unbounded empty-column native queries.
                    for(var entry:counts(level).entrySet())if(rule.entities().matches(entry.getKey()))for(var reference:entry.getValue().values()){
                        var mob=reference.get();if(mob!=null&&!mob.getUUID().equals(replaces)&&mob.position().distanceToSqr(player.position())<=squared&&++count>=cap.limit())return false;
                    }
                }
            }
        }
        return true;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void position(MobSpawnEvent.PositionCheck event){
        var mob=event.getEntity();if(present(mob))return;var source=JEDMobSpawnSource.of(event.getSpawnType());boolean generating=source==JEDMobSpawnSource.CHUNK_GENERATION||!(event.getLevel() instanceof ServerLevel);attempt(mob,source,null,generating);
        if(!permits(event.getLevel().getLevel(),mob.getType(),mob.position(),source,null,true,!generating))event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void finalized(FinalizeSpawnEvent event){
        var root=event.getEntity();if(present(root))return;var source=JEDMobSpawnSource.of(event.getSpawnType());boolean generating=source==JEDMobSpawnSource.CHUNK_GENERATION||!(event.getLevel() instanceof ServerLevel);
        attempt(root,source,null,generating);for(var entity:root.getIndirectPassengers())if(entity instanceof Mob mob&&!INTENTS.containsKey(mob))attempt(mob,source,null,generating);
        if(!permits(event.getLevel().getLevel(),root.getType(),root.position(),source,null,true,!generating))event.setSpawnCancelled(true);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void baby(BabyEntitySpawnEvent event){
        if(event.isCanceled()||event.getChild()==null||!(event.getParentA().level() instanceof ServerLevel level))return;
        var child=event.getChild();attempt(child,JEDMobSpawnSource.BREEDING,null);
        if(!permits(level,child.getType(),event.getParentA().position(),JEDMobSpawnSource.BREEDING,null,true,true))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void join(EntityJoinLevelEvent event){
        if(event.isCanceled()||!(event.getLevel() instanceof ServerLevel level)||!(event.getEntity() instanceof Mob mob)||present(mob)||RESTORED.contains(mob))return;
        var generatedSource=firstSource(mob);boolean generated=generatedSource!=null;if(event.loadedFromDisk()&&!generated)return;
        var intent=INTENTS.get(mob);var source=generated?generatedSource:intent==null?JEDMobSpawnSource.UNKNOWN:intent.source();
        for(var child:mob.getIndirectPassengers())if(child instanceof Mob passenger&&!INTENTS.containsKey(passenger))attempt(passenger,source,null,generated);
        if(!generated&&intent!=null&&intent.replaces()!=null)return; // conversion was admitted before native source equipment mutation
        if(!permits(level,mob.getType(),mob.position(),source,intent==null?null:intent.replaces(),!generated,true))event.setCanceled(true);
    }
    public static Entity conversion(Entity old,Entity target){
        if(!(old instanceof Mob)||!(target instanceof Mob mob)||!(old.level() instanceof ServerLevel level))return target;
        if(!permits(level,mob.getType(),old.position(),JEDMobSpawnSource.CONVERSION,old.getUUID(),true,true))return null;
        attempt(mob,JEDMobSpawnSource.CONVERSION,old.getUUID());return target;
    }
    /** Used for source APIs which create NBT mobs without finalization (commands and egg offspring). */
    public static boolean addGroup(ServerLevel level,Entity root,JEDMobSpawnSource source){
        for(var entity:root.getSelfAndPassengers().toList())if(entity instanceof Mob mob&&(entity==root||!INTENTS.containsKey(mob)))attempt(mob,source,null);
        return level.tryAddFreshEntityWithPassengers(root);
    }
    public static void addFreshGroup(ServerLevel level,Entity root,JEDMobSpawnSource source){for(var entity:root.getSelfAndPassengers().toList())if(entity instanceof Mob mob&&(entity==root||!INTENTS.containsKey(mob)))attempt(mob,source,null);level.addFreshEntityWithPassengers(root);}
    public static boolean generating(ServerLevelAccessor region,Entity entity){
        if(!(entity instanceof Mob mob))return true;var known=INTENTS.get(mob);var source=known==null?JEDMobSpawnSource.UNKNOWN:known.source();attempt(mob,source,known==null?null:known.replaces(),true);
        return permits(region.getLevel(),mob.getType(),mob.position(),source,null,true,false);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void potential(LevelEvent.PotentialSpawns event){
        if(!(event.getLevel() instanceof ServerLevel level))return;var p=policy(level);if(p==null)return;
        var original=event.getSpawnerDataList();var changed=JEDMobWeights.apply(p,event.getMobCategory(),JEDMobSpawnSource.NATURAL,original);if(changed==original)return;
        for(var entry:List.copyOf(original))event.removeSpawnerData(entry);for(var entry:changed)event.addSpawnerData(entry);
    }
    public static WeightedRandomList<MobSpawnSettings.SpawnerData> generationList(WeightedRandomList<MobSpawnSettings.SpawnerData> original,ServerLevelAccessor level){
        var p=policy(level.getLevel());if(p==null)return original;var result=JEDMobWeights.apply(p,MobCategory.CREATURE,JEDMobSpawnSource.CHUNK_GENERATION,original.unwrap());return result==original.unwrap()?original:WeightedRandomList.create(result);
    }
    @SubscribeEvent public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){if(!"verified".equals(System.getProperty("jed.mob.rules.entity"))||!"verified".equals(System.getProperty("jed.mob.rules.registry"))||!"verified".equals(System.getProperty("jed.mob.rules.travel")))throw new IllegalStateException("JED requires matching Arbitrage mob-spawning hooks");}
    @SubscribeEvent public static void stop(ServerStoppedEvent event){synchronized(JEDMobRulesController.class){var next=new IdentityHashMap<>(SERVERS);next.remove(event.getServer());SERVERS=Collections.unmodifiableMap(next);}COUNTS.keySet().removeIf(level->level.getServer()==event.getServer());OWNERS.values().removeIf(reference->{var level=reference.get();return level==null||level.getServer()==event.getServer();});synchronized(INTENTS){INTENTS.keySet().removeIf(mob->mob.level() instanceof ServerLevel level&&level.getServer()==event.getServer());}synchronized(RESTORED){RESTORED.removeIf(entity->entity.level() instanceof ServerLevel level&&level.getServer()==event.getServer());}}
    private JEDMobRulesController(){}
}
