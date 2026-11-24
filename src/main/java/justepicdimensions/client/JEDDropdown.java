package justepicdimensions.client;

import java.util.*;
import java.util.function.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.builder.JEDBuilderFieldInfo;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Searchable, paged dropdown over native buttons. An open menu consumes input before its parent. */
@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDDropdown extends Button {
    public record Page(List<String> values,int offset,int total){public Page{values=List.copyOf(values);}}
    @FunctionalInterface public interface Source {boolean load(String query,int offset,Consumer<Page> reply);default Component label(String value){return null;}default Component details(String value){return null;}}
    public static Source local(List<String> values){return local(()->values);}
    public static Source local(Supplier<List<String>> values,Function<String,Component> labels,Function<String,Component> details){return new Source(){public boolean load(String query,int offset,Consumer<Page> reply){String search=query.toLowerCase(Locale.ROOT);var filtered=values.get().stream().filter(value->value.toLowerCase(Locale.ROOT).contains(search)||labels.apply(value).getString().toLowerCase(Locale.ROOT).contains(search)).toList();reply.accept(new Page(filtered.stream().skip(offset).limit(80).toList(),offset,filtered.size()));return true;}public Component label(String value){return value.isEmpty()?null:labels.apply(value);}public Component details(String value){return details.apply(value);}};}
    public static Source local(Supplier<List<String>> values,Function<String,Component> details){var delegate=local(values);return new Source(){public boolean load(String query,int offset,Consumer<Page> reply){return delegate.load(query,offset,reply);}public Component details(String value){return details.apply(value);}};}
    public static Source local(Supplier<List<String>> values){return (query,offset,reply)->{var filtered=values.get().stream().filter(v->v.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))||label(v).getString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();reply.accept(new Page(filtered.stream().skip(offset).limit(80).toList(),offset,filtered.size()));return true;};}
    public static Component label(String value){
        if(value.startsWith("justepicdimensions:")){String path=value.substring("justepicdimensions:".length());if(Set.of("native","none","surface","nether","end").contains(path))return label(path).copy().append(" ("+value+")");}
        if(value.equals("true")||value.equals("false"))return Component.translatable(value.equals("true")?"options.on":"options.off");
        if(Set.of("survival","creative","adventure","spectator").contains(value))return Component.translatable("gameMode."+value);
        if(Set.of("peaceful","easy","normal","hard").contains(value))return Component.translatable("options.difficulty."+value);
        if(Set.of("apply","cancel","reset").contains(value))return Component.translatable("justepicdimensions.gui."+value);
        if(Set.of("preset","sound","inventory_group","dimension").contains(value))return JEDBuilderFieldInfo.tr("word."+value);
        if(value.equals("native"))return justepicdimensions.JEDText.fixed("native");
        String key="justepicdimensions.builder.option."+value;
        return net.minecraft.locale.Language.getInstance().has(key)?Component.translatable(key):Component.literal(value);
    }
    private final Component title;private final Source source;private final Function<String,Component> labels;private final Consumer<String> change;private String value;
    private static Menu menu;
    public JEDDropdown(int x,int y,int width,Component title,String value,Source source,Function<String,Component> labels,Consumer<String> change){
        super(x,y,width,20,Component.empty(),b->{},DEFAULT_NARRATION);this.title=title;this.source=source;this.labels=s->{var runtime=source.label(s);return runtime==null?labels.apply(s):runtime;};this.change=change;setValue(value);
    }
    public String value(){return value;}
    public void setValue(String value){this.value=value;var label=labels.apply(value);setMessage((value.isEmpty()&&label.getString().isEmpty()?JEDBuilderFieldInfo.tr("select_value"):label).copy().append(" ▾"));}
    @Override public void onPress(){menu=new Menu(this,Minecraft.getInstance().screen);}
    /** Same commit path for mouse, keyboard and programmatic native-widget users. */
    public void select(String selected){setValue(selected);menu=null;change.accept(selected);}
    @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage(){return title.copy().append(": ").append(getMessage());}
    static Consumer<String> failureHandler(){var pending=menu;return message->{if(pending!=null&&menu==pending){pending.inFlight=false;pending.pending=false;pending.error=message;pending.page=new Page(List.of(),0,0);}};}
    private static Menu current(Screen screen){if(menu!=null&&(menu.owner!=screen||!screen.children().contains(menu.widget)))menu=null;return menu;}
    private static final class Menu {
        final JEDDropdown widget;final Screen owner;final EditBox search;Page page=new Page(List.of(),0,0);int x,y,w,h,rows,scroll,selected;boolean pending=true,inFlight;long due;String error="",loadedQuery="";
        Menu(JEDDropdown widget,Screen owner){this.widget=widget;this.owner=owner;w=Math.min(Math.max(widget.width,180),owner.width-16);rows=Math.max(1,Math.min(6,(owner.height-68)/18));h=28+rows*18+22;x=Math.max(8,Math.min(widget.getX(),owner.width-w-8));y=widget.getY()+22;if(y+h>owner.height-8)y=widget.getY()-h-2;y=Math.max(8,Math.min(y,owner.height-h-8));
            search=new EditBox(Minecraft.getInstance().font,x+4,y+4,w-8,20,JEDBuilderFieldInfo.tr("search_choices"));search.setHint(JEDBuilderFieldInfo.tr("search_choices"));search.setMaxLength(256);search.setResponder(s->{pending=true;due=System.nanoTime()+200_000_000L;scroll=selected=0;});search.setFocused(true);menu=this;fetch();
        }
        void fetch(){if(!pending||inFlight||System.nanoTime()<due)return;String query=search.getValue();int offset=page.offset();inFlight=true;
            try{boolean started=widget.source.load(query,offset,result->{inFlight=false;if(menu!=this)return;page=result;loadedQuery=query;pending=!search.getValue().equals(query);scroll=selected=0;error="";});if(started)pending=false;else inFlight=false;}catch(Exception e){inFlight=false;pending=false;error=String.valueOf(e.getMessage());}
        }
        void request(int offset){page=new Page(List.of(),Math.max(0,offset),page.total());pending=true;due=0;fetch();}
        void filter(){page=new Page(List.of(),0,0);pending=true;due=0;fetch();}
        void draw(GuiGraphics g,int mx,int my){
            if(pending&&!inFlight&&System.nanoTime()>=due){if(!search.getValue().equals(loadedQuery))page=new Page(List.of(),0,0);fetch();}
            var font=Minecraft.getInstance().font;Component hovered=null;g.pose().pushPose();g.pose().translate(0,0,500);g.fill(x-1,y-1,x+w+1,y+h+1,0xffb8c5d0);g.fill(x,y,x+w,y+h,0xff10151b);search.render(g,mx,my,0);
            for(int row=0;row<rows&&scroll+row<page.values().size();row++){int index=scroll+row,top=y+28+row*18;boolean hover=mx>=x+2&&mx<x+w-2&&my>=top&&my<top+18;if(hover||index==selected)g.fill(x+2,top,x+w-2,top+18,hover?0xff40566a:0xff273746);String value=page.values().get(index);var label=widget.labels.apply(value);if(hover){var detail=widget.source.details(value);if(detail!=null)hovered=detail;else if(font.width(label)>w-10)hovered=label;}g.drawString(font,font.plainSubstrByWidth(label.getString(),w-10),x+5,top+5,value.equals(widget.value)?0xffa8efb0:0xffffffff);}
            if(page.values().isEmpty())g.drawString(font,font.plainSubstrByWidth(!error.isEmpty()?error:inFlight||pending?JEDBuilderFieldInfo.tr("choices_loading").getString():JEDBuilderFieldInfo.tr("no_choices").getString(),w-10),x+5,y+33,0xffbbbbbb);
            int bottom=y+h-19;g.drawString(font,"◀",x+6,bottom+5,page.offset()>0?0xffffffff:0xff555555);g.drawString(font,"▶",x+w-14,bottom+5,page.offset()+page.values().size()<page.total()?0xffffffff:0xff555555);String counts=(page.total()==0?"0":(page.offset()+1)+"–"+(page.offset()+page.values().size()))+" / "+page.total();g.drawCenteredString(font,counts,x+w/2,bottom+5,0xffbbbbbb);if(hovered!=null)g.renderTooltip(font,font.split(hovered,Math.max(100,Math.min(owner.width-20,360))),mx,my);g.pose().popPose();
        }
        void click(double mx,double my,int button){if(button!=0){menu=null;return;}if(mx<x||mx>=x+w||my<y||my>=y+h){menu=null;return;}if(my<y+26){search.mouseClicked(mx,my,button);return;}if(inFlight||pending)return;if(my>=y+h-22){if(mx<x+30&&page.offset()>0)request(Math.max(0,page.offset()-80));else if(mx>x+w-30&&page.offset()+page.values().size()<page.total())request(page.offset()+80);return;}int index=scroll+(int)(my-y-28)/18;if(index>=0&&index<page.values().size()&&!inFlight&&!pending)widget.select(page.values().get(index));}
        boolean key(int key,int scan,int mods){if(key==256){menu=null;return true;}if(key==258){menu=null;return false;}if(key==257||key==335){if(pending||!search.getValue().equals(loadedQuery))filter();else if(!inFlight&&!page.values().isEmpty())widget.select(page.values().get(selected));return true;}
            if(key==264||key==265){selected=Math.max(0,Math.min(page.values().size()-1,selected+(key==264?1:-1)));scroll=Math.max(0,Math.min(scroll,selected));if(selected>=scroll+rows)scroll=selected-rows+1;return true;}
            if(key==266||key==267){if(inFlight||pending)return true;if(key==266&&page.offset()>0)request(page.offset()-80);else if(key==267&&page.offset()+page.values().size()<page.total())request(page.offset()+80);return true;}
            search.keyPressed(key,scan,mods);return true;
        }
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event){var active=current(event.getScreen());if(active!=null)active.draw(event.getGuiGraphics(),event.getMouseX(),event.getMouseY());}
    @SubscribeEvent public static void click(ScreenEvent.MouseButtonPressed.Pre event){var active=current(event.getScreen());if(active!=null){active.click(event.getMouseX(),event.getMouseY(),event.getButton());event.setCanceled(true);}}
    @SubscribeEvent public static void key(ScreenEvent.KeyPressed.Pre event){var active=current(event.getScreen());if(active!=null&&active.key(event.getKeyCode(),event.getScanCode(),event.getModifiers()))event.setCanceled(true);}
    @SubscribeEvent public static void character(ScreenEvent.CharacterTyped.Pre event){var active=current(event.getScreen());if(active!=null){active.search.charTyped(event.getCodePoint(),event.getModifiers());event.setCanceled(true);}}
    @SubscribeEvent public static void wheel(ScreenEvent.MouseScrolled.Pre event){var active=current(event.getScreen());if(active!=null){active.scroll=Math.max(0,Math.min(Math.max(0,active.page.values().size()-active.rows),active.scroll-(int)Math.signum(event.getScrollDeltaY())*3));event.setCanceled(true);}}
}
