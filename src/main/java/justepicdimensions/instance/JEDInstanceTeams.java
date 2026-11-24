package justepicdimensions.instance;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.scores.*;

/** Stable identity belongs to the native team object, never its reusable display/name string. */
@net.neoforged.fml.common.EventBusSubscriber(modid="justepicdimensions")
public final class JEDInstanceTeams {
    private static final Map<PlayerTeam,UUID> IDS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Scoreboard,String> FAULTS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PlayerTeam,Tag> BAD_TAGS=Collections.synchronizedMap(new WeakHashMap<>());
    public static boolean valid(Scoreboard board){return !FAULTS.containsKey(board);}
    @net.neoforged.bus.api.SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST) public static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){var error=FAULTS.get(event.getServer().getScoreboard());if(error!=null)throw new IllegalStateException(error+"; restore the scoreboard ownership tags before restarting");}
    private static final String KEY="justepicdimensions:instance_team";
    public static UUID id(PlayerTeam team){synchronized(IDS){var id=IDS.get(team);if(id!=null)return id;id=UUID.randomUUID();IDS.put(team,id);team.getScoreboard().onTeamChanged(team);return id;}}
    public static void loaded(ListTag values,Scoreboard board){
        var seen=new HashSet<UUID>();for(int i=0;i<values.size();i++){var tag=values.getCompound(i);var team=board.getPlayerTeam(tag.getString("Name"));if(team==null)continue;UUID uuid=tag.hasUUID(KEY)?tag.getUUID(KEY):UUID.randomUUID();if(!seen.add(uuid))FAULTS.put(board,"Duplicate JED team ownership identity in saved scoreboard");if(tag.contains(KEY)&&!tag.hasUUID(KEY)){FAULTS.put(board,"Invalid JED team ownership identity in saved scoreboard");BAD_TAGS.put(team,tag.get(KEY).copy());}IDS.put(team,uuid);}
    }
    public static ListTag saved(ListTag values,Scoreboard board){for(int i=0;i<values.size();i++){var tag=values.getCompound(i);var team=board.getPlayerTeam(tag.getString("Name"));if(team!=null){var invalid=BAD_TAGS.get(team);if(invalid!=null)tag.put(KEY,invalid.copy());else tag.putUUID(KEY,id(team));}}return values;}
}
