package justepicdimensions.client;

import com.mojang.logging.LogUtils;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Session-only tour reading position and prepared commands in the existing book readers. */
@EventBusSubscriber(modid="justepicdimensions",value=Dist.CLIENT)
public final class JEDShowcaseBookLinks {
    private static final String MARKER=justepicdimensions.showcase.JEDShowcaseGuide.MARKER;
    private static final Map<UUID,Integer> PAGES=new HashMap<>();
    private static final Map<Screen,OpenBook> OPEN=new IdentityHashMap<>();
    private static final Set<Class<?>> FAILED=new HashSet<>();
    private interface Reader {
        List<Component> pages() throws ReflectiveOperationException;
        int page() throws ReflectiveOperationException;
        void page(int page) throws ReflectiveOperationException;
        Style style(double x,double y) throws ReflectiveOperationException;
    }
    private record OpenBook(UUID player,Reader reader) {}
    private record NativeReader(BookViewScreen screen) implements Reader {
        public List<Component> pages(){return screen.bookAccess.pages();}
        public int page(){return screen.currentPage;}
        public void page(int page){screen.setPage(page);}
        public Style style(double x,double y){return screen.getClickedComponentStyleAt(x,y);}
    }
    /** No Scholar dependency or class loading on installations using the native reader. */
    private record ScholarAccess(Field spread,Method access,Method book,Method setPage,Method style) {}
    private static final ClassValue<Optional<ScholarAccess>> SCHOLAR=new ClassValue<>() {
        @Override protected Optional<ScholarAccess> computeValue(Class<?> type){
            for(var parent=type;parent!=null;parent=parent.getSuperclass()){
                if(!parent.getName().equals("io.github.mortuusars.scholar.client.gui.screen.view.SpreadBookViewScreen"))continue;
                try{
                    var spread=parent.getSuperclass().getDeclaredField("currentSpread");
                    if(!spread.trySetAccessible())throw new IllegalAccessException("Scholar currentSpread is inaccessible");
                    var access=parent.getMethod("getBookAccess");
                    return Optional.of(new ScholarAccess(spread,access,access.getReturnType().getMethod("getBook"),parent.getMethod("setPage",int.class),parent.getMethod("getClickedComponentStyleAt",double.class,double.class)));
                }catch(ReflectiveOperationException error){failed(type,error);return Optional.empty();}
            }
            return Optional.empty();
        }
    };
    private record ScholarReader(Screen screen,ScholarAccess methods) implements Reader {
        public List<Component> pages()throws ReflectiveOperationException{
            var book=(ItemStack)methods.book().invoke(methods.access().invoke(screen));
            var content=book.get(DataComponents.WRITTEN_BOOK_CONTENT);
            return content==null?List.of():content.getPages(Minecraft.getInstance().isTextFilteringEnabled());
        }
        public int page()throws ReflectiveOperationException{return methods.spread().getInt(screen)*2;}
        public void page(int page)throws ReflectiveOperationException{methods.setPage().invoke(screen,page);}
        public Style style(double x,double y)throws ReflectiveOperationException{return (Style)methods.style().invoke(screen,x,y);}
    }
    private static void failed(Class<?> type,Exception error){
        if(FAILED.add(type))LogUtils.getLogger().warn("JED showcase book reader integration unavailable for {}",type.getName(),error);
    }
    private static OpenBook opened(Screen screen)throws ReflectiveOperationException{
        var existing=OPEN.get(screen);if(existing!=null)return existing;
        var player=Minecraft.getInstance().player;if(player==null||FAILED.contains(screen.getClass()))return null;
        Reader reader=screen instanceof BookViewScreen nativeBook?new NativeReader(nativeBook):SCHOLAR.get(screen.getClass()).<Reader>map(methods->new ScholarReader(screen,methods)).orElse(null);
        if(reader==null)return null;
        boolean guide=reader.pages().stream().anyMatch(page->page.visit((style,text)->MARKER.equals(style.getInsertion())?Optional.of(true):Optional.empty(),Style.EMPTY).orElse(false));
        if(!guide)return null;
        var book=new OpenBook(player.getUUID(),reader);reader.page(PAGES.getOrDefault(book.player(),0));OPEN.put(screen,book);return book;
    }
    private static void remember(OpenBook book)throws ReflectiveOperationException{PAGES.put(book.player(),book.reader().page());}
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event){
        try{opened(event.getScreen());}catch(ReflectiveOperationException error){failed(event.getScreen().getClass(),error);}
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){
        try{var book=opened(event.getScreen());if(book!=null)remember(book);}catch(ReflectiveOperationException error){failed(event.getScreen().getClass(),error);OPEN.remove(event.getScreen());}
    }
    @SubscribeEvent public static void closed(ScreenEvent.Closing event){
        var book=OPEN.remove(event.getScreen());if(book!=null)try{remember(book);}catch(ReflectiveOperationException error){failed(event.getScreen().getClass(),error);}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){OPEN.clear();PAGES.clear();}
    @SubscribeEvent public static void click(ScreenEvent.MouseButtonPressed.Pre event){
        if(event.getButton()!=0)return;
        try{
            var book=opened(event.getScreen());if(book==null)return;
            var style=book.reader().style(event.getMouseX(),event.getMouseY());
            if(style==null||!MARKER.equals(style.getInsertion()))return;
            var action=style.getClickEvent();if(action==null||action.getAction()!=ClickEvent.Action.SUGGEST_COMMAND)return;
            String command=StringUtil.filterText(action.getValue());if(!command.startsWith("/")||command.length()>256)return;
            remember(book);event.setCanceled(true);event.getScreen().onClose();Minecraft.getInstance().setScreen(new ChatScreen(command));
        }catch(ReflectiveOperationException error){failed(event.getScreen().getClass(),error);OPEN.remove(event.getScreen());}
    }
    private JEDShowcaseBookLinks(){}
}
