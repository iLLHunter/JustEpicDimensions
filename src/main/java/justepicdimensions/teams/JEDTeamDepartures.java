package justepicdimensions.teams;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.*;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import justepicdimensions.instance.JEDInstanceTeams;
import justepicdimensions.definition.JEDDefinitionException;

/** Observes membership removal. It does not edit either external mod's state. */
public final class JEDTeamDepartures {
    private static boolean ftbRegistered;
    public static void start(MinecraftServer server){
        if(!"verified".equals(System.getProperty("jed.teams.coremod.net.minecraft.world.scores.Scoreboard")))throw new JEDDefinitionException("Missing JED native-team departure hook; install matching current JED and Arbitrage JARs together");
        if(JEDTeamSystems.installed("openpartiesandclaims"))try{Class.forName("xaero.pac.common.server.parties.party.ServerParty",false,JEDTeamDepartures.class.getClassLoader());if(!"verified".equals(System.getProperty("jed.teams.coremod.xaero.pac.common.server.parties.party.ServerParty")))throw new JEDDefinitionException("Missing JED native Xaero party departure observer; install matching current JED and Arbitrage JARs together");}catch(ClassNotFoundException error){throw new JEDDefinitionException("Native Xaero party API unavailable",error);}
        if(JEDTeamSystems.installed("ftbteams")&&!ftbRegistered)try{
            var eventType=JEDTeamSystems.type("dev.ftb.mods.ftbteams.api.event.TeamEvent");
            Object event=eventType.getField("PLAYER_LEFT_PARTY").get(null);
            Consumer<Object> listener=value->{var current=ServerLifecycleHooks.getCurrentServer();if(current==null)return;try{Object team=eventType.getMethod("getTeam").invoke(value);UUID group=(UUID)JEDTeamSystems.call("dev.ftb.mods.ftbteams.api.Team",team,"getId");UUID player=(UUID)JEDTeamSystems.call("dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent",value,"getPlayerId");JEDTeamPermissions.departed(current,new JEDTeamSystems.Key("ftbteams",group),player);}catch(ReflectiveOperationException error){throw new JEDDefinitionException("Cannot observe FTB team departure",JEDTeamSystems.root(error));}};
            JEDTeamSystems.type("dev.architectury.event.Event").getMethod("register",Object.class).invoke(event,listener);ftbRegistered=true;
        }catch(ReflectiveOperationException error){throw new JEDDefinitionException("Cannot register FTB team departure observer",JEDTeamSystems.root(error));}
    }
    /** Called after native scoreboard removal, before a subsequent join can restore the same membership. */
    public static void nativeLeft(Scoreboard board,String name,PlayerTeam team){
        var server=ServerLifecycleHooks.getCurrentServer();if(server==null||server.getScoreboard()!=board)return;
        var key=new JEDTeamSystems.Key("minecraft",JEDInstanceTeams.id(team));var known=JEDTeamPermissions.trackedPlayers(server,key);
        var online=server.getPlayerList().getPlayerByName(name);if(online!=null)known.add(online.getUUID());
        for(UUID player:known){String username=server.getProfileCache().get(player).map(com.mojang.authlib.GameProfile::getName).orElseGet(()->JEDTeamPermissions.nativeName(server,player));if(name.equals(username)||online!=null&&online.getUUID().equals(player))JEDTeamPermissions.departed(server,key,player);}
    }
    /** Receives only the native Xaero party object and a successful native member removal result. */
    public static void xaeroLeft(Object removed,Object party,UUID player){
        if(removed==null)return;var server=ServerLifecycleHooks.getCurrentServer();if(server==null)return;
        try{UUID group=(UUID)JEDTeamSystems.call("xaero.pac.common.parties.party.api.IPartyAPI",party,"getId");JEDTeamPermissions.departed(server,new JEDTeamSystems.Key("openpartiesandclaims",group),player);}
        catch(ReflectiveOperationException error){throw new JEDDefinitionException("Cannot observe Xaero party departure",JEDTeamSystems.root(error));}
    }
    private JEDTeamDepartures(){}
}
