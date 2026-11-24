package justepicdimensions.player;

import java.util.*;
import com.mojang.logging.LogUtils;
import justepicdimensions.definition.*;
import justepicdimensions.spawn.JEDPlayerStateTracker;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid="justepicdimensions")
public final class JEDPlayerStateController {
    public enum Arrival { INITIAL, TRAVEL, RESPAWN, RECONNECT, END_RETURN }
    private record Policy(JEDPlayerOptions options,Map<ResourceLocation,JEDPlayerDefinition> dimensions,Map<ResourceLocation,JEDLoadoutDefinition> kits) {}
    public record Outcome(String group,String trigger,boolean applied,int skipped,String reason,long elapsedNanos) {}
    private static final Map<MinecraftServer,Policy> POLICIES=new IdentityHashMap<>();
    private static final Map<ServerPlayer,Outcome> LAST=new WeakHashMap<>();
    private static final ThreadLocal<IdentityHashMap<ServerPlayer,Integer>> EVENTS=ThreadLocal.withInitial(IdentityHashMap::new);
    public static void validate(MinecraftServer server,JEDPreparedDefinitions prepared){prepared.loadouts(RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,server.registryAccess()));}
    public static void publish(MinecraftServer server,JEDPreparedDefinitions prepared,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var policies=new TreeMap<ResourceLocation,JEDPlayerDefinition>();definitions.forEach((id,value)->policies.put(id,value.player()));
        POLICIES.put(server,new Policy(prepared.playerOptions(),Map.copyOf(policies),prepared.loadouts(RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,server.registryAccess()))));
        JEDPlayerGroupController.publish(server,prepared);
    }
    private static Policy policy(ServerPlayer player){return Objects.requireNonNull(POLICIES.get(player.server),"JED player policies are not bound");}
    public static JEDPlayerOptions options(ServerPlayer player){return policy(player).options();}
    public static JEDPlayerDefinition definition(ServerPlayer player){return policy(player).dimensions().getOrDefault(player.level().dimension().location(),JEDPlayerDefinition.DEFAULT);}
    public static JEDPlayerDefinition definition(ServerPlayer player,ResourceLocation dimension){return policy(player).dimensions().getOrDefault(dimension,JEDPlayerDefinition.DEFAULT);}
    public static String group(ServerPlayer player){return group(player,player.level().dimension().location());}
    public static String group(ServerPlayer player,ResourceLocation dimension){var p=policy(player);if(p.options().inventoryGroups())return justepicdimensions.instance.JEDInstanceController.scopedInventory(player.server,dimension,definition(player,dimension).inventoryGroup());var saved=JEDPlayerInventoryState.read(player);return saved.contains("active_group")?saved.getString("active_group"):JEDPlayerDefinition.DEFAULT_GROUP;}
    public static Outcome last(ServerPlayer player){return LAST.get(player);}
    /** Rehearse only inventory operations, using native adapter sizing/validation, before any travel.
     * No state/history, mode, health, XP, claims or chunk-loading policy is committed here. */
    public static void requireRetainedRoom(ServerPlayer player,ResourceLocation target){
        if(!player.server.isSameThread())throw new IllegalStateException("Inventory preflight requires the server thread");
        var p=policy(player);var original=JEDPlayerInventoryState.read(player);
        if(p.options().globalInventoryItems().isEmpty())return;
        JEDInventoryGroupManager.closeMenu(player);
        var retained=JEDInventoryRetention.begin(player,original,target);
        if(!retained.relevant(player,original,target))return;
        // Roll back only the actual live slots/metadata. Copying saved deferred receipts here
        // would materialize them again during rollback while their original state remained saved.
        var before=JEDInventoryAdapterRegistry.capture(player,new CompoundTag());
        try{
            var definition=definition(player,target);String group=group(player,target);
            var prepared=JEDInventoryGroupManager.prepare(player,original,group,retained);
            boolean hardcore=JEDPlayerStateTracker.get(player.server).player(player.getUUID()).hardcoreDead;
            String trigger=trigger(definition,prepared.state(),Arrival.TRAVEL,target,group,hardcore);
            var rule=definition.loadouts().get(trigger);
            if(rule!=null){
                retained.destination(player,replaces(rule));
                var restored=JEDInventoryAdapterRegistry.capture(player,prepared.restored());
                try{JEDLoadoutController.execute(player,Objects.requireNonNull(p.kits().get(rule.kit())),rule.mode());}
                catch(RuntimeException error){JEDInventoryAdapterRegistry.restore(player,restored);}
            }
            retained.restore(player,target);
        }finally{JEDInventoryAdapterRegistry.restore(player,before);JEDInventoryAdapterRegistry.sync(player);}
    }
    private static boolean replaces(JEDPlayerDefinition.Rule rule){return rule.mode()==JEDPlayerDefinition.Mode.REPLACE_SLOTS||rule.mode()==JEDPlayerDefinition.Mode.REPLACE_CONTAINERS;}
    private static String trigger(JEDPlayerDefinition definition,CompoundTag state,Arrival arrival,ResourceLocation dimension,String group,boolean hardcore){
        if(arrival==Arrival.RECONNECT||arrival==Arrival.END_RETURN||hardcore)return "";
        if(arrival==Arrival.RESPAWN&&definition.loadouts().containsKey("respawn"))return "respawn";
        if(definition.loadouts().containsKey("first_entry")){
            var first=definition.loadouts().get("first_entry");String scope=first.dimensionScope()?"dimension":"group";
            if(!JEDPlayerInventoryState.visited(state,scope,first.dimensionScope()?dimension.toString():group))return "first_entry";
        }
        return definition.loadouts().containsKey("every_entry")?"every_entry":"";
    }
    public static boolean keepInventory(boolean nativeValue,ServerPlayer original){return original.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);}
    public static void nativeLogin(net.minecraft.world.entity.player.Player input){var player=(ServerPlayer)input;nativeEvent(player,()->EventHooks.firePlayerLoggedIn(player),JEDPlayerInventoryState.read(player).getBoolean("joined")?Arrival.RECONNECT:Arrival.INITIAL);}
    public static void nativeRespawn(ServerPlayer player,boolean end){nativeEvent(player,()->EventHooks.firePlayerRespawnEvent(player,end),end?Arrival.END_RETURN:Arrival.RESPAWN);}
    public static void nativeTravel(net.minecraft.world.entity.player.Player input,ResourceKey<Level> from,ResourceKey<Level> to){var player=(ServerPlayer)input;nativeEvent(player,()->EventHooks.firePlayerChangedDimensionEvent(player,from,to),Arrival.TRAVEL);}
    private static void nativeEvent(ServerPlayer player,Runnable event,Arrival arrival){
        var events=EVENTS.get();int depth=events.getOrDefault(player,0);events.put(player,depth+1);
        try{event.run();}finally{if(depth==0)events.remove(player);else events.put(player,depth);if(events.isEmpty())EVENTS.remove();}
        // A listener may redirect this same player again. Only the final actual arrival is processed.
        if(depth==0)arrive(player,arrival);
    }
    public static Outcome arrive(ServerPlayer player,Arrival arrival){
        var transaction=JEDPlayerGroupController.prepare(player,JEDPlayerInventoryState.read(player),arrival);
        var inventory=new CompoundTag[1];
        try{return arrivePrepared(player,arrival,transaction,inventory);}
        catch(RuntimeException error){
            try{if(inventory[0]!=null)JEDInventoryAdapterRegistry.restore(player,inventory[0]);if(transaction!=null)transaction.rollback();JEDInventoryAdapterRegistry.sync(player);}
            catch(RuntimeException rollback){error.addSuppressed(rollback);}
            throw error;
        }finally{if(transaction==null)JEDPlayerGroupController.clearLoad(player);}
    }
    private static Outcome arrivePrepared(ServerPlayer player,Arrival arrival,JEDPlayerGroupController.Transaction categories,CompoundTag[] rollback){
        if(!player.server.isSameThread())throw new IllegalStateException("Player-state arrivals require the server thread");
        long started=System.nanoTime();var p=policy(player);var definition=definition(player);
        var original=JEDPlayerInventoryState.read(player);String group=group(player);
        if(original.contains("normal_game_mode")&&GameType.byName(original.getString("normal_game_mode"),null)==null)throw new IllegalStateException("Invalid saved normal game mode; player state retained");
        var retained=JEDInventoryRetention.begin(player,original,player.level().dimension().location());
        var prepared=JEDInventoryGroupManager.prepare(player,original,group,retained);var state=prepared.state();
        if(rollback!=null)rollback[0]=prepared.before();
        boolean hardcore=JEDPlayerStateTracker.get(player.server).player(player.getUUID()).hardcoreDead;
        boolean eligible=arrival!=Arrival.RECONNECT&&arrival!=Arrival.END_RETURN&&!hardcore;
        String trigger="";JEDPlayerDefinition.Rule rule=null;String reason="no matching trigger";boolean applied=false;int skipped=0;boolean complete=true;
        if(eligible){
            trigger=trigger(definition,state,arrival,player.level().dimension().location(),group,hardcore);
            rule=definition.loadouts().get(trigger);
            if(rule!=null){
                reason=trigger.equals("respawn")?respawnSkip(player,state,group,rule):null;
                if(reason==null){
                    retained.destination(player,replaces(rule));
                    var restored=JEDInventoryAdapterRegistry.capture(player,prepared.restored());
                    try{
                        var kit=Objects.requireNonNull(p.kits().get(rule.kit()),"Validated loadout disappeared");
                        var unplaced=JEDLoadoutController.execute(player,kit,rule.mode());
                        applied=true;skipped=unplaced.size();reason=skipped==0?"applied":"applied with unavailable slots skipped";
                        for(var missing:unplaced){
                            LogUtils.getLogger().warn("JED loadout {} for player {} in {}: skipped item {} due to {} slot {}",kit.id(),player.getUUID(),player.level().dimension().location(),missing.item().getHoverName().getString(),missing.missing()?"missing":"no available",missing.slot());
                            player.sendSystemMessage(Component.translatable(missing.missing()?"justepicdimensions.loadout.skipped_slot":"justepicdimensions.loadout.full_slot",missing.item().getHoverName(),Component.literal(missing.slot())));
                        }
                    }catch(RuntimeException error){
                        complete=false;reason="loadout failed: "+error.getMessage();
                        JEDInventoryAdapterRegistry.restore(player,restored);
                        LogUtils.getLogger().error("JED loadout {} failed for player {}; restored destination inventory retained and history uncommitted",rule.kit(),player.getUUID(),error);
                    }
                }else LogUtils.getLogger().info("JED respawn loadout {} skipped for player {}: {}",rule.kit(),player.getUUID(),reason);
            }
        }
        retained.restore(player,player.level().dimension().location());
        if(eligible&&complete){JEDPlayerInventoryState.visit(state,"group",group);JEDPlayerInventoryState.visit(state,"dimension",player.level().dimension().location().toString());}
        if(categories!=null)categories.apply(state);
        modes(player,state,p.options(),definition,hardcore,original.getBoolean("joined"));
        JEDInventoryGroupManager.capture(player,state,group,prepared.restored());state.putBoolean("joined",true);state.putString("dimension",player.level().dimension().location().toString());
        if(arrival==Arrival.RESPAWN){state.remove("death");JEDGraveRegistry.consumed(player);}
        JEDPlayerInventoryState.commit(player,state);
        if(p.options().inventoryGroups()||p.options().forcedModes()||rule!=null||prepared.switched()||!retained.empty())JEDInventoryAdapterRegistry.sync(player);
        var outcome=new Outcome(group,trigger,applied,skipped,reason,System.nanoTime()-started);LAST.put(player,outcome);return outcome;
    }
    private static String respawnSkip(ServerPlayer player,CompoundTag state,String group,JEDPlayerDefinition.Rule rule){
        var death=state.getCompound("death");
        if(death.getBoolean("keep_inventory")&&!rule.applyWithKeepInventory())return "keepInventory retained items";
        if(!rule.graveAcrossGroups()&&!group.equals(death.getString("group"))&&death.contains("group"))return null;
        if(rule.grantWithGrave())return null;
        var recovery=JEDGraveRegistry.recovery(player,death);
        if(recovery==JEDGraveRegistry.Recovery.RECOVERABLE)return "current death has recoverable grave items";
        if(recovery==JEDGraveRegistry.Recovery.UNKNOWN&&!rule.grantWithUnknownGrave()){
            LogUtils.getLogger().warn("JED could not confirm grave recovery state for player {} death {}; respawn kit skipped by unknown_grave policy",player.getUUID(),death.getString("token"));
            return "grave recovery state unknown";
        }return null;
    }
    private static void modes(ServerPlayer player,CompoundTag state,JEDPlayerOptions options,JEDPlayerDefinition definition,boolean hardcore,boolean joined){
        if(!options.forcedModes()||hardcore)return;
        if(definition.forceGameMode()){
            if(!state.contains("normal_game_mode"))state.putString("normal_game_mode",(joined?player.gameMode.getGameModeForPlayer():options.normalMode()).getName());
            var mode=((ServerLevelData)player.serverLevel().getLevelData()).getGameType();if(player.gameMode.getGameModeForPlayer()!=mode)player.setGameMode(mode);
        }else if(state.contains("normal_game_mode")){
            var mode=GameType.byName(state.getString("normal_game_mode"),null);if(mode==null)throw new IllegalStateException("Invalid saved normal game mode; player state retained");
            if(player.gameMode.getGameModeForPlayer()!=mode)player.setGameMode(mode);
            if(player.gameMode.getGameModeForPlayer()==mode)state.remove("normal_game_mode");
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void logout(PlayerEvent.PlayerLoggedOutEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&POLICIES.containsKey(player.server)){
            JEDPlayerGroupController.save(player);
            var state=JEDPlayerInventoryState.read(player);if(state.contains("active_group")){String group=state.getString("active_group");JEDInventoryGroupManager.capture(player,state,group,JEDPlayerInventoryState.groups(state).getCompound(group));JEDPlayerInventoryState.commit(player,state);}
            // Durable death receipts remain in the native save; release live grave/player objects.
            JEDGraveRegistry.consumed(player);
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){POLICIES.remove(event.getServer());LAST.keySet().removeIf(player->player.server==event.getServer());JEDPlayerGroupController.stopped(event.getServer());}
    private JEDPlayerStateController(){}
}
