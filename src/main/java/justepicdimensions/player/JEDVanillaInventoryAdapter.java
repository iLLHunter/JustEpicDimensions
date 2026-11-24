package justepicdimensions.player;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;

public final class JEDVanillaInventoryAdapter implements JEDInventoryAdapter {
    private static final List<String> NAMES=List.of("hotbar","main","armor","offhand","ender_chest");
    public String id(){return "minecraft";}
    public boolean carriedContainer(JEDLoadoutDefinition.Address address){return Set.of("hotbar","main","offhand").contains(address.container());}
    public List<JEDLoadoutDefinition.Address> retainedContainers(ServerPlayer player){return List.of("hotbar","main","armor","offhand").stream().map(name->new JEDLoadoutDefinition.Address(id(),name,false)).toList();}
    public List<String> snapshotPath(JEDLoadoutDefinition.Address address){return List.of(id(),address.container());}
    public List<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment> equipped(ServerPlayer player) {
        var result = new ArrayList<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment>();
        for (var slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
                EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
            var item = player.getItemBySlot(slot);
            if (!item.isEmpty()) result.add(new justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment(id(), slot.getName(), 0, item));
        }
        return List.copyOf(result);
    }
    public CompoundTag capture(ServerPlayer player,CompoundTag previous){
        var result=previous.copy();
        for(var name:NAMES)result.put(name,JEDInventoryCodec.capture(player,resolve(player,new JEDLoadoutDefinition.Address(id(),name,false)),previous.getCompound(name)));
        result.putInt("selected",player.getInventory().selected);return result;
    }
    public void validate(ServerPlayer player,CompoundTag saved){for(var name:NAMES)JEDInventoryCodec.validate(player,saved.getCompound(name),resolve(player,new JEDLoadoutDefinition.Address(id(),name,false)).size());}
    public CompoundTag restore(ServerPlayer player,CompoundTag saved){
        validate(player,saved);var result=saved.copy();
        for(var name:NAMES)result.put(name,JEDInventoryCodec.restore(player,resolve(player,new JEDLoadoutDefinition.Address(id(),name,false)),saved.getCompound(name)));
        player.getInventory().selected=Math.max(0,Math.min(8,saved.getInt("selected")));return result;
    }
    public Binding resolve(ServerPlayer player,JEDLoadoutDefinition.Address address){
        if(address.cosmetic())return null;
        int offset,size;
        switch(address.container()){
            case "hotbar"->{offset=0;size=9;}
            case "main"->{offset=9;size=27;}
            case "armor"->{offset=36;size=4;}
            case "offhand"->{offset=40;size=1;}
            case "ender_chest"->{offset=0;size=player.getEnderChestInventory().getContainerSize();}
            default->{return null;}
        }
        Container inventory=address.container().equals("ender_chest")?player.getEnderChestInventory():player.getInventory();
        return new Binding(){
            public Object owner(){return inventory;}
            public String part(){return address.container();}
            public int size(){return size;}
            public ItemStack get(int index){return inventory.getItem(offset+index);}
            public void set(int index,ItemStack value){inventory.setItem(offset+index,value.copy());}
            public boolean valid(int index,ItemStack value){
                if(value.isEmpty())return true;
                if(address.container().equals("armor"))return value.canEquip(EquipmentSlot.values()[EquipmentSlot.FEET.ordinal()+index],player);
                return inventory.canPlaceItem(offset+index,value);
            }
            public int limit(int index,ItemStack value){return address.container().equals("armor")?1:inventory.getMaxStackSize(value);}
        };
    }
    public void reconcile(ServerPlayer player){player.detectEquipmentUpdates();}
    public void sync(ServerPlayer player){player.getInventory().setChanged();player.inventoryMenu.broadcastFullState();if(player.containerMenu!=player.inventoryMenu)player.containerMenu.broadcastFullState();player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(player.getInventory().selected));}
}
