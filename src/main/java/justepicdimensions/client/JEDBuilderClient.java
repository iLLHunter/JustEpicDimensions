package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.builder.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDBuilderClient {
    static UUID session;static int sequence;static boolean busy;static JEDBuilderScreen screen;
    static Consumer<JsonObject> registryReply,terrainReply,bundleReply,pregenReply,instanceReply,worldgenReply;static Consumer<String> registryError;private static final JEDBuilderWire.Assembly IN=new JEDBuilderWire.Assembly();
    public static void open(){var mc=Minecraft.getInstance();if(mc.getConnection()==null)return;terrainReply=null;worldgenReply=null;registryError=null;mc.setScreen(new Waiting());PacketDistributor.sendToServer(new JEDBuilderWire.Request(new JEDBuilderWire.Frame(JEDBuilderWire.OPEN,0,0,1,"open")));}
    static void request(JsonObject data){if(busy||session==null)return;var frames=JEDBuilderWire.split(session,sequence+1,data.toString());sequence++;busy=true;for(var frame:frames)PacketDistributor.sendToServer(new JEDBuilderWire.Request(frame));}
    static void receive(JEDBuilderWire.Frame f){
        if(JEDTeamClient.receive(f))return;if(JEDMonitoringClient.receive(f))return;
        if(f.session().equals(JEDBuilderWire.OPEN)&&f.request()==0){busy=false;Minecraft.getInstance().setScreen(new Message(text(JEDBuilderWire.json(f.text()),"message")));return;}
        if(f.request()==0&&f.index()==0&&!f.session().equals(JEDBuilderWire.OPEN)){session=f.session();sequence=0;IN.clear();}
        if(!Objects.equals(session,f.session())||f.request()!=sequence)return;
        boolean selectingTerrain=terrainReply!=null;
        try{String text=IN.accept(f,System.nanoTime());if(text==null)return;var json=JEDBuilderWire.json(text);busy=false;String kind=json.get("kind").getAsString();
            if(worldgenReply!=null&&(kind.equals("worldgen")||kind.equals("error"))){var reply=worldgenReply;worldgenReply=null;if(kind.equals("error"))json.addProperty("error",JEDBuilderClient.text(json,"message"));reply.accept(json);return;}
            if(kind.equals("error")&&registryError!=null){var failed=registryError;registryError=null;registryReply=null;failed.accept(JEDBuilderClient.text(json,"message"));return;}
            if(kind.equals("error")&&pregenReply!=null){var reply=pregenReply;pregenReply=null;var value=new JsonObject();value.addProperty("error",JEDBuilderClient.text(json,"message"));reply.accept(value);return;}
            if(kind.equals("error")&&instanceReply!=null){var reply=instanceReply;instanceReply=null;var value=new JsonObject();value.addProperty("error",JEDBuilderClient.text(json,"message"));reply.accept(value);return;}
            if(kind.equals("instances")){var reply=instanceReply;instanceReply=null;if(reply!=null)reply.accept(json);return;}
            if(kind.equals("pregen")){var reply=pregenReply;pregenReply=null;if(reply!=null)reply.accept(json);return;}
            if(kind.equals("error")&&bundleReply!=null){var reply=bundleReply;bundleReply=null;var value=new JsonObject();value.addProperty("error",JEDBuilderClient.text(json,"message"));reply.accept(value);return;}
            if(kind.equals("bundle")){var reply=bundleReply;bundleReply=null;if(reply!=null)reply.accept(json);return;}
            if(kind.equals("error")){terrainReply=null;registryReply=null;if(screen!=null){var message=JEDText.read(json,"message",Minecraft.getInstance().getConnection().registryAccess());if(selectingTerrain){screen.terrainError(message);screen.afterUpdate=null;Minecraft.getInstance().setScreen(screen);}else screen.failed(message);}return;}
            if(kind.equals("registry")){var reply=registryReply;registryReply=null;if(reply!=null)reply.accept(json);registryError=null;return;}
            if(kind.equals("terrain")){var reply=terrainReply;terrainReply=null;if(reply!=null)reply.accept(json);return;}
            if(screen!=null&&screen.dirty()&&f.request()==0){screen.status=JEDText.tr("gui.kept").getString();Minecraft.getInstance().setScreen(screen);return;}
            if(screen==null)screen=new JEDBuilderScreen();Minecraft.getInstance().setScreen(screen);screen.receive(json);
        }catch(Exception error){busy=false;terrainReply=null;registryReply=null;IN.clear();if(registryError!=null){var failed=registryError;registryError=null;failed.accept(JEDText.error(error).getString());return;}if(worldgenReply!=null){var reply=worldgenReply;worldgenReply=null;var failure=new JsonObject();failure.addProperty("error",JEDText.error(error).getString());reply.accept(failure);return;}if(screen!=null){if(selectingTerrain){screen.terrainError(JEDText.error(error));screen.afterUpdate=null;Minecraft.getInstance().setScreen(screen);}else screen.failed(JEDText.error(error));}}
    }
    static void close(){if(!busy&&session!=null){var data=new JsonObject();data.addProperty("op","close");request(data);}session=null;screen=null;busy=false;registryReply=null;terrainReply=null;bundleReply=null;pregenReply=null;instanceReply=null;worldgenReply=null;registryError=null;IN.clear();Minecraft.getInstance().setScreen(null);}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){JEDTeamClient.logout();session=null;screen=null;busy=false;registryReply=null;terrainReply=null;bundleReply=null;pregenReply=null;instanceReply=null;worldgenReply=null;registryError=null;IN.clear();}
    @SubscribeEvent public static void pause(ScreenEvent.Init.Post e){var mc=Minecraft.getInstance();if(!(e.getScreen() instanceof PauseScreen pause)||!pause.showsPauseMenu()||mc.player==null||!mc.player.hasPermissions(2))return;
        // Find a free rectangle; preserve native and other mods' controls even at small GUI sizes.
        int w=100,h=20,x=8,y=8;boolean placed=false;for(int row=8;row<=pause.height-h-4&&!placed;row+=24)for(int col=8;col<=pause.width-w-4;col+=104){final int cx=col,cy=row;boolean overlap=e.getListenersList().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).anyMatch(v->v.visible&&cx<v.getX()+v.getWidth()+3&&cx+w+3>v.getX()&&cy<v.getY()+v.getHeight()+3&&cy+h+3>v.getY());if(!overlap){x=col;y=row;placed=true;break;}}
        if(placed)e.addListener(Button.builder(Component.translatable("justepicdimensions.gui.open"),b->open()).bounds(x,y,w,h).build());
    }
    @EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
    public static final class Setup {@SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(()->JEDBuilderWire.receiver((f,c)->receive(f)));}}
    private static final class Waiting extends Screen {Waiting(){super(Component.translatable("justepicdimensions.gui.connecting"));}public boolean isPauseScreen(){return false;}public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,height/2,0xffffff);}}
    private static final class Message extends Screen {
        Message(String text){super(Component.literal(text));}public boolean isPauseScreen(){return false;}
        protected void init(){addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(width/2-75,height-40,150,20).build());}
        public void render(net.minecraft.client.gui.GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawWordWrap(font,title,20,40,width-40,0xffffff);}
    }
    static String text(JsonObject json,String field){return JEDText.read(json,field,Minecraft.getInstance().getConnection().registryAccess()).getString();}
    private JEDBuilderClient(){}
}
