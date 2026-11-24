package justepicdimensions.teams;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import justepicdimensions.definition.JEDDefinitionException;
import justepicdimensions.instance.JEDInstanceTeams;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.fml.ModList;

/** Read-only team APIs. Never consults an external party bridge or a claiming/loading API. */
public final class JEDTeamSystems {
    public static final List<String> SYSTEMS=List.of("minecraft","ftbteams","openpartiesandclaims");
    public record Key(String system,UUID id) {
        public Key { if(!SYSTEMS.contains(system))throw new JEDDefinitionException("Unknown team system: "+system);Objects.requireNonNull(id); }
        public String toString(){return system+":"+id;}
        public static Key parse(String value){int colon=value.indexOf(':');return colon<0?new Key("minecraft",UUID.fromString(value)):new Key(value.substring(0,colon),UUID.fromString(value.substring(colon+1)));}
    }
    public record Group(Key key,String name,String kind,UUID owner,Set<UUID> members,Set<UUID> managers) {
        public Group { members=Set.copyOf(members);managers=Set.copyOf(managers); }
    }
    public static boolean installed(String system){return system.equals("minecraft")||ModList.get().isLoaded(system);}
    public static List<Group> groups(MinecraftServer server,String system){
        if(!SYSTEMS.contains(system))throw new JEDDefinitionException("Unknown team system: "+system);
        if(!installed(system))return List.of();
        try{return switch(system){case "minecraft"->nativeGroups(server);case "ftbteams"->ftb(server);case "openpartiesandclaims"->xaero(server);default->throw new AssertionError();};}
        catch(ReflectiveOperationException|LinkageError error){throw new JEDDefinitionException("Team API unavailable for "+system,root(error));}
    }
    public static Group group(MinecraftServer server,Key key){return groups(server,key.system()).stream().filter(g->g.key().equals(key)).findFirst().orElse(null);}
    public static boolean member(net.minecraft.server.level.ServerPlayer player,Group group){if(group==null)return false;if(group.key().system().equals("minecraft"))return player.getTeam() instanceof PlayerTeam team&&JEDInstanceTeams.id(team).equals(group.key().id());return group.members().contains(player.getUUID());}
    public static boolean member(MinecraftServer server,Group group,UUID player){
        if(group==null)return false;
        if(!group.key().system().equals("minecraft"))return group.members().contains(player);
        var online=server.getPlayerList().getPlayer(player);
        String name=online==null?server.getProfileCache().get(player).map(com.mojang.authlib.GameProfile::getName).orElseGet(()->JEDTeamPermissions.nativeName(server,player)):online.getGameProfile().getName();
        var team=name==null?null:server.getScoreboard().getPlayersTeam(name);
        return team!=null&&JEDInstanceTeams.id(team).equals(group.key().id());
    }
    public static boolean knownNativePlayer(MinecraftServer server,UUID player){return server.getPlayerList().getPlayer(player)!=null||server.getProfileCache().get(player).isPresent()||JEDTeamPermissions.nativeName(server,player)!=null;}
    private static List<Group> nativeGroups(MinecraftServer server){
        if(!JEDInstanceTeams.valid(server.getScoreboard()))throw new JEDDefinitionException("Native team identities are invalid");
        var result=new ArrayList<Group>();
        for(var team:server.getScoreboard().getPlayerTeams()){
            UUID id=JEDInstanceTeams.id(team);var members=new HashSet<UUID>();
            for(var p:server.getPlayerList().getPlayers())if(p.getTeam()==team)members.add(p.getUUID());
            result.add(new Group(new Key("minecraft",id),team.getName(),"team",null,members,Set.of()));
        }
        return List.copyOf(result);
    }
    private static final String FTB="dev.ftb.mods.ftbteams.api.";
    private static List<Group> ftb(MinecraftServer server)throws ReflectiveOperationException{
        var api=type(FTB+"FTBTeamsAPI").getMethod("api").invoke(null);
        if(!(boolean)call(FTB+"FTBTeamsAPI$API",api,"isManagerLoaded"))throw new JEDDefinitionException("FTB Teams manager is not loaded");
        var manager=call(FTB+"FTBTeamsAPI$API",api,"getManager");
        if(call(FTB+"TeamManager",manager,"getServer")!=server)throw new JEDDefinitionException("FTB Teams manager belongs to another server");
        var result=new ArrayList<Group>();var known=new LinkedHashMap<UUID,Object>();
        for(Object team:(Collection<?>)call(FTB+"TeamManager",manager,"getTeams"))known.put((UUID)call(FTB+"Team",team,"getId"),team);
        for(Object team:((Map<?,?>)call(FTB+"TeamManager",manager,"getKnownPlayerTeams")).values())known.put((UUID)call(FTB+"Team",team,"getId"),team);
        for(Object team:known.values()){
            if(!(boolean)call(FTB+"Team",team,"isValid"))continue;
            UUID id=(UUID)call(FTB+"Team",team,"getId"),owner=(UUID)call(FTB+"Team",team,"getOwner");
            boolean personal=(boolean)call(FTB+"Team",team,"isPlayerTeam");
            if(personal)owner=id;
            var members=new HashSet<UUID>();var managers=new HashSet<UUID>();
            if(personal)members.add(owner);else for(Object value:(Set<?>)call(FTB+"Team",team,"getMembers"))members.add((UUID)value);
            for(UUID member:members){var rank=call(FTB+"Team",team,"getRankForPlayer",UUID.class,member);if(member.equals(owner)||((Enum<?>)rank).name().equals("OFFICER")||((Enum<?>)rank).name().equals("OWNER"))managers.add(member);}
            result.add(new Group(new Key("ftbteams",id),((Component)call(FTB+"Team",team,"getName")).getString(),personal?"personal":((boolean)call(FTB+"Team",team,"isPartyTeam")?"party":"team"),owner,members,managers));
        }
        return List.copyOf(result);
    }
    private static final String XA="xaero.pac.common.";
    private static List<Group> xaero(MinecraftServer server)throws ReflectiveOperationException{
        Object api=type(XA+"server.api.OpenPACServerAPI").getMethod("get",MinecraftServer.class).invoke(null,server);
        Object manager=call(XA+"server.api.OpenPACServerAPI",api,"getPartyManager");
        var result=new ArrayList<Group>();
        // The native manager is intentional: never use PlayerPartySystemManager or its external bridges.
        try(var parties=(Stream<?>)call(XA+"server.parties.party.api.IPartyManagerAPI",manager,"getAllStream")){
            for(Object party:parties.toList()){
                String partyType=XA+"parties.party.api.IPartyAPI",memberType=XA+"parties.party.member.api.IPartyMemberAPI";
                UUID id=(UUID)call(partyType,party,"getId");Object ownerInfo=call(partyType,party,"getOwner");UUID owner=(UUID)call(memberType,ownerInfo,"getUUID");
                var members=new HashSet<UUID>();var managers=new HashSet<UUID>();members.add(owner);managers.add(owner);
                try(var roster=(Stream<?>)call(partyType,party,"getMemberInfoStream")){for(Object member:roster.toList()){
                    UUID uuid=(UUID)call(memberType,member,"getUUID");members.add(uuid);String rank=((Enum<?>)call(memberType,member,"getRank")).name();if(rank.equals("MODERATOR")||rank.equals("ADMIN"))managers.add(uuid);
                }}
                String name=(String)call(partyType,party,"getDefaultName");
                // PARTY_NAME is party metadata only. No claim/loading option is read or written.
                String configApi=XA+"server.player.config.api.v2.";
                Object configs=call(XA+"server.api.OpenPACServerAPI",api,"getPlayerConfigManager");
                Object config=call(configApi+"IPlayerConfigManagerAPI",configs,"getLoadedConfig",UUID.class,owner);
                if(config==null)throw new JEDDefinitionException("Xaero party name metadata is not loaded");Object option=type(configApi+"PlayerConfigOptions").getField("PARTY_NAME").get(null);Object custom=call(configApi+"IPlayerConfigAPI",config,"getEffective",type(configApi+"IPlayerConfigOptionSpecAPI"),option);if(!(custom instanceof String))throw new JEDDefinitionException("Xaero party name metadata is invalid");if(!((String)custom).isEmpty())name=(String)custom;
                result.add(new Group(new Key("openpartiesandclaims",id),name,"party",owner,members,managers));
            }
        }
        return List.copyOf(result);
    }
    static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,true,JEDTeamSystems.class.getClassLoader());}
    static Object call(String api,Object target,String method)throws ReflectiveOperationException{return type(api).getMethod(method).invoke(target);}
    static Object call(String api,Object target,String method,Class<?> parameter,Object argument)throws ReflectiveOperationException{return type(api).getMethod(method,parameter).invoke(target,argument);}
    static Throwable root(Throwable error){return error instanceof InvocationTargetException invocation?invocation.getCause():error;}
    private JEDTeamSystems(){}
}
