package justepicdimensions.item;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

public final class JEDItems {
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems("justepicdimensions");
    public static final DeferredItem<JEDGuideBookItem> GUIDE_BOOK=ITEMS.register("guide_book",()->new JEDGuideBookItem(new Item.Properties()));
    public static void register(IEventBus bus){ITEMS.register(bus);}
    private JEDItems(){}
}
