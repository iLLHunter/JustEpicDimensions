package justepicdimensions.player;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Strict parsing never turns an unavailable item/component into a silently empty slot. */
public final class JEDInventoryCodec {
    /** Native APIs return removed live slots to the player. Keep a backup without granting them twice. */
    public static void nativeContainers(CompoundTag result,CompoundTag previous,Set<String> current,boolean restoring){
        if(!restoring){var backup=result.getCompound("@native_removed").copy();for(var raw:previous.getList("@available_containers",Tag.TAG_STRING)){
            String name=raw.getAsString();if(!current.contains(name)&&result.contains(name)){backup.put(name,result.getCompound(name).copy());result.remove(name);}
        }if(!backup.isEmpty())result.put("@native_removed",backup);}
        var names=new ListTag();current.forEach(name->names.add(StringTag.valueOf(name)));if(!names.isEmpty()||previous.contains("@available_containers"))result.put("@available_containers",names);
    }
    public static CompoundTag item(ServerPlayer player, ItemStack stack) {
        return (CompoundTag) ItemStack.OPTIONAL_CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), stack)
            .getOrThrow(message -> new IllegalStateException("Cannot save inventory item: " + message));
    }
    public static ItemStack item(ServerPlayer player, Tag tag) {
        return ItemStack.OPTIONAL_CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, player.registryAccess()), tag)
            .getOrThrow(message -> new IllegalStateException("Cannot restore inventory item; saved data retained: " + message));
    }
    public static CompoundTag capture(ServerPlayer player, JEDInventoryAdapter.Binding binding, CompoundTag previous) {
        var tag = previous.copy();
        var deferred=previous.getList("deferred",Tag.TAG_COMPOUND).copy();
        var items = new ListTag();
        for (int i=0;i<binding.size();i++) items.add(item(player,binding.get(i)));
        tag.put("items",items);
        tag.put("deferred",deferred);
        return tag;
    }
    public static void validate(ServerPlayer player, CompoundTag saved, int available) {
        var items=saved.getList("items",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(available,items.size());i++)item(player,items.getCompound(i));
        for(var raw:saved.getList("deferred",Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;
            if(entry.getInt("index")<available)item(player,entry.getCompound("item"));
        }
    }
    public static CompoundTag restore(ServerPlayer player, JEDInventoryAdapter.Binding binding, CompoundTag saved) {
        return restore(player,binding,saved,false);
    }
    /** Optional native stores may change acceptance rules between saves; retain incompatible stacks. */
    public static CompoundTag restoreCompatible(ServerPlayer player,JEDInventoryAdapter.Binding binding,CompoundTag saved){
        return restore(player,binding,saved,true);
    }
    private static CompoundTag restore(ServerPlayer player,JEDInventoryAdapter.Binding binding,CompoundTag saved,boolean compatible){
        var desired=new TreeMap<Integer,CompoundTag>();var deferred=new ListTag();
        var items=saved.getList("items",Tag.TAG_COMPOUND);
        for(int i=0;i<items.size();i++)desired.put(i,items.getCompound(i));
        for(var raw:saved.getList("deferred",Tag.TAG_COMPOUND)){
            var entry=(CompoundTag)raw;int index=entry.getInt("index");
            if(index<0)throw new IllegalStateException("Invalid deferred inventory slot "+index);
            if(desired.containsKey(index)&&!desired.get(index).isEmpty())defer(deferred,index,entry.getCompound("item"));
            else desired.put(index,entry.getCompound("item"));
        }
        var parsed=new HashMap<Integer,ItemStack>();
        for(var entry:desired.entrySet())if(entry.getKey()<binding.size()){
            var stack=item(player,entry.getValue());
            if(compatible&&!stack.isEmpty()&&(!binding.valid(entry.getKey(),stack)||stack.getCount()>binding.limit(entry.getKey(),stack)))defer(deferred,entry.getKey(),entry.getValue());
            else parsed.put(entry.getKey(),stack);
        }
        for(int i=0;i<binding.size();i++)binding.set(i,parsed.getOrDefault(i,ItemStack.EMPTY).copy());
        desired.forEach((index,stack)->{if(index>=binding.size()&&!stack.isEmpty()){var entry=new CompoundTag();entry.putInt("index",index);entry.put("item",stack.copy());deferred.add(entry);}});
        var base=saved.copy();base.remove("items");base.remove("deferred");var result=capture(player,binding,base);result.put("deferred",deferred);return result;
    }
    /** Recover held items only into their empty, valid native indices; conflicts stay held. */
    public static CompoundTag recover(ServerPlayer player,JEDInventoryAdapter.Binding binding,CompoundTag saved,boolean returned){
        var pending=saved.getList("deferred",Tag.TAG_COMPOUND).copy();
        if(returned){var old=saved.getList("items",Tag.TAG_COMPOUND);for(int i=0;i<old.size();i++){
            var item=old.getCompound(i);if(item.isEmpty())continue;
            if(i<binding.size()&&ItemStack.matches(binding.get(i),item(player,item)))continue;
            defer(pending,i,item);
        }}
        var remaining=new ListTag();var writes=new TreeMap<Integer,ItemStack>();
        for(var raw:pending){var entry=(CompoundTag)raw;int index=entry.getInt("index");
            if(index<0)throw new IllegalStateException("Invalid deferred inventory slot "+index);
            if(index>=binding.size()||!binding.get(index).isEmpty()||writes.containsKey(index)){remaining.add(entry.copy());continue;}
            var stack=item(player,entry.getCompound("item"));
            if(!binding.valid(index,stack)||stack.getCount()>binding.limit(index,stack)){remaining.add(entry.copy());continue;}
            writes.put(index,stack);
        }
        writes.forEach(binding::set);var base=saved.copy();base.remove("items");base.remove("deferred");var result=capture(player,binding,base);result.put("deferred",remaining);return result;
    }
    private static void defer(ListTag list,int index,CompoundTag item){if(!item.isEmpty()){var entry=new CompoundTag();entry.putInt("index",index);entry.put("item",item.copy());list.add(entry);}}
    private JEDInventoryCodec() {}
}
