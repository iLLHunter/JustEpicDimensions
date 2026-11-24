package justepicdimensions.access;

import com.mojang.logging.LogUtils;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionLifecycle;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Server-authoritative admission, evaluated before travel mutates inventory/category state. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDAccessController {
    public enum Arrival {TRAVEL,RESPAWN,RECONNECT,INITIAL,FALLBACK}
    public record Policy(JEDAccessDefinition definition,Component message){}
    public record Prepared(Map<ResourceLocation,Policy> policies,Map<ResourceLocation,JEDAccessRule> rules){}
    public record Decision(boolean allowed,Component reason){public static final Decision ALLOW=new Decision(true,Component.empty());}
    private static final Map<MinecraftServer,Prepared> SERVERS=new IdentityHashMap<>();
    private static final Map<ServerPlayer,AutoCloseable> RESPAWNS=new IdentityHashMap<>();
    private static final Map<ServerPlayer,java.util.concurrent.CompletableFuture<?>> LOGIN_PERMISSIONS=new IdentityHashMap<>();
    public static void loginDone(ServerPlayer player){LOGIN_PERMISSIONS.remove(player);}
    public static void loginAbandoned(ServerPlayer player){var prepared=LOGIN_PERMISSIONS.remove(player);if(prepared!=null)JEDAccessPermissions.release(prepared);}
    private static final Map<ServerPlayer,Long> WARNINGS=new WeakHashMap<>();
    private static final Map<MinecraftServer,Set<String>> ERRORS=new IdentityHashMap<>();
    public static Prepared prepare(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var ops=RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,server.registryAccess());var rules=candidate.accessRules(ops);var policies=new HashMap<ResourceLocation,Policy>();
        definitions.forEach((id,d)->{var a=d.access();if(!a.equals(JEDAccessDefinition.DEFAULT)){
            var message=a.message()==null?null:ComponentSerialization.CODEC.parse(ops,a.message()).getOrThrow(error->new JEDDefinitionException(id+": "+error));
            policies.put(id,new Policy(a,message));
        }});
        return new Prepared(Map.copyOf(policies),Map.copyOf(rules));
    }
    public static void publish(MinecraftServer server,Prepared prepared){SERVERS.put(server,prepared);ERRORS.remove(server);}
    public static JEDAccessDefinition policy(MinecraftServer server,ResourceLocation id){var state=SERVERS.get(server);var p=state==null?null:state.policies().get(id);return p==null?JEDAccessDefinition.DEFAULT:p.definition();}
    private static boolean applies(JEDAccessDefinition a,Arrival arrival){return switch(arrival){case RESPAWN->a.respawn();case RECONNECT->a.reconnect();case INITIAL->a.initialSpawn();default->true;};}
    public static Decision check(Entity entity,ResourceLocation destination,boolean explicit,Arrival arrival){
        var server=((ServerLevel)entity.level()).getServer();if(!server.isSameThread())throw new IllegalStateException("Access evaluation requires the server thread");
        if(!JEDDimensionLifecycle.available(server,destination))return new Decision(false,JEDAccessRule.text("unavailable","Destination %s is unavailable",destination.toString()));
        if(arrival==Arrival.TRAVEL&&justepicdimensions.instance.JEDInstanceController.fallbackArrival(entity,destination))arrival=Arrival.FALLBACK;
        var a=policy(server,destination);
        if(!justepicdimensions.instance.JEDInstanceController.admission(entity,destination,explicit,arrival))return new Decision(false,Component.translatableWithFallback("justepicdimensions.instance.denied","Instance entry requires current membership and an available run."));
        if(arrival==Arrival.TRAVEL&&entity.level().dimension().location().equals(destination))return Decision.ALLOW;
        if(explicit&&!a.bypass().equals("never"))return Decision.ALLOW;
        if(a.bypass().equals("operator")&&entity instanceof ServerPlayer p&&p.hasPermissions(Math.max(2,justepicdimensions.travel.JEDTeleportService.options(server).tpjAdminLevel())))return Decision.ALLOW;
        if(justepicdimensions.schedule.JEDLifecycleScheduleController.blocked(server,destination,arrival))return new Decision(false,justepicdimensions.schedule.JEDLifecycleScheduleController.denial(destination));
        if(justepicdimensions.pregen.JEDPregenerationController.blocked(server,destination,arrival))return new Decision(false,justepicdimensions.pregen.JEDPregenerationController.denial(destination));
        if(!applies(a,arrival))return Decision.ALLOW;
        Component failure=null;
        if(!(entity instanceof ServerPlayer player)){if(a.nonPlayers())return Decision.ALLOW;failure=JEDAccessRule.text("non_players","Non-player entry is blocked");}
        else if(a.rule()==null)return Decision.ALLOW;
        else try{
            var state=SERVERS.get(server);var rule=state==null?null:state.rules().get(a.rule());if(rule==null)throw new JEDDefinitionException("Unknown active access rule "+a.rule());
            var result=JEDAccessPermissions.beforeLogin(arrival==Arrival.INITIAL||arrival==Arrival.RECONNECT,()->rule.condition().evaluate(player));if(result.allowed())return Decision.ALLOW;failure=result.reason();
        }catch(RuntimeException error){
            String key=String.valueOf(a.rule())+":"+error.getMessage();if(ERRORS.computeIfAbsent(server,s->new HashSet<>()).add(key))LogUtils.getLogger().warn("JED access rule {} could not be evaluated for {}",a.rule(),entity.getUUID(),error);
            failure=JEDAccessRule.text("evaluation_failed","The access rule could not be evaluated; contact the server owner");
        }
        var prepared=SERVERS.get(server);var p=prepared==null?null:prepared.policies().get(destination);
        Component feedback=a.feedback().equals("custom")&&p!=null?p.message():a.feedback().equals("generic")?JEDAccessRule.text("denied","Entry to %s is denied",destination.toString()):JEDAccessRule.text("requirements","Entry to %s requires: %s",destination.toString(),failure);
        return new Decision(false,feedback);
    }
    /** Active reusable rule evaluation for {@link justepicdimensions.api.access.JEDAccess}; empty for an unknown rule. */
    public static Optional<Decision> evaluateRule(ServerPlayer player,ResourceLocation id){
        var server=player.server;if(!server.isSameThread())throw new IllegalStateException("Access evaluation requires the server thread");
        var state=SERVERS.get(server);var rule=state==null?null:state.rules().get(id);if(rule==null)return Optional.empty();
        try{var result=rule.condition().evaluate(player);return Optional.of(result.allowed()?Decision.ALLOW:new Decision(false,result.reason()));}
        catch(RuntimeException error){
            String key=id+":"+error.getMessage();if(ERRORS.computeIfAbsent(server,s->new HashSet<>()).add(key))LogUtils.getLogger().warn("JED access rule {} could not be evaluated for {}",id,player.getUUID(),error);
            return Optional.of(new Decision(false,JEDAccessRule.text("evaluation_failed","The access rule could not be evaluated; contact the server owner")));
        }
    }
    public static Set<ResourceLocation> ruleIds(MinecraftServer server){var state=SERVERS.get(server);return state==null?Set.of():Set.copyOf(state.rules().keySet());}
    public static Decision group(Entity root,ResourceLocation destination,boolean explicit){
        for(var member:root.getSelfAndPassengers().toList()){var decision=check(member,destination,explicit,Arrival.TRAVEL);if(!decision.allowed())return decision;}return Decision.ALLOW;
    }
    public static void require(Entity entity,ResourceLocation destination,boolean explicit,boolean together){
        var decision=together?group(entity,destination,explicit):check(entity,destination,explicit,Arrival.TRAVEL);
        if(!decision.allowed())throw new JEDDefinitionException(decision.reason().getString(),null,decision.reason());
    }
    public static void warn(Entity entity,Decision decision){
        if(entity instanceof ServerPlayer player){long now=player.server.getTickCount();if(now-WARNINGS.getOrDefault(player,Long.MIN_VALUE/2)<20)return;WARNINGS.put(player,now);player.sendSystemMessage(decision.reason().copy().withStyle(ChatFormatting.RED));}
    }
    /** A fallback is a new admission; exemptions for the original saved respawn/login do not unlock it. */
    public static ServerLevel destination(ServerPlayer player,ServerLevel proposed,Arrival arrival){return JEDAccessPermissions.beforeLogin(arrival==Arrival.INITIAL||arrival==Arrival.RECONNECT,()->resolveDestination(player,proposed,arrival));}
    private static ServerLevel resolveDestination(ServerPlayer player,ServerLevel proposed,Arrival arrival){
        if(check(player,proposed.dimension().location(),false,arrival).allowed())return proposed;
        var server=player.server;var seen=new HashSet<ResourceLocation>();var source=proposed.dimension().location();var definition=JEDDefinitionManager.state(server).definitions().get(source);var id=(justepicdimensions.schedule.JEDLifecycleScheduleController.blocked(server,source,arrival)||justepicdimensions.pregen.JEDPregenerationController.blocked(server,source,arrival))?definition==null?Level.OVERWORLD.location():definition.lifecycle().fallback():fallback(server,source);
        while(seen.add(id)){
            if(eligible(player,id)){var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,id));if(level!=null)return level;}
            id=fallback(server,id);
        }
        id=Level.OVERWORLD.location();if(eligible(player,id))return server.overworld();
        var reason=JEDAccessRule.text("no_fallback","No accessible destination exists for %s. Contact the server owner.",player.getGameProfile().getName());
        throw new JEDDefinitionException(reason.getString(),null,reason);
    }
    public static boolean pendingRespawn(ServerPlayer player){return RESPAWNS.containsKey(player);}
    public static ServerPlayer respawn(net.minecraft.server.players.PlayerList list,ServerPlayer player,boolean keep,Entity.RemovalReason reason,net.minecraft.world.level.portal.DimensionTransition original,net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent event){
        ServerLevel target;java.util.concurrent.CompletableFuture<Void> ready;
        try{
            target=destination(player,original.newLevel(),keep?Arrival.TRAVEL:Arrival.RESPAWN);
            ready=justepicdimensions.spawn.JEDSpawnController.prepare(target);
            if(ready.isCompletedExceptionally())ready.join();
        }catch(RuntimeException error){player.connection.disconnect(justepicdimensions.JEDText.error(error));return player;}
        var transition=target==original.newLevel()?original:new net.minecraft.world.level.portal.DimensionTransition(target,net.minecraft.world.phys.Vec3.atBottomCenterOf(target.getSharedSpawnPos()),net.minecraft.world.phys.Vec3.ZERO,target.getSharedSpawnAngle(),0,original.missingRespawnBlock(),original.postDimensionTransition());
        if(ready.isDone())return ((JEDAccessRespawnAccess)list).jed$resumeRespawn(player,keep,reason,transition,event);
        var server=player.server;var pin=JEDDimensionLifecycle.pinPending(server,player.level().dimension().location(),target.dimension().location());RESPAWNS.put(player,pin);
        ready.whenComplete((v,error)->server.tell(new net.minecraft.server.TickTask(server.getTickCount(),()->{
            if(RESPAWNS.remove(player)!=pin)return;
            try{
                if(!player.connection.isAcceptingMessages()||player.connection.player!=player)return;
                if(error!=null){player.connection.disconnect(justepicdimensions.JEDText.error(error));return;}
                player.connection.player=respawn(list,player,keep,reason,original,event);
            }finally{release(pin);}
        })));
        return player;
    }
    @SubscribeEvent public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){if(!"verified".equals(System.getProperty("jed.access.respawn.coremod")))throw new IllegalStateException("JED requires matching Arbitrage respawn admission hooks");}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer player){var pin=RESPAWNS.remove(player);if(pin!=null)release(pin);WARNINGS.remove(player);loginAbandoned(player);}}
    private static void release(AutoCloseable pin){try{pin.close();}catch(Exception error){LogUtils.getLogger().error("JED access preparation pin cleanup failed",error);}}
    public static java.util.concurrent.CompletableFuture<ServerLevel> loginDestination(ServerPlayer player,ServerLevel proposed,boolean initial){
        var arrival=initial?Arrival.INITIAL:Arrival.RECONNECT;var a=policy(player.server,proposed.dimension().location());
        if(justepicdimensions.instance.JEDInstanceController.admission(player,proposed.dimension().location(),false,arrival)&&!applies(a,arrival)&&!justepicdimensions.schedule.JEDLifecycleScheduleController.blocked(player.server,proposed.dimension().location(),arrival)&&!justepicdimensions.pregen.JEDPregenerationController.blocked(player.server,proposed.dimension().location(),arrival))return java.util.concurrent.CompletableFuture.completedFuture(proposed);
        var active=SERVERS.get(player.server);
        boolean luckperms=net.neoforged.fml.ModList.get().isLoaded("luckperms")&&active!=null&&active.rules().values().stream().anyMatch(rule->usesPermissions(rule.json().get("condition")));
        if(luckperms){
            var permissions=LOGIN_PERMISSIONS.computeIfAbsent(player,JEDAccessPermissions::prepare);
            // Re-entry after asynchronous preparation must complete synchronously once data is ready.
            // A failed provider is a failed condition; accessible fallbacks still get their checks.
            if(!permissions.isDone())return permissions.handleAsync((v,error)->destination(player,proposed,arrival),player.server);
        }
        try{return java.util.concurrent.CompletableFuture.completedFuture(destination(player,proposed,arrival));}
        catch(RuntimeException error){return java.util.concurrent.CompletableFuture.failedFuture(error);}
    }
    private static boolean usesPermissions(com.google.gson.JsonElement value){
        if(value==null||!value.isJsonObject())return false;var j=value.getAsJsonObject();
        if(j.has("permission"))return true;
        if(j.has("not"))return usesPermissions(j.get("not"));
        for(String key:List.of("all","any"))if(j.has(key))for(var child:j.getAsJsonArray(key))if(usesPermissions(child))return true;
        return false;
    }
    private static boolean eligible(ServerPlayer player,ResourceLocation id){return JEDDimensionLifecycle.available(player.server,id)&&check(player,id,false,Arrival.FALLBACK).allowed();}
    private static ResourceLocation fallback(MinecraftServer server,ResourceLocation id){var a=policy(server,id);if(a.fallback()!=null)return a.fallback();var d=JEDDefinitionManager.state(server).definitions().get(id);return d==null?Level.OVERWORLD.location():d.lifecycle().fallback();}
    @SubscribeEvent public static void stop(ServerStoppedEvent event){for(var p:List.copyOf(RESPAWNS.keySet()))if(p.server==event.getServer())release(RESPAWNS.remove(p));LOGIN_PERMISSIONS.keySet().removeIf(p->p.server==event.getServer());SERVERS.remove(event.getServer());ERRORS.remove(event.getServer());WARNINGS.keySet().removeIf(p->p.server==event.getServer());}
    private JEDAccessController(){}
}
