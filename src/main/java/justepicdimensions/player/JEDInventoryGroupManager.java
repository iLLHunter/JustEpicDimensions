package justepicdimensions.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** A group transaction owns only inventory/equipment, never health, XP, food or effects. */
public final class JEDInventoryGroupManager {
    public record Prepared(CompoundTag state,CompoundTag restored,CompoundTag before,String from,String to,boolean switched) {}
    public static Prepared prepare(ServerPlayer player,CompoundTag original,String target){
        return prepare(player,original,target,null);
    }
    public static Prepared prepare(ServerPlayer player,CompoundTag original,String target,JEDInventoryRetention.Transfer retained){
        var state=original.copy();var groups=JEDPlayerInventoryState.groups(state).copy();
        String from=state.contains("active_group")?state.getString("active_group"):target;
        var source=JEDInventoryAdapterRegistry.recover(player,groups.getCompound(from));
        var before=JEDInventoryAdapterRegistry.capture(player,source);
        if(!from.equals(target)){
            // Trash is disposable, not a saved loadout. Ignore legacy undo stacks on entry.
            var destination=withoutTrash(groups.getCompound(target));
            JEDInventoryAdapterRegistry.validate(player,destination);
            closeMenu(player);
            // Closing a native menu returns carried/crafting items before the departing snapshot.
            before=JEDInventoryAdapterRegistry.capture(player,source);
            try{
                var departing=withoutTrash(retained==null?before:retained.depart(player,before));
                var restored=JEDInventoryAdapterRegistry.restore(player,destination);
                groups.put(from,departing);groups.put(target,restored);state.put("groups",groups);state.putString("active_group",target);
                return new Prepared(state,restored,before,from,target,true);
            }catch(RuntimeException error){
                try{JEDInventoryAdapterRegistry.restore(player,before);JEDInventoryAdapterRegistry.sync(player);}catch(RuntimeException rollback){error.addSuppressed(rollback);}
                throw error;
            }
        }
        groups.put(target,before);state.put("groups",groups);state.putString("active_group",target);
        return new Prepared(state,before,before,from,target,false);
    }
    public static void capture(ServerPlayer player,CompoundTag state,String group,CompoundTag previous){
        var groups=JEDPlayerInventoryState.groups(state).copy();
        groups.put(group,JEDInventoryAdapterRegistry.capture(player,previous));state.put("groups",groups);
    }
    private static CompoundTag withoutTrash(CompoundTag snapshot){
        var result=snapshot.copy();result.remove("trashslot");return result;
    }
    private JEDInventoryGroupManager(){}
    /**
     * Returns carried and crafting-grid items before a snapshot. Only a real container menu also tells the client to
     * close its screen: for the player's own inventory menu that packet would close whatever the client shows, including
     * the loading screen of the dimension the player is entering, so its first frame would be drawn without terrain.
     */
    public static void closeMenu(net.minecraft.server.level.ServerPlayer player){
        if(player.containerMenu!=player.inventoryMenu)player.closeContainer();else player.doCloseContainer();
    }
}
