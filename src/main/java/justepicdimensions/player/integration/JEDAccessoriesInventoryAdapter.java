package justepicdimensions.player.integration;

import java.util.*;
import justepicdimensions.player.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import io.wispforest.accessories.api.*;
import io.wispforest.accessories.api.slot.SlotReference;
import io.wispforest.accessories.impl.*;
import io.wispforest.accessories.endec.NbtMapCarrier;
import io.wispforest.accessories.networking.client.SyncEntireContainer;
import io.wispforest.endec.SerializationContext;
import io.wispforest.owo.serialization.RegistriesAttribute;

/** Container serialization avoids grouping the holder's unrelated screen preferences. */
public final class JEDAccessoriesInventoryAdapter implements JEDInventoryAdapter {
    public String id(){return "accessories";}
    public List<JEDLoadoutDefinition.Address> retainedContainers(ServerPlayer player){var result=new ArrayList<JEDLoadoutDefinition.Address>();all(player).keySet().forEach(name->{for(boolean cosmetic:List.of(false,true))result.add(new JEDLoadoutDefinition.Address(id(),name,cosmetic));});return List.copyOf(result);}
    public List<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment> equipped(ServerPlayer player) {
        var result = new ArrayList<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment>();
        all(player).forEach((name, container) -> {
            var stacks = container.getAccessories();
            for (int index = 0; index < stacks.getContainerSize(); index++) {
                var item = stacks.getItem(index);
                if (!item.isEmpty()) result.add(new justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment(id(), name, index, item));
            }
        });
        return List.copyOf(result);
    }
    private Map<String,AccessoriesContainer> all(ServerPlayer player){var capability=AccessoriesCapability.get(player);return capability==null?Map.of():capability.getContainers();}
    private SerializationContext context(ServerPlayer player){return SerializationContext.attributes(RegistriesAttribute.of(player.registryAccess()));}
    public Binding resolve(ServerPlayer player,JEDLoadoutDefinition.Address address){
        var container=all(player).get(address.container());if(container==null)return null;
        return new Binding(){
            public Object owner(){return container;}
            public String part(){return address.cosmetic()?"cosmetic":"functional";}
            private ExpandedSimpleContainer stacks(){return address.cosmetic()?container.getCosmeticAccessories():container.getAccessories();}
            public int size(){return stacks().getContainerSize();}
            public ItemStack get(int index){return stacks().getItem(index);}
            public void set(int index,ItemStack value){stacks().setItem(index,value.copy());}
            public boolean valid(int index,ItemStack value){return value.isEmpty()||AccessoriesAPI.canInsertIntoSlot(value,container.createReference(index));}
            public int limit(int index,ItemStack value){return Math.min(value.getMaxStackSize(),stacks().getMaxStackSize(value));}
        };
    }
    private Binding binding(ServerPlayer p,String name,boolean cosmetic){return resolve(p,new JEDLoadoutDefinition.Address(id(),name,cosmetic));}
    public CompoundTag capture(ServerPlayer player,CompoundTag previous){
        var result=previous.copy();all(player).forEach((name,container)->{var entry=previous.getCompound(name).copy();
            entry.put("functional",JEDInventoryCodec.capture(player,binding(player,name,false),entry.getCompound("functional")));
            entry.put("cosmetic",JEDInventoryCodec.capture(player,binding(player,name,true),entry.getCompound("cosmetic")));
            if(!(container instanceof AccessoriesContainerImpl impl))throw new IllegalStateException("Unsupported Accessories container implementation: "+container.getClass());
            var carrier=NbtMapCarrier.of();impl.write(carrier,context(player));var metadata=carrier.compoundTag();metadata.remove("items");metadata.remove("cosmetics");metadata.remove("current_size");entry.put("metadata",metadata);result.put(name,entry);
        });JEDInventoryCodec.nativeContainers(result,previous,all(player).keySet(),false);return result;
    }
    public void validate(ServerPlayer player,CompoundTag saved){all(player).forEach((name,container)->{var entry=saved.getCompound(name);JEDInventoryCodec.validate(player,entry.getCompound("functional"),Integer.MAX_VALUE);JEDInventoryCodec.validate(player,entry.getCompound("cosmetic"),Integer.MAX_VALUE);});}
    public CompoundTag restore(ServerPlayer player,CompoundTag saved){
        return restoreSelected(player,saved,all(player).keySet());
    }
    private CompoundTag restoreSelected(ServerPlayer player,CompoundTag saved,Set<String> selected){
        validate(player,saved);var containers=new LinkedHashMap<>(all(player));containers.keySet().retainAll(selected);var result=saved.copy();
        containers.forEach((name,container)->{for(boolean cosmetic:List.of(false,true)){var binding=binding(player,name,cosmetic);for(int i=0;i<binding.size();i++)binding.set(i,ItemStack.EMPTY);}});
        reconcile(player);
        containers.forEach((name,container)->{container.clearModifiers();container.clearCachedModifiers();var entry=saved.getCompound(name).copy();var metadata=entry.getCompound("metadata");
            if(!metadata.isEmpty()){if(!(container instanceof AccessoriesContainerImpl impl))throw new IllegalStateException("Unsupported Accessories container");impl.read(new NbtMapCarrier(metadata.copy()),context(player));}
            else Collections.fill(container.renderOptions(),true);
            container.markChanged(true);container.update();
            entry.put("functional",JEDInventoryCodec.restore(player,binding(player,name,false),entry.getCompound("functional")));
            entry.put("cosmetic",JEDInventoryCodec.restore(player,binding(player,name,true),entry.getCompound("cosmetic")));result.put(name,entry);
        });JEDInventoryCodec.nativeContainers(result,saved,all(player).keySet(),true);return result;
    }
    public CompoundTag recover(ServerPlayer player,CompoundTag saved,boolean returned){
        var present=saved.getList("@available_containers",Tag.TAG_STRING);var restore=new HashSet<String>();
        all(player).forEach((name,container)->{if(saved.contains(name)&&(returned||saved.contains("@available_containers")&&!present.contains(StringTag.valueOf(name)))){
            boolean empty=true;for(boolean cosmetic:List.of(false,true)){var b=binding(player,name,cosmetic);for(int i=0;i<b.size();i++)empty&=b.get(i).isEmpty();}if(empty)restore.add(name);
        }});
        var result=restore.isEmpty()?saved.copy():restoreSelected(player,saved,restore);reconcile(player);
        all(player).forEach((name,container)->{var entry=result.getCompound(name).copy();boolean missing=!restore.contains(name)&&(returned||saved.contains("@available_containers")&&!present.contains(StringTag.valueOf(name)));
            for(boolean cosmetic:List.of(false,true)){String part=cosmetic?"cosmetic":"functional";entry.put(part,JEDInventoryCodec.recover(player,binding(player,name,cosmetic),entry.getCompound(part),missing));}result.put(name,entry);
        });return capture(player,result);
    }
    public void reconcile(ServerPlayer player){
        if(!"verified".equals(System.getProperty("jed.player.coremod.accessories")))throw new IllegalStateException("Accessories equipment-reconciliation bridge is unavailable");
        JEDAccessoryRuntime.reconcile(()->AccessoriesEventHandler.onLivingEntityTick(player));
    }
    public void sync(ServerPlayer player){SyncEntireContainer.syncToAllTrackingAndSelf(player);}
    public static void tick(Accessory accessory,ItemStack stack,SlotReference reference){if(!JEDAccessoryRuntime.reconciling())accessory.tick(stack,reference);}
}
