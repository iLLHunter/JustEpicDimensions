package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.config.*;
import justepicdimensions.lighting.JEDLightingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.*;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-local preferences and the authenticated remote editor have independent state. */
@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDConfigClient {
    private static JEDConfigScreen screen;
    private static UUID nonce,session;
    private static int sequence;
    private static long began;
    private static boolean busy;
    private static final JEDBuilderWire.Assembly IN=new JEDBuilderWire.Assembly();
    private static JsonObject savedOnlyClientValues;
    public static Screen create(Screen parent){closeSession();screen=new JEDConfigScreen(parent);return screen;}
    public static void open(){Minecraft.getInstance().setScreen(create(null));}
    static boolean remote(){return Minecraft.getInstance().getConnection()!=null;}
    static boolean permitted(){var p=Minecraft.getInstance().player;return p!=null&&p.hasPermissions(2);}
    static boolean busy(){return busy;}
    static void fetch(){
        if(!remote()||!permitted()||busy)return;
        if(session==null){nonce=UUID.randomUUID();busy=true;began=System.nanoTime();PacketDistributor.sendToServer(new JEDConfigWire.Request(new JEDBuilderWire.Frame(JEDBuilderWire.OPEN,0,0,1,nonce.toString())));}
        else{var json=new JsonObject();json.addProperty("op","refresh");request(json);}
    }
    static void request(JsonObject json){
        if(busy||session==null)throw new IllegalStateException("Reopen server configuration before saving");
        var frames=JEDBuilderWire.split(session,sequence+1,json.toString());sequence++;busy=true;began=System.nanoTime();
        for(var frame:frames)PacketDistributor.sendToServer(new JEDConfigWire.Request(frame));
    }
    private static void receive(JEDBuilderWire.Frame frame){
        if(screen==null)return;
        try{
            if(frame.session().equals(JEDBuilderWire.OPEN)&&frame.request()==0&&nonce!=null){busy=false;nonce=null;screen.failure(text(JEDBuilderWire.json(frame.text()),"detail"));return;}
            if(nonce!=null&&frame.request()==0){
                if(frame.index()==0)IN.clear();String text=IN.accept(frame,System.nanoTime());if(text==null)return;
                var json=JEDBuilderWire.json(text);if(!json.has("nonce")||!nonce.toString().equals(json.get("nonce").getAsString()))return;
                nonce=null;session=frame.session();sequence=0;busy=false;screen.receive(json);return;
            }
            if(!Objects.equals(session,frame.session())||frame.request()!=sequence)return;
            String text=IN.accept(frame,System.nanoTime());if(text==null)return;busy=false;var json=JEDBuilderWire.json(text);
            if(json.get("kind").getAsString().equals("error"))screen.failure(text(json,"detail"));else screen.receive(json);
        }catch(Exception error){busy=false;IN.clear();screen.failure(JEDText.error(error).getString());}
    }
    static void timeout(){if(busy&&System.nanoTime()-began>15_000_000_000L){closeSession();if(screen!=null)screen.failure(JEDText.tr("config.timeout").getString());}}
    static void close(){closeSession();screen=null;}
    private static void closeSession(){
        if(session!=null&&remote()&&!busy){var json=new JsonObject();json.addProperty("op","close");for(var f:JEDBuilderWire.split(session,sequence+1,json.toString()))PacketDistributor.sendToServer(new JEDConfigWire.Request(f));}
        nonce=null;session=null;busy=false;sequence=0;IN.clear();
    }
    static String text(JsonObject json,String field){return JEDText.read(json,field,Minecraft.getInstance().getConnection().registryAccess()).getString();}
    private static ModConfig clientConfig(){return ModConfigs.getModConfigs(JustEpicDimensions.ID).stream().filter(c->c.getSpec()==JEDLightingConfig.SPEC).findFirst().orElseThrow();}
    static JsonObject local(boolean client){
        var path=client?clientConfig().getFullPath():JEDConfigService.global();var json=JEDConfigFiles.read(path,client).json();
        json.add("active",client?JEDLightingConfig.runtimeValues():JsonNull.INSTANCE);return json;
    }
    static JsonObject saveLocal(boolean client,String stamp,JsonObject values,boolean apply){
        var path=client?clientConfig().getFullPath():JEDConfigService.global();
        var priorHold=savedOnlyClientValues;var priorRuntime=client?JEDLightingConfig.runtimeValues():null;
        if(client){JEDLightingConfig.holdRuntime(priorRuntime);savedOnlyClientValues=values.deepCopy();}
        JEDConfigFiles.Saved saved;
        try{saved=JEDConfigFiles.save(path,stamp,values,client);}
        catch(RuntimeException error){if(client){savedOnlyClientValues=priorHold;if(priorHold==null)JEDLightingConfig.releaseRuntime();else JEDLightingConfig.holdRuntime(priorRuntime);}throw error;}
        if(client&&apply){
            var config=clientConfig();var loaded=config.getLoadedConfig();
            for(var field:JEDConfigFields.CLIENT)loaded.config().set(field.key(),field.validate(values.get(field.key())));
            JEDLightingConfig.SPEC.afterReload();savedOnlyClientValues=null;JEDLightingConfig.releaseRuntime();JEDClientLightRuntime.configChanged();JEDClientRenderRuntime.colorOptionChanged();
        }
        var json=local(client);json.addProperty("message_key",apply?(client?"applied":"saved_no_world"):"saved");
        if(saved.backup()!=null)json.addProperty("backup",saved.backup().toString());return json;
    }
    /** Called on the client thread after NeoForge has refreshed its config caches. */
    static void configReload(){
        if(savedOnlyClientValues!=null){
            try{if(!JEDConfigFiles.read(clientConfig().getFullPath(),true).values().equals(savedOnlyClientValues)){savedOnlyClientValues=null;JEDLightingConfig.releaseRuntime();}}
            catch(Exception error){com.mojang.logging.LogUtils.getLogger().warn("JED could not compare pending client configuration",error);}
        }
        JEDClientLightRuntime.configChanged();JEDClientRenderRuntime.colorOptionChanged();
    }
    @SubscribeEvent public static void command(RegisterClientCommandsEvent event){event.getDispatcher().register(Commands.literal("jed").then(Commands.literal("config").executes(c->{open();return 1;})));}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){nonce=null;session=null;sequence=0;busy=false;screen=null;IN.clear();}
    @EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
            ModList.get().getModContainerById(JustEpicDimensions.ID).orElseThrow().registerExtensionPoint(IConfigScreenFactory.class,(container,parent)->create(parent));
            JEDConfigWire.receiver(JEDConfigClient::receive);
        });}
    }
    private JEDConfigClient(){}
}
