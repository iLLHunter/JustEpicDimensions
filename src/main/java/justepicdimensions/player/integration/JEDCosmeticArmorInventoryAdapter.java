package justepicdimensions.player.integration;

import java.util.*;
import lain.mods.cos.api.CosArmorAPI;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class JEDCosmeticArmorInventoryAdapter extends JEDExtendedInventoryAdapter {
    public String id(){return "cosmeticarmorreworked";}
    protected List<String> names(ServerPlayer player){return List.of("armor");}
    protected Binding binding(ServerPlayer player,String name,boolean physical){var armor=CosArmorAPI.getCAStacks(player.getUUID());return new Binding(){
        public Object owner(){return armor;}
        public String part(){return "cosmetic";}
        public int size(){return armor.getSlots();}
        public ItemStack get(int index){return armor.getStackInSlot(index);}
        public void set(int index,ItemStack value){armor.setStackInSlot(index,value.copy());}
        public boolean valid(int index,ItemStack value){return value.isEmpty()||armor.isItemValid(index,value);}
        public int limit(int index,ItemStack value){return Math.min(armor.getSlotLimit(index),value.getMaxStackSize());}
    };}
    protected void captureMetadata(ServerPlayer player,CompoundTag result){var armor=CosArmorAPI.getCAStacks(player.getUUID());var metadata=new CompoundTag();metadata.putString("Hidden",armor.serializeNBT(player.registryAccess()).getString("Hidden"));var flags=new byte[armor.getSlots()];for(int i=0;i<flags.length;i++)flags[i]=(byte)(armor.isSkinArmor(i)?1:0);metadata.putByteArray("skin",flags);result.put("metadata",metadata);}
    protected void restoreMetadata(ServerPlayer player,CompoundTag saved){var armor=CosArmorAPI.getCAStacks(player.getUUID());var metadata=saved.getCompound("metadata");var nativeTag=new CompoundTag();nativeTag.putInt("Size",armor.getSlots());nativeTag.putString("Hidden",metadata.getString("Hidden"));var items=new ListTag();var flags=metadata.getByteArray("skin");for(int i=0;i<Math.min(flags.length,armor.getSlots());i++)if(flags[i]!=0){var item=new CompoundTag();item.putInt("Slot",i);item.putBoolean("isSkinArmor",true);items.add(item);}nativeTag.put("Items",items);armor.deserializeNBT(player.registryAccess(),nativeTag);}
    // Native setStackInSlot/deserializeNBT notify the mod's owner/tracking sync listeners.
}
