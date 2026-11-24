package justepicdimensions.player.integration;

import java.util.*;
import justepicdimensions.player.*;
import net.blay09.mods.trashslot.TrashHelper;
import net.blay09.mods.trashslot.network.MessageTrashSlotContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class JEDTrashSlotInventoryAdapter extends JEDExtendedInventoryAdapter {
    public String id(){return "trashslot";}
    protected List<String> names(ServerPlayer player){return List.of("trash");}
    protected Binding binding(ServerPlayer player,String name,boolean physical){return new Binding(){
        public Object owner(){return player;}
        public String part(){return "trashslot:trash";}
        public int size(){return 1;}
        public ItemStack get(int index){return TrashHelper.getTrashItem(player);}
        public void set(int index,ItemStack value){TrashHelper.setTrashItem(player,value.copy());}
        public boolean valid(int index,ItemStack value){return value.isEmpty()||TrashHelper.canDelete(value);}
    };}
    /** Undo storage is excluded from global retention and discarded on inventory-group swaps. */
    public List<JEDLoadoutDefinition.Address> retainedContainers(ServerPlayer player){return List.of();}
    public void sync(ServerPlayer player){PacketDistributor.sendToPlayer(player,new MessageTrashSlotContent(TrashHelper.getTrashItem(player)));}
}
