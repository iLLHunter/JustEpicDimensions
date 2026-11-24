package justepicdimensions.player.integration;

import java.util.*;
import justepicdimensions.player.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Independent player stores share JED's strict item codec and group-local deferred recovery. */
abstract class JEDExtendedInventoryAdapter implements JEDInventoryAdapter {
    protected abstract List<String> names(ServerPlayer player);
    protected abstract Binding binding(ServerPlayer player,String name,boolean physical);
    protected void captureMetadata(ServerPlayer player,CompoundTag result) {}
    protected void restoreMetadata(ServerPlayer player,CompoundTag saved) {}
    protected JEDLoadoutDefinition.Address address(String name){return new JEDLoadoutDefinition.Address(id(),name,false);}
    public Binding resolve(ServerPlayer player,JEDLoadoutDefinition.Address address){return address.cosmetic()||!names(player).contains(address.container())?null:binding(player,address.container(),false);}
    public Binding resolveRetained(ServerPlayer player,JEDLoadoutDefinition.Address address){return address.cosmetic()||!names(player).contains(address.container())?null:binding(player,address.container(),true);}
    public List<String> snapshotPath(JEDLoadoutDefinition.Address address){return List.of(id(),address.container());}
    public CompoundTag capture(ServerPlayer player,CompoundTag previous){
        var result=previous.copy();var available=new ListTag();
        for(var name:names(player)){result.put(name,JEDInventoryCodec.capture(player,binding(player,name,true),previous.getCompound(name)));available.add(StringTag.valueOf(name));}
        result.put("@available_containers",available);captureMetadata(player,result);return result;
    }
    public void validate(ServerPlayer player,CompoundTag saved){for(var name:names(player))JEDInventoryCodec.validate(player,saved.getCompound(name),Integer.MAX_VALUE);}
    public void beforeRestore(ServerPlayer player){for(var name:names(player)){var b=binding(player,name,true);for(int i=0;i<b.size();i++)if(!b.get(i).isEmpty())b.set(i,ItemStack.EMPTY);}}
    public CompoundTag restore(ServerPlayer player,CompoundTag saved){
        validate(player,saved);restoreMetadata(player,saved);var result=saved.copy();
        for(var name:names(player))result.put(name,JEDInventoryCodec.restoreCompatible(player,binding(player,name,false),saved.getCompound(name)));
        return result;
    }
    public CompoundTag recover(ServerPlayer player,CompoundTag saved,boolean returned){
        var result=saved.copy();var available=saved.getList("@available_containers",Tag.TAG_STRING);
        // A reappearing empty backend needs its group metadata as well as its stacks.
        // Never reset a populated native store: recovery must not overwrite live items.
        boolean empty=returned;
        if(empty)for(var name:names(player)){var physical=binding(player,name,true);for(int i=0;i<physical.size();i++)if(!physical.get(i).isEmpty())empty=false;}
        if(empty&&!saved.isEmpty())restoreMetadata(player,saved);
        for(var name:names(player)){boolean missing=returned||saved.contains("@available_containers")&&!available.contains(StringTag.valueOf(name));result.put(name,JEDInventoryCodec.recover(player,binding(player,name,false),saved.getCompound(name),missing));}
        return capture(player,result);
    }
    public List<JEDLoadoutDefinition.Address> retainedContainers(ServerPlayer player){return names(player).stream().map(this::address).toList();}
}
