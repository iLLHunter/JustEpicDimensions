package justepicdimensions.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Only isolated optional implementations reference third-party classes. */
public interface JEDInventoryAdapter {
    String id();
    CompoundTag capture(ServerPlayer player, CompoundTag previous);
    void validate(ServerPlayer player, CompoundTag saved);
    /** After the complete source snapshot, before any backend can replace capacity-granting equipment. */
    default void beforeRestore(ServerPlayer player) {}
    CompoundTag restore(ServerPlayer player, CompoundTag saved);
    Binding resolve(ServerPlayer player, JEDLoadoutDefinition.Address address);
    /** Retention must inspect hidden backing slots too, while native validity still governs placement. */
    default Binding resolveRetained(ServerPlayer player,JEDLoadoutDefinition.Address address){return resolve(player,address);}
    /** General carried space eligible for retained-item overflow, rather than equipment-only slots. */
    default boolean carriedContainer(JEDLoadoutDefinition.Address address){return false;}
    /** Physical carried/equipped containers, including cosmetic slots; excludes remote storage. */
    default java.util.List<JEDLoadoutDefinition.Address> retainedContainers(ServerPlayer player){return java.util.List.of();}
    /** Exact snapshot path. Never traverse arbitrary item components or nested inventories. */
    default java.util.List<String> snapshotPath(JEDLoadoutDefinition.Address address){return java.util.List.of(address.backend(),address.container(),address.cosmetic()?"cosmetic":"functional");}
    default CompoundTag recover(ServerPlayer player,CompoundTag saved,boolean returned){return saved;}
    default void reconcile(ServerPlayer player) {}
    default void sync(ServerPlayer player) {}
    default java.util.List<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment> equipped(ServerPlayer player) { return java.util.List.of(); }

    interface Binding {
        Object owner();
        String part();
        int size();
        ItemStack get(int index);
        void set(int index, ItemStack value);
        boolean valid(int index, ItemStack value);
        default int limit(int index, ItemStack value) { return value.getMaxStackSize(); }
    }
}
