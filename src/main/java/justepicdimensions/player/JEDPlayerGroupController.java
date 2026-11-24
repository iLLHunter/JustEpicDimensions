package justepicdimensions.player;

import java.util.*;
import com.mojang.serialization.JsonOps;
import justepicdimensions.definition.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;

/** Category transactions share the inventory controller's native player-data commit. */
public final class JEDPlayerGroupController {
    public static final String DEFAULT="@default";
    private record Policy(Map<JEDPlayerCategory,Map<ResourceLocation,JEDPlayerGroupDefinition>> groups,List<JEDPlayerEquipmentRule> equipment){}
    private record Binding(Map<JEDPlayerCategory,JEDPlayerGroupDefinition> groups,List<JEDPlayerEquipmentRule> equipment){}
    private record Loaded(float raw,float clamped,float maximum){}
    private static final Map<MinecraftServer,Policy> POLICIES=new IdentityHashMap<>();
    private static final Map<ServerPlayer,Binding> ACTIVE=new WeakHashMap<>();
    private static final Map<ServerPlayer,EnumMap<JEDPlayerCategory,CompoundTag>> DEPARTURES=new WeakHashMap<>();
    private static final Map<ServerPlayer,Binding> DEPARTURE_BINDINGS=new WeakHashMap<>();
    private static final Map<ServerPlayer,Loaded> LOADED=new WeakHashMap<>();
    private static final Map<ServerPlayer,Integer> TRAVELS=new WeakHashMap<>();

    public static void publish(MinecraftServer server,JEDPreparedDefinitions definitions){
        var groups=definitions.playerGroups(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()));
        var equipment=new ArrayList<JEDPlayerEquipmentRule>();
        groups.get(JEDPlayerCategory.EFFECTS).values().forEach(group->equipment.addAll(group.effects().equipment()));
        POLICIES.put(server,new Policy(groups,List.copyOf(equipment)));
        JEDPlayerGroupClock.bind(server,groups.get(JEDPlayerCategory.EFFECTS).values().stream()
                .anyMatch(group->group.effects().clock()==JEDPlayerGroupDefinition.Clock.SERVER_TIME));
    }
    private static Binding destination(ServerPlayer player){
        var policy=POLICIES.get(player.server);if(policy==null)return null;
        var selected=JEDPlayerStateController.definition(player).groups();
        var groups=new EnumMap<JEDPlayerCategory,JEDPlayerGroupDefinition>(JEDPlayerCategory.class);
        for(var category:JEDPlayerCategory.values()){
            var id=selected.get(category);var value=id==null?JEDPlayerGroupDefinition.defaults(category):policy.groups().get(category).get(id);
            if(id!=null&&value!=null)value=value.withId(justepicdimensions.instance.JEDInstanceController.scopedGroup(player.server,player.level().dimension().location(),category.key(),id));
            groups.put(category,Objects.requireNonNull(value,"Validated category group disappeared"));
        }
        return new Binding(Map.copyOf(groups),policy.equipment());
    }
    private static String key(JEDPlayerGroupDefinition policy){return policy.id()==null?DEFAULT:policy.id().toString();}
    private static EnumMap<JEDPlayerCategory,CompoundTag> live(ServerPlayer player,Binding binding,CompoundTag state){
        var values=new EnumMap<JEDPlayerCategory,CompoundTag>(JEDPlayerCategory.class);
        var equipment=JEDPlayerEquipmentEffects.owned(player,binding.equipment());
        for(var category:JEDPlayerCategory.values()){
            var definition=binding.groups().get(category);var data=state.getCompound("categories").getCompound(category.key());
            values.put(category,category==JEDPlayerCategory.EFFECTS?JEDPlayerEffectState.capture(player,definition,equipment,
                    data.getCompound("groups").getCompound(data.getString("active_group"))):JEDPlayerCategoryState.capture(player,category));
        }return values;
    }
    public static void beforeTravel(ServerPlayer player){
        beginTravel(player,ACTIVE.get(player),true);
    }
    public static void beforeTravel(ServerPlayer player,net.minecraft.world.level.portal.DimensionTransition transition){
        prepareTravel(player,transition);justepicdimensions.instance.JEDInstanceController.entering(player,transition.newLevel().dimension().location());
    }
    private static void prepareTravel(ServerPlayer player,net.minecraft.world.level.portal.DimensionTransition transition){
        var binding=ACTIVE.getOrDefault(player,DEPARTURE_BINDINGS.get(player));
        if(binding==null){
            var policy=POLICIES.get(player.server);if(policy==null)return;
            var state=JEDPlayerInventoryState.read(player);
            if(JEDPlayerStateController.definition(player,transition.newLevel().dimension().location()).groups().isEmpty()&&!state.contains("categories"))return;
            var definitions=new EnumMap<JEDPlayerCategory,JEDPlayerGroupDefinition>(JEDPlayerCategory.class);
            for(var category:JEDPlayerCategory.values()){
                var id=ResourceLocation.tryParse(state.getCompound("categories").getCompound(category.key()).getString("active_group"));
                definitions.put(category,id==null?JEDPlayerGroupDefinition.defaults(category):policy.groups().get(category).getOrDefault(justepicdimensions.instance.JEDInstanceController.baseGroup(id),JEDPlayerGroupDefinition.defaults(category)).withId(id));
            }
            binding=new Binding(Map.copyOf(definitions),policy.equipment());
        }
        beginTravel(player,binding,transition.newLevel()!=player.serverLevel());
    }
    private static void beginTravel(ServerPlayer player,Binding binding,boolean departing){
        if(binding==null)return;
        int depth=TRAVELS.getOrDefault(player,0);
        if(depth==0){var captured=live(player,binding,JEDPlayerInventoryState.read(player));repairLoadedHealth(player,captured);if(departing)justepicdimensions.environment.JEDEnvironmentEffects.departure(captured.get(JEDPlayerCategory.EFFECTS));DEPARTURES.put(player,captured);DEPARTURE_BINDINGS.put(player,binding);}
        TRAVELS.put(player,depth+1);
    }
    public static void afterTravel(ServerPlayer player){
        justepicdimensions.instance.JEDInstanceController.arrived(player);
        int depth=TRAVELS.getOrDefault(player,0);if(depth<=1){TRAVELS.remove(player);DEPARTURES.remove(player);DEPARTURE_BINDINGS.remove(player);}else TRAVELS.put(player,depth-1);
    }
    public static void loaded(ServerPlayer player,CompoundTag tag){
        if(tag.contains("Health",Tag.TAG_ANY_NUMERIC)){
            var health=tag.getCompound("NeoForgeData").getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                    .getCompound(JEDPlayerInventoryState.KEY).getCompound("categories").getCompound("health");
            float savedMaximum=health.getCompound("groups").getCompound(health.getString("active_group")).getFloat("maximum");
            if(!Float.isFinite(savedMaximum)||savedMaximum<=0)savedMaximum=Math.max(player.getMaxHealth(),tag.getFloat("Health"));
            LOADED.put(player,new Loaded(tag.getFloat("Health"),player.getHealth(),savedMaximum));
        }
    }
    public static void clonedHealth(ServerPlayer player,ServerPlayer original,boolean retained){
        if(retained)LOADED.put(player,new Loaded(original.getHealth(),player.getHealth(),original.getMaxHealth()));
    }
    public static void clearLoad(ServerPlayer player){LOADED.remove(player);}
    public static void cloned(ServerPlayer player,ServerPlayer original){
        var binding=ACTIVE.get(original);if(binding!=null){
            ACTIVE.put(player,binding);
            if(JEDPlayerInventoryState.read(original).getCompound("categories").contains("health")){
                var health=binding.groups().get(JEDPlayerCategory.HEALTH).health();
                JEDPlayerHealthLimits.health(player,health.maximum().name().toLowerCase(Locale.ROOT),health.maximumHearts());
            }
        }
    }
    public static Transaction prepare(ServerPlayer player,CompoundTag state,JEDPlayerStateController.Arrival arrival){
        if(!state.contains("categories")&&JEDPlayerStateController.definition(player).groups().isEmpty())return null;
        var target=destination(player);if(target==null)return null;
        if(state.contains("format_version")&&(state.getInt("format_version")<1||state.getInt("format_version")>2))throw invalid();
        if(state.contains("categories")&&!state.contains("categories",Tag.TAG_COMPOUND))throw invalid();
        boolean configured=target.groups().values().stream().anyMatch(group->group.id()!=null);
        if(!configured&&!state.contains("categories"))return null;
        var old=ACTIVE.getOrDefault(player,DEPARTURE_BINDINGS.getOrDefault(player,target));
        var before=live(player,old,state);var departure=DEPARTURES.getOrDefault(player,before);
        repairLoadedHealth(player,before);
        var categories=state.getCompound("categories");
        for(var category:JEDPlayerCategory.values()){
            var saved=categories.getCompound(category.key());
            if(categories.contains(category.key())&&!categories.contains(category.key(),Tag.TAG_COMPOUND))throw invalid();
            if(saved.contains("groups")&&!saved.contains("groups",Tag.TAG_COMPOUND))throw invalid();
            if(saved.contains("active_group")&&!saved.contains("active_group",Tag.TAG_STRING))throw invalid();
            for(var id:saved.getCompound("groups").getAllKeys()){
                if(!id.equals(DEFAULT)&&ResourceLocation.tryParse(id)==null)throw invalid();
                if(!saved.getCompound("groups").contains(id,Tag.TAG_COMPOUND))throw invalid();
                validate(category,saved.getCompound("groups").getCompound(id));
            }
        }
        return new Transaction(player,arrival,old,target,before,departure,state.copy());
    }
    private static void repairLoadedHealth(ServerPlayer player,EnumMap<JEDPlayerCategory,CompoundTag> before){
        var loaded=LOADED.get(player);
        if(loaded!=null&&before.get(JEDPlayerCategory.HEALTH).getFloat("health")==loaded.clamped()
                &&Float.isFinite(loaded.raw())&&loaded.raw()>=0&&Float.isFinite(loaded.maximum())&&loaded.maximum()>0){
            before.get(JEDPlayerCategory.HEALTH).putFloat("health",Math.min(loaded.raw(),loaded.maximum()));
            before.get(JEDPlayerCategory.HEALTH).putFloat("maximum",loaded.maximum());
        }
    }
    private static IllegalStateException invalid(){return new IllegalStateException("Invalid saved player category state; data retained");}
    private static void validate(JEDPlayerCategory category,CompoundTag value){if(category==JEDPlayerCategory.EFFECTS)JEDPlayerEffectState.validate(value);else JEDPlayerCategoryState.validate(category,value);}

    public static final class Transaction {
        private final ServerPlayer player;
        private final JEDPlayerStateController.Arrival arrival;
        private final Binding old,target;
        private final EnumMap<JEDPlayerCategory,CompoundTag> before,departure;
        private final CompoundTag original;
        private final boolean fresh;
        private final EnumMap<JEDPlayerCategory,CompoundTag> desired=new EnumMap<>(JEDPlayerCategory.class);
        private final EnumSet<JEDPlayerCategory> managed=EnumSet.noneOf(JEDPlayerCategory.class);
        private final EnumSet<JEDPlayerCategory> changed=EnumSet.noneOf(JEDPlayerCategory.class);
        private final EnumSet<JEDPlayerCategory> initializing=EnumSet.noneOf(JEDPlayerCategory.class);
        private CompoundTag effectResult;
        private Transaction(ServerPlayer player,JEDPlayerStateController.Arrival arrival,Binding old,Binding target,
                EnumMap<JEDPlayerCategory,CompoundTag> before,EnumMap<JEDPlayerCategory,CompoundTag> departure,CompoundTag original){
            this.player=player;this.arrival=arrival;this.old=old;this.target=target;this.before=before;this.departure=departure;this.original=original;
            this.fresh=!original.getBoolean("joined")&&!LOADED.containsKey(player);
        }
        /** Run after destination inventory and loadouts have reconciled their native attributes. */
        public void apply(CompoundTag state){
            var categories=state.getCompound("categories").copy();
            boolean respawn=arrival==JEDPlayerStateController.Arrival.RESPAWN;
            for(var category:JEDPlayerCategory.values()){
                var policy=target.groups().get(category);var data=categories.getCompound(category.key()).copy();
                if(policy.id()==null&&data.isEmpty())continue;
                managed.add(category);String to=key(policy);
                // A first installation adopts an existing player's live values in their current group.
                String from=data.contains("active_group")?data.getString("active_group")
                        :arrival==JEDPlayerStateController.Arrival.TRAVEL
                        ||((arrival==JEDPlayerStateController.Arrival.END_RETURN||arrival==JEDPlayerStateController.Arrival.RESPAWN)
                        &&!original.getString("dimension").equals(player.level().dimension().location().toString()))?DEFAULT:to;
                boolean switchGroup=!from.equals(to);if(switchGroup)changed.add(category);
                var snapshots=data.getCompound("groups").copy();
                var source=(respawn||!switchGroup?before:departure).get(category).copy();
                if(category==JEDPlayerCategory.EFFECTS&&arrival==JEDPlayerStateController.Arrival.RECONNECT&&LOADED.containsKey(player)&&snapshots.contains(from)){
                    var stored=snapshots.getCompound(from);
                    // capture() already aged deferred entries; only loaded native effects still need offline aging.
                    var pending=source.getList("pending",Tag.TAG_COMPOUND);var pendingIds=new HashSet<String>();
                    for(var raw:pending)pendingIds.add(((CompoundTag)raw).getString("id"));
                    var ordinary=source.copy();var values=new ListTag();for(var raw:source.getList("values",Tag.TAG_COMPOUND))if(!pendingIds.contains(((CompoundTag)raw).getString("id")))values.add(raw.copy());
                    ordinary.put("values",values);ordinary.putString("clock",stored.getString("clock"));ordinary.putLong("server_tick",stored.getLong("server_tick"));ordinary.putLong("wall_ms",stored.getLong("wall_ms"));
                    var aged=JEDPlayerEffectState.elapsed(ordinary,JEDPlayerGroupClock.ticks(player.server),System.currentTimeMillis());for(var raw:pending)aged.add(raw.copy());source.put("values",aged);
                }
                if(category==JEDPlayerCategory.EFFECTS&&!original.getString("dimension").equals(player.level().dimension().location().toString()))justepicdimensions.environment.JEDEnvironmentEffects.departure(source);
                snapshots.put(from,source);
                CompoundTag restore=null;
                if(switchGroup&&!(respawn&&policy.respawn()==JEDPlayerGroupDefinition.Respawn.NORMAL))restore=snapshots.contains(to)?snapshots.getCompound(to).copy():null;
                else restore=source.copy();
                boolean initialize=(switchGroup&&!snapshots.contains(to)&&!(respawn&&policy.respawn()==JEDPlayerGroupDefinition.Respawn.NORMAL))
                        ||(fresh&&policy.id()!=null);
                if(initialize)initializing.add(category);
                if(category==JEDPlayerCategory.HEALTH){
                    var health=policy.health();JEDPlayerHealthLimits.health(player,health.maximum().name().toLowerCase(Locale.ROOT),health.maximumHearts());
                }
                if(initialize&&category!=JEDPlayerCategory.HEALTH)restore=category==JEDPlayerCategory.EFFECTS?JEDPlayerEffectState.initial(player,policy):JEDPlayerCategoryState.initial(player,policy);
                if(restore==null)restore=source.copy();
                if(category==JEDPlayerCategory.EFFECTS&&switchGroup&&!(respawn&&policy.respawn()==JEDPlayerGroupDefinition.Respawn.NORMAL)){
                    var list=JEDPlayerEffectState.elapsed(restore,JEDPlayerGroupClock.ticks(player.server),System.currentTimeMillis());
                    if(policy.effects().carryOver()){
                        var incoming=source.getList("values",Tag.TAG_COMPOUND);
                        list=policy.effects().carryMode()==JEDPlayerGroupDefinition.Carry.REPLACE?incoming.copy():JEDPlayerEffectState.combine(list,incoming);
                    }
                    restore.put("values",list);
                }
                desired.put(category,restore);data.put("groups",snapshots);data.putString("active_group",to);categories.put(category.key(),data);
            }
            // Effect modifiers affect health capacity. Restoration is load-like; it never replays one-shot effect starts.
            if(managed.contains(JEDPlayerCategory.EFFECTS)){
                var policy=target.groups().get(JEDPlayerCategory.EFFECTS);
                if(changed.contains(JEDPlayerCategory.EFFECTS)||fresh||LOADED.containsKey(player))
                    effectResult=JEDPlayerEffectState.restore(player,policy,desired.get(JEDPlayerCategory.EFFECTS).getList("values",Tag.TAG_COMPOUND),JEDPlayerEquipmentEffects.owned(player,target.equipment()));
                else effectResult=desired.get(JEDPlayerCategory.EFFECTS);
            }
            LOADED.remove(player);
            for(var category:List.of(JEDPlayerCategory.XP,JEDPlayerCategory.FOOD,JEDPlayerCategory.HEALTH,JEDPlayerCategory.ABSORPTION))if(managed.contains(category)){
                var policy=target.groups().get(category);var value=desired.get(category);
                if(category==JEDPlayerCategory.HEALTH&&initializing.contains(category))value=JEDPlayerCategoryState.initial(player,policy);
                // Reapplying percentage on same-group travel would heal/damage when equipment changes.
                var restorePolicy=changed.contains(category)&&!(respawn&&policy.respawn()==JEDPlayerGroupDefinition.Respawn.NORMAL)?policy:JEDPlayerGroupDefinition.defaults(category);
                JEDPlayerCategoryState.restore(player,restorePolicy,value);
            }
            // Entry grants are new gameplay actions, after saved numeric state has been restored.
            boolean actualEntry=(arrival==JEDPlayerStateController.Arrival.INITIAL&&fresh)
                    ||((arrival==JEDPlayerStateController.Arrival.TRAVEL||arrival==JEDPlayerStateController.Arrival.END_RETURN||arrival==JEDPlayerStateController.Arrival.RESPAWN)
                    &&!original.getString("dimension").equals(player.level().dimension().location().toString()));
            if(managed.contains(JEDPlayerCategory.EFFECTS)&&actualEntry){
                var policy=target.groups().get(JEDPlayerCategory.EFFECTS);String dimension=player.level().dimension().location().toString();
                if(!JEDPlayerInventoryState.visited(original,"dimension",dimension)&&!JEDPlayerInventoryState.visited(original,"effect_dimension",dimension))JEDPlayerEffectState.grant(player,policy.effects().firstEntry());
                JEDPlayerEffectState.grant(player,policy.effects().everyEntry());JEDPlayerInventoryState.visit(state,"effect_dimension",dimension);
            }
            state.put("categories",categories);ACTIVE.put(player,target);captureInto(player,state,target,effectResult);
            player.connection.send(new ClientboundSetHealthPacket(player.getHealth(),player.getFoodData().getFoodLevel(),player.getFoodData().getSaturationLevel()));
        }
        public void rollback(){
            var health=old.groups().get(JEDPlayerCategory.HEALTH).health();JEDPlayerHealthLimits.health(player,health.maximum().name().toLowerCase(Locale.ROOT),health.maximumHearts());
            var effect=before.get(JEDPlayerCategory.EFFECTS);
            JEDPlayerEffectState.restoreExact(player,JEDPlayerGroupDefinition.defaults(JEDPlayerCategory.EFFECTS),effect.getList("values",Tag.TAG_COMPOUND),JEDPlayerEquipmentEffects.owned(player,old.equipment()));
            for(var category:List.of(JEDPlayerCategory.XP,JEDPlayerCategory.FOOD,JEDPlayerCategory.HEALTH,JEDPlayerCategory.ABSORPTION))JEDPlayerCategoryState.restore(player,JEDPlayerGroupDefinition.defaults(category),before.get(category));
            ACTIVE.put(player,old);
        }
    }
    private static void captureInto(ServerPlayer player,CompoundTag state,Binding binding,CompoundTag effects){
        var categories=state.getCompound("categories").copy();
        for(var category:JEDPlayerCategory.values())if(categories.contains(category.key())){
            var data=categories.getCompound(category.key()).copy();var snapshots=data.getCompound("groups").copy();String group=data.getString("active_group");
            var previous=effects!=null&&category==JEDPlayerCategory.EFFECTS?effects:snapshots.getCompound(group);
            var value=category==JEDPlayerCategory.EFFECTS?JEDPlayerEffectState.capture(player,binding.groups().get(category),JEDPlayerEquipmentEffects.owned(player,binding.equipment()),previous)
                    :JEDPlayerCategoryState.capture(player,category);
            snapshots.put(group,value);data.put("groups",snapshots);categories.put(category.key(),data);
        }state.put("categories",categories);
    }
    /** Invoked before native serialization, so live progress and inactive snapshots share its atomic file. */
    public static void saveEntity(net.minecraft.world.entity.Entity entity){if(entity instanceof ServerPlayer player)save(player);}
    public static void save(ServerPlayer player){
        var binding=ACTIVE.get(player);if(binding==null||TRAVELS.containsKey(player))return;
        var state=JEDPlayerInventoryState.read(player);if(!state.contains("categories"))return;
        captureInto(player,state,binding,null);JEDPlayerInventoryState.commit(player,state);
    }
    public static String describe(ServerPlayer player){
        var binding=ACTIVE.get(player);if(binding==null)return "categories disabled";
        var state=JEDPlayerInventoryState.read(player).getCompound("categories");var values=new ArrayList<String>();
        for(var category:JEDPlayerCategory.values())if(state.contains(category.key())){
            var policy=binding.groups().get(category);values.add(category.key()+"="+state.getCompound(category.key()).getString("active_group")
                    +" [respawn="+policy.respawn().name().toLowerCase(Locale.ROOT)
                    +(category==JEDPlayerCategory.HEALTH?", maximum="+policy.health().maximum().name().toLowerCase(Locale.ROOT)+", restore="+policy.health().restore().name().toLowerCase(Locale.ROOT):"")
                    +(category==JEDPlayerCategory.EFFECTS?", clock="+policy.effects().clock().name().toLowerCase(Locale.ROOT)+", vetoes="+policy.effects().vetoes().name().toLowerCase(Locale.ROOT):"")+"]");
        }return String.join("; ",values);
    }
    public static void stopped(MinecraftServer server){POLICIES.remove(server);ACTIVE.keySet().removeIf(p->p.server==server);DEPARTURES.keySet().removeIf(p->p.server==server);DEPARTURE_BINDINGS.keySet().removeIf(p->p.server==server);LOADED.keySet().removeIf(p->p.server==server);TRAVELS.keySet().removeIf(p->p.server==server);}
    private JEDPlayerGroupController(){}
}
