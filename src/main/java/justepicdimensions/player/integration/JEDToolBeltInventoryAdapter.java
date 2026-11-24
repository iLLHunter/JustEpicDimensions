package justepicdimensions.player.integration;

import java.util.*;
import dev.gigaherz.toolbelt.ConfigData;
import dev.gigaherz.toolbelt.slot.BeltAttachment;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class JEDToolBeltInventoryAdapter extends JEDExtendedInventoryAdapter {
    public String id(){return "toolbelt";}
    protected List<String> names(ServerPlayer player){return List.of("belt");}
    protected Binding binding(ServerPlayer player,String name,boolean physical){var belt=BeltAttachment.get(player);return new Binding(){
        public Object owner(){return belt;}
        public String part(){return "belt";}
        public int size(){return physical||ConfigData.customBeltSlotEnabled?1:0;}
        public ItemStack get(int index){return belt.getContents();}
        public void set(int index,ItemStack value){belt.setContents(value.copy());}
        public boolean valid(int index,ItemStack value){return value.isEmpty()||ConfigData.customBeltSlotEnabled&&belt.canEquip(value);}
        public int limit(int index,ItemStack value){return 1;}
    };}
    public void sync(ServerPlayer player){BeltAttachment.get(player).syncToTracking(player);}
}
