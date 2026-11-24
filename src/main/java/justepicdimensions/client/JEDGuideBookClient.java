package justepicdimensions.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.*;
import justepicdimensions.item.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Open the registered guide in the configured reader without changing its real inventory item ID. */
@EventBusSubscriber(modid="justepicdimensions",value=Dist.CLIENT)
public final class JEDGuideBookClient {
    private record Scholar(ModConfigSpec.BooleanValue enabled,ModConfigSpec.BooleanValue sneak,Method access,Method color,Constructor<?> screen){}
    private static Scholar scholar;
    private static boolean checked;
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->JEDGuideBookOpen.receiver(JEDGuideBookClient::open));}
    private static Scholar scholar(){
        if(checked)return scholar;checked=true;if(!ModList.get().isLoaded("scholar"))return null;
        try{
            var config=Class.forName("io.github.mortuusars.scholar.Config$Common");var access=Class.forName("io.github.mortuusars.scholar.client.gui.screen.view.BookViewAccess");var color=Class.forName("io.github.mortuusars.scholar.book.BookColor");var screen=Class.forName("io.github.mortuusars.scholar.client.gui.screen.view.InHandSpreadBookViewScreen");
            scholar=new Scholar((ModConfigSpec.BooleanValue)config.getField("IN_HAND_TWO_PAGE_BOOK_SCREEN").get(null),(ModConfigSpec.BooleanValue)config.getField("SNEAKING_OPENS_VANILLA_BOOK_SCREEN").get(null),access.getMethod("fromItem",ItemStack.class),color.getMethod("of",ItemStack.class),screen.getConstructor(access,int.class,InteractionHand.class));
        }catch(ReflectiveOperationException error){LogUtils.getLogger().warn("JED guide reader compatibility unavailable; using the native reader",error);}
        return scholar;
    }
    private static void open(InteractionHand hand){
        var mc=Minecraft.getInstance();if(mc.player==null)return;var book=mc.player.getItemInHand(hand);if(!book.is(JEDItems.GUIDE_BOOK.get()))return;
        var optional=scholar();
        if(optional!=null&&optional.enabled().get()&&!(optional.sneak().get()&&mc.player.isSecondaryUseActive())){
            try{mc.setScreen((Screen)optional.screen().newInstance(optional.access().invoke(null,book),optional.color().invoke(null,book),hand));return;}
            catch(ReflectiveOperationException error){LogUtils.getLogger().warn("JED guide reader could not open; using the native reader",error);}
        }
        var access=BookViewScreen.BookAccess.fromItem(book);if(access!=null)mc.setScreen(new BookViewScreen(access));
    }
    private JEDGuideBookClient(){}
}
