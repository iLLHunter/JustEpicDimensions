package justepicdimensions.player;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** The native atomic player .dat save owns live inventory, group snapshots and trigger history together. */
public final class JEDPlayerInventoryState {
    public static final String KEY="justepicdimensions:player_state";
    public static CompoundTag read(ServerPlayer player){
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(KEY).copy();
    }
    public static void commit(ServerPlayer player,CompoundTag state){
        var persistent=player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).copy();
        state=state.copy();justepicdimensions.instance.JEDInstanceSnapshots.prune(player.server,state);state.putInt("format_version",state.contains("categories")||state.getInt("format_version")>=2?2:1);persistent.put(KEY,state);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG,persistent);
    }
    public static CompoundTag groups(CompoundTag state){return state.getCompound("groups");}
    public static boolean visited(CompoundTag state,String scope,String key){return state.getCompound("visited_"+scope).getBoolean(key);}
    public static void visit(CompoundTag state,String scope,String key){var values=state.getCompound("visited_"+scope);values.putBoolean(key,true);state.put("visited_"+scope,values);}
    private JEDPlayerInventoryState(){}
}
