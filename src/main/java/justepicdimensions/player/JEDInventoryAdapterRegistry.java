package justepicdimensions.player;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

public final class JEDInventoryAdapterRegistry {
    public static final Set<String> BACKENDS=Set.of("minecraft","curios","accessories","trashslot","cosmeticarmorreworked","toolbelt","inventorio");
    private static final JEDInventoryAdapter VANILLA=new JEDVanillaInventoryAdapter();
    private static volatile List<JEDInventoryAdapter> adapters;
    public static List<JEDInventoryAdapter> adapters(){
        if(adapters==null){var list=new ArrayList<JEDInventoryAdapter>();list.add(VANILLA);
            if(ModList.get().isLoaded("accessories"))list.add(construct("JEDAccessoriesInventoryAdapter"));
            if(ModList.get().isLoaded("curios"))list.add(construct("JEDCuriosInventoryAdapter"));
            if(ModList.get().isLoaded("trashslot"))list.add(construct("JEDTrashSlotInventoryAdapter"));
            if(ModList.get().isLoaded("cosmeticarmorreworked"))list.add(construct("JEDCosmeticArmorInventoryAdapter"));
            if(ModList.get().isLoaded("toolbelt"))list.add(construct("JEDToolBeltInventoryAdapter"));
            if(ModList.get().isLoaded("inventorio"))list.add(construct("JEDInventorioInventoryAdapter"));
            adapters=List.copyOf(list);
        }return adapters;
    }
    private static JEDInventoryAdapter construct(String name){try{return (JEDInventoryAdapter)Class.forName("justepicdimensions.player.integration."+name).getConstructor().newInstance();}catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot initialize optional inventory adapter "+name,error);}}
    public static JEDInventoryAdapter.Binding resolve(ServerPlayer player,JEDLoadoutDefinition.Address address){
        for(var adapter:adapters())if(adapter.id().equals(address.backend()))return adapter.resolve(player,address);
        return null;
    }
    public static CompoundTag capture(ServerPlayer player,CompoundTag previous){
        var result=previous.copy();var present=new CompoundTag();for(var adapter:adapters()){result.put(adapter.id(),adapter.capture(player,previous.getCompound(adapter.id())));present.putBoolean(adapter.id(),true);}result.put("@available_backends",present);return result;
    }
    public static CompoundTag recover(ServerPlayer player,CompoundTag saved){
        var result=saved.copy();var present=saved.getCompound("@available_backends");
        for(var adapter:adapters())result.put(adapter.id(),adapter.recover(player,saved.getCompound(adapter.id()),saved.contains("@available_backends")&&!present.getBoolean(adapter.id())));
        reconcile(player);return result;
    }
    public static void validate(ServerPlayer player,CompoundTag saved){for(var adapter:adapters())adapter.validate(player,saved.getCompound(adapter.id()));}
    public static CompoundTag restore(ServerPlayer player,CompoundTag saved){
        validate(player,saved);var result=saved.copy();
        for(var adapter:adapters())adapter.beforeRestore(player);
        for(var adapter:adapters())result.put(adapter.id(),adapter.restore(player,saved.getCompound(adapter.id())));
        reconcile(player);return recover(player,result);
    }
    public static void reconcile(ServerPlayer player){JEDAccessoryRuntime.reconcile(()->{for(var adapter:adapters())adapter.reconcile(player);});}
    public static void sync(ServerPlayer player){for(var adapter:adapters())adapter.sync(player);}
    private JEDInventoryAdapterRegistry(){}
}
