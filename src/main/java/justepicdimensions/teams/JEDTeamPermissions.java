package justepicdimensions.teams;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** JED-owned delegation data only; provider rosters, ranks, claims and loading are never mutated. */
public final class JEDTeamPermissions {
    public static final List<String> LEVELS=List.of("owner","managers","members");
    public static final Set<String> ACTIONS=Set.of("create","reset","retire","members","managers");
    private static final Map<MinecraftServer,State> STATES=new IdentityHashMap<>();
    private static final class State {
        final Path file;JsonObject data;boolean dirty;String lastSaveError;
        State(MinecraftServer server){file=server.getWorldPath(LevelResource.ROOT).resolve("data/justepicdimensions/team_permissions.json");data=Files.exists(file)?JEDJson.object(JEDJson.read(file),file.toString()):empty();if(!data.has("native_names"))data.add("native_names",new JsonObject());validate(data);}
        void edit(Runnable edit){var before=data.deepCopy();try{edit.run();JEDDimensionFiles.writeDurable(file,data);dirty=false;lastSaveError=null;}catch(RuntimeException error){data=before;throw error;}}
        // Provider removals already happened. Never roll revocations back or interrupt the provider's own cleanup.
        void revoke(Runnable edit){edit.run();dirty=true;flush();}
        void flush(){if(!dirty)return;try{JEDDimensionFiles.writeDurable(file,data);dirty=false;lastSaveError=null;}catch(RuntimeException error){if(!error.toString().equals(lastSaveError)){lastSaveError=error.toString();com.mojang.logging.LogUtils.getLogger().warn("JED team revocations remain active in memory; retrying persistence: {}",lastSaveError);}}}
    }
    private static JsonObject empty(){var j=new JsonObject();j.addProperty("format_version",1);for(String key:List.of("native_owners","native_managers","native_names","team_defaults","instances"))j.add(key,new JsonObject());j.add("orphaned",new JsonArray());return j;}
    private static State state(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Team permission mutation requires server thread");return STATES.computeIfAbsent(server,State::new);}
    private static void validate(JsonObject data){
        JEDJson.keys(data,Set.of("format_version","native_owners","native_managers","native_names","team_defaults","instances","orphaned"),"team permissions");
        if(JEDJson.integer(data.get("format_version"),"team permissions format")!=1)throw new JEDDefinitionException("Unsupported team permissions format");
        for(var e:data.getAsJsonObject("native_owners").entrySet()){UUID.fromString(e.getKey());UUID.fromString(e.getValue().getAsString());}
        for(var e:data.getAsJsonObject("native_names").entrySet()){UUID.fromString(e.getKey());JEDJson.string(e.getValue(),"native player name");}
        for(var e:data.getAsJsonObject("native_managers").entrySet()){UUID.fromString(e.getKey());for(var id:e.getValue().getAsJsonArray())UUID.fromString(id.getAsString());}
        for(var e:data.getAsJsonObject("team_defaults").entrySet()){JEDTeamSystems.Key.parse(e.getKey());validateActions(e.getValue().getAsJsonObject());}
        for(var e:data.getAsJsonObject("instances").entrySet()){ResourceLocation.parse(e.getKey());var j=e.getValue().getAsJsonObject();JEDTeamSystems.Key.parse(j.get("owner").getAsString());validateActions(j.getAsJsonObject("actions"));}
        for(var id:data.getAsJsonArray("orphaned"))ResourceLocation.parse(id.getAsString());
    }
    private static void validateActions(JsonObject actions){JEDJson.keys(actions,ACTIONS,"team actions");for(var e:actions.entrySet()){var j=e.getValue().getAsJsonObject();JEDJson.keys(j,Set.of("level","grants"),"team action");if(!LEVELS.contains(j.get("level").getAsString()))throw new JEDDefinitionException("Unknown delegation level");for(var id:j.getAsJsonArray("grants"))UUID.fromString(id.getAsString());}}
    public static UUID nativeOwner(MinecraftServer server,UUID team){var owners=state(server).data.getAsJsonObject("native_owners");return owners.has(team.toString())?UUID.fromString(owners.get(team.toString()).getAsString()):null;}
    public static String nativeName(MinecraftServer server,UUID player){var names=state(server).data.getAsJsonObject("native_names");return names.has(player.toString())?names.get(player.toString()).getAsString():null;}
    private static void rememberNative(MinecraftServer server,State s,UUID player){var online=server.getPlayerList().getPlayer(player);String name=online!=null?online.getGameProfile().getName():server.getProfileCache().get(player).map(com.mojang.authlib.GameProfile::getName).orElse(null);if(name!=null)s.data.getAsJsonObject("native_names").addProperty(player.toString(),name);}
    public static boolean isOwner(MinecraftServer server,JEDTeamSystems.Group group,UUID player){return group!=null&&JEDTeamSystems.member(server,group,player)&&player.equals(group.key().system().equals("minecraft")?nativeOwner(server,group.key().id()):group.owner());}
    public static boolean isManager(MinecraftServer server,JEDTeamSystems.Group group,UUID player){
        if(group==null||!JEDTeamSystems.member(server,group,player))return false;if(isOwner(server,group,player))return true;
        if(!group.key().system().equals("minecraft"))return group.managers().contains(player);
        var values=state(server).data.getAsJsonObject("native_managers").getAsJsonArray(group.key().id().toString());return values!=null&&contains(values,player.toString());
    }
    public static void assignNative(MinecraftServer server,UUID team,UUID player){
        var key=new JEDTeamSystems.Key("minecraft",team);var group=JEDTeamSystems.group(server,key);if(!JEDTeamSystems.member(server,group,player))throw new JEDDefinitionException("The assigned native-team JED owner must be a current team member");
        var s=state(server);s.edit(()->{rememberNative(server,s,player);s.data.getAsJsonObject("native_owners").addProperty(team.toString(),player.toString());});
    }
    public static void nativeManager(MinecraftServer server,UUID team,UUID player,boolean add){
        var group=JEDTeamSystems.group(server,new JEDTeamSystems.Key("minecraft",team));if(add&&!JEDTeamSystems.member(server,group,player))throw new JEDDefinitionException("JED managers must be current native-team members");
        var s=state(server);s.edit(()->{if(add)rememberNative(server,s,player);var managers=s.data.getAsJsonObject("native_managers");var values=managers.has(team.toString())?managers.getAsJsonArray(team.toString()):new JsonArray();remove(values,player.toString());if(add)values.add(player.toString());managers.add(team.toString(),values);});
    }
    private static JsonObject actions(State s,JEDTeamSystems.Key key,ResourceLocation instance,boolean create){
        if(instance==null){var defaults=s.data.getAsJsonObject("team_defaults");if(!defaults.has(key.toString())&&create)defaults.add(key.toString(),new JsonObject());return defaults.getAsJsonObject(key.toString());}
        var instances=s.data.getAsJsonObject("instances");String id=instance.toString();
        if(instances.has(id)&&!instances.getAsJsonObject(id).get("owner").getAsString().equals(key.toString()))throw new JEDDefinitionException("Instance permission owner mismatch");
        if(!instances.has(id)&&create){var j=new JsonObject();j.addProperty("owner",key.toString());j.add("actions",new JsonObject());instances.add(id,j);}
        return instances.has(id)?instances.getAsJsonObject(id).getAsJsonObject("actions"):null;
    }
    public static JsonObject settings(MinecraftServer server,JEDTeamSystems.Key key,ResourceLocation instance,String action){
        var s=state(server);var local=actions(s,key,instance,false);if(local!=null&&local.has(action))return local.getAsJsonObject(action).deepCopy();
        var defaults=actions(s,key,null,false);return defaults!=null&&defaults.has(action)?defaults.getAsJsonObject(action).deepCopy():null;
    }
    public static boolean allows(ServerPlayer player,JEDTeamSystems.Group group,ResourceLocation instance,String action){
        if(!JEDTeamSystems.member(player,group))return false;prune(player.server,group.key());
        var setting=settings(player.server,group.key(),instance,action);if(setting==null)return true;
        if(contains(setting.getAsJsonArray("grants"),player.getUUID().toString()))return true;
        return switch(setting.get("level").getAsString()){case "owner"->isOwner(player.server,group,player.getUUID());case "managers"->isManager(player.server,group,player.getUUID());case "members"->true;default->false;};
    }
    public static void set(MinecraftServer server,JEDTeamSystems.Key key,ResourceLocation instance,String action,String level){
        if(!ACTIONS.contains(action)||!LEVELS.contains(level)&&!level.equals("inherit"))throw new JEDDefinitionException("Unknown action or delegation level");var s=state(server);
        s.edit(()->{var values=actions(s,key,instance,true);if(level.equals("inherit")){values.remove(action);return;}var previous=settings(server,key,instance,action);var rule=previous==null?new JsonObject():previous;rule.addProperty("level",level);if(!rule.has("grants"))rule.add("grants",new JsonArray());values.add(action,rule);});
    }
    public static void grant(MinecraftServer server,JEDTeamSystems.Key key,ResourceLocation instance,String action,UUID player,boolean add,String policyLevel){
        if(!ACTIONS.contains(action))throw new JEDDefinitionException("Unknown instance action");var group=JEDTeamSystems.group(server,key);if(add&&!JEDTeamSystems.member(server,group,player))throw new JEDDefinitionException("Individual grants require current owning-group membership");
        var s=state(server);s.edit(()->{if(add&&key.system().equals("minecraft"))rememberNative(server,s,player);var rule=settings(server,key,instance,action);if(rule==null){rule=new JsonObject();rule.addProperty("level",LEVELS.contains(policyLevel)?policyLevel:"managers");rule.add("grants",new JsonArray());}var grants=rule.getAsJsonArray("grants");remove(grants,player.toString());if(add)grants.add(player.toString());actions(s,key,instance,true).add(action,rule);});
    }
    public static void departed(MinecraftServer server,JEDTeamSystems.Key key,UUID player){var s=state(server);var next=s.data.deepCopy();clearMember(next,key,player);if(!next.equals(s.data))s.revoke(()->s.data=next);}
    private static void clearMember(JsonObject data,JEDTeamSystems.Key key,UUID player){
        if(key.system().equals("minecraft")){String id=key.id().toString();var owners=data.getAsJsonObject("native_owners");if(owners.has(id)&&owners.get(id).getAsString().equals(player.toString()))owners.remove(id);var managers=data.getAsJsonObject("native_managers").getAsJsonArray(id);if(managers!=null)remove(managers,player.toString());}
        var defaults=data.getAsJsonObject("team_defaults").getAsJsonObject(key.toString());if(defaults!=null)clearGrants(defaults,player);
        for(var entry:data.getAsJsonObject("instances").entrySet()){var j=entry.getValue().getAsJsonObject();if(j.get("owner").getAsString().equals(key.toString()))clearGrants(j.getAsJsonObject("actions"),player);}
    }
    private static void clearGrants(JsonObject actions,UUID player){for(var value:actions.entrySet())remove(value.getValue().getAsJsonObject().getAsJsonArray("grants"),player.toString());}
    public static void prune(MinecraftServer server,JEDTeamSystems.Key key){
        if(!JEDTeamSystems.installed(key.system()))return;var s=state(server);var group=JEDTeamSystems.group(server,key);var tracked=new HashSet<UUID>();
        if(key.system().equals("minecraft")){UUID owner=nativeOwner(server,key.id());if(owner!=null)tracked.add(owner);var managers=s.data.getAsJsonObject("native_managers").getAsJsonArray(key.id().toString());if(managers!=null)for(var id:managers)tracked.add(UUID.fromString(id.getAsString()));}
        var defaults=actions(s,key,null,false);if(defaults!=null)tracked(defaults,tracked);
        for(var entry:s.data.getAsJsonObject("instances").entrySet()){var j=entry.getValue().getAsJsonObject();if(j.get("owner").getAsString().equals(key.toString()))tracked(j.getAsJsonObject("actions"),tracked);}
        var removed=tracked.stream().filter(id->(!key.system().equals("minecraft")||group==null||JEDTeamSystems.knownNativePlayer(server,id))&&!JEDTeamSystems.member(server,group,id)).toList();if(!removed.isEmpty())s.revoke(()->{for(UUID id:removed)clearMember(s.data,key,id);});
    }
    public static Set<UUID> trackedPlayers(MinecraftServer server,JEDTeamSystems.Key key){
        var s=state(server);var players=new HashSet<UUID>();if(key.system().equals("minecraft")){UUID owner=nativeOwner(server,key.id());if(owner!=null)players.add(owner);var managers=s.data.getAsJsonObject("native_managers").getAsJsonArray(key.id().toString());if(managers!=null)for(var id:managers)players.add(UUID.fromString(id.getAsString()));}
        var defaults=actions(s,key,null,false);if(defaults!=null)tracked(defaults,players);for(var entry:s.data.getAsJsonObject("instances").entrySet()){var j=entry.getValue().getAsJsonObject();if(j.get("owner").getAsString().equals(key.toString()))tracked(j.getAsJsonObject("actions"),players);}return players;
    }
    private static void tracked(JsonObject values,Set<UUID> ids){for(var entry:values.entrySet())for(var id:entry.getValue().getAsJsonObject().getAsJsonArray("grants"))ids.add(UUID.fromString(id.getAsString()));}
    public static void pruneAll(MinecraftServer server){
        var s=state(server);s.flush();var data=s.data;var keys=new HashSet<JEDTeamSystems.Key>();for(var entry:data.getAsJsonObject("team_defaults").entrySet())keys.add(JEDTeamSystems.Key.parse(entry.getKey()));for(var entry:data.getAsJsonObject("instances").entrySet())keys.add(JEDTeamSystems.Key.parse(entry.getValue().getAsJsonObject().get("owner").getAsString()));for(String name:List.of("native_owners","native_managers"))for(var entry:data.getAsJsonObject(name).entrySet())keys.add(new JEDTeamSystems.Key("minecraft",UUID.fromString(entry.getKey())));for(var key:keys)prune(server,key);
    }
    public static boolean orphaned(MinecraftServer server,ResourceLocation instance){return contains(state(server).data.getAsJsonArray("orphaned"),instance.toString());}
    public static void orphan(MinecraftServer server,ResourceLocation instance){var s=state(server);if(!orphaned(server,instance))s.edit(()->s.data.getAsJsonArray("orphaned").add(instance.toString()));}
    public static void repaired(MinecraftServer server,ResourceLocation instance){var s=state(server);s.edit(()->{s.data.getAsJsonObject("instances").remove(instance.toString());remove(s.data.getAsJsonArray("orphaned"),instance.toString());});}
    public static JsonObject view(MinecraftServer server,JEDTeamSystems.Key key,ResourceLocation instance){prune(server,key);var result=new JsonObject();for(String action:new TreeSet<>(ACTIONS)){var settings=settings(server,key,instance,action);if(settings!=null)result.add(action,settings);}return result;}
    private static boolean contains(JsonArray values,String id){for(var value:values)if(value.getAsString().equals(id))return true;return false;}
    private static void remove(JsonArray values,String id){for(int i=values.size()-1;i>=0;i--)if(values.get(i).getAsString().equals(id))values.remove(i);}
    public static void stopped(MinecraftServer server){var state=STATES.remove(server);if(state!=null)state.flush();}
    private JEDTeamPermissions(){}
}
