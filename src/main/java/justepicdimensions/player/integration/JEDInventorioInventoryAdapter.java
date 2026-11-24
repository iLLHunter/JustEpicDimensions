package justepicdimensions.player.integration;

import java.util.*;
import de.rubixdev.inventorio.player.PlayerInventoryAddon;
import de.rubixdev.inventorio.packet.InventorioNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class JEDInventorioInventoryAdapter extends JEDExtendedInventoryAdapter {
    public String id(){return "inventorio";}
    public boolean carriedContainer(justepicdimensions.player.JEDLoadoutDefinition.Address address){return address.container().equals("deep_pockets")||address.container().equals("utility_belt");}
    private PlayerInventoryAddon addon(ServerPlayer player){return Objects.requireNonNull(PlayerInventoryAddon.getInventoryAddon(player),"Inventorio player inventory is unavailable; refusing an incomplete swap");}
    protected List<String> names(ServerPlayer player){var names=new ArrayList<String>(List.of("deep_pockets","utility_belt"));for(var template:PlayerInventoryAddon.getToolBeltTemplates())names.add("toolbelt/"+template.getName());return List.copyOf(names);}
    protected Binding binding(ServerPlayer player,String name,boolean physical){var addon=addon(player);var templates=PlayerInventoryAddon.getToolBeltTemplates();var stacks=name.equals("deep_pockets")?addon.deepPockets:name.equals("utility_belt")?addon.utilityBelt:addon.toolBelt;int offset=0;if(name.startsWith("toolbelt/")){String key=name.substring(9);offset=java.util.stream.IntStream.range(0,templates.size()).filter(i->templates.get(i).getName().equals(key)).findFirst().orElseThrow();}int start=offset;return new Binding(){
        public Object owner(){return addon;}
        public String part(){return name;}
        public int size(){if(name.startsWith("toolbelt/"))return 1;return physical?stacks.size():name.equals("deep_pockets")?addon.getDeepPocketsRowCount()*9:addon.getAvailableUtilityBeltSize();}
        public ItemStack get(int index){return stacks.get(start+index);}
        public void set(int index,ItemStack value){stacks.set(start+index,value.copy());addon.setChanged();}
        public boolean valid(int index,ItemStack value){if(value.isEmpty())return true;if(name.startsWith("toolbelt/"))return templates.get(start).test(value,addon);return index<(name.equals("deep_pockets")?addon.getDeepPocketsRowCount()*9:addon.getAvailableUtilityBeltSize());}
        public int limit(int index,ItemStack value){return name.startsWith("toolbelt/")?1:value.getMaxStackSize();}
    };}
    protected void captureMetadata(ServerPlayer player,CompoundTag result){result.putInt("selected_utility",addon(player).getSelectedUtility());}
    protected void restoreMetadata(ServerPlayer player,CompoundTag saved){addon(player).setDisplayTool(ItemStack.EMPTY);addon(player).setSelectedUtility(Math.max(0,Math.min(addon(player).getAvailableUtilityBeltSize()-1,saved.getInt("selected_utility"))));}
    public void beforeRestore(ServerPlayer player){addon(player).setDisplayTool(ItemStack.EMPTY);super.beforeRestore(player);}
    public void sync(ServerPlayer player){addon(player).updateState();InventorioNetworking.getInstance().s2cSelectUtilitySlot(player);}
}
