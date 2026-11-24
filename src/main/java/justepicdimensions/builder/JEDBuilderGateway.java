package justepicdimensions.builder;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.definition.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Authenticated, private GUI adapter. All handlers execute on the normal server thread. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDBuilderGateway {
    public static final int MAX_REQUEST_BYTES=8*1024*1024, BACKGROUND_WIRE_CHARS=256*1024;
    private static final Map<MinecraftServer,Map<UUID,Session>> SERVERS=new IdentityHashMap<>();
    private static final class Session {
        final UUID id=UUID.randomUUID();final JEDBuilderWire.Assembly assembly=new JEDBuilderWire.Assembly(MAX_REQUEST_BYTES);
        final JEDBuilderWire.RateLimit rate=new JEDBuilderWire.RateLimit();
        int sequence=-1;boolean busy;long lastOpen;String name="";
    }
    @SubscribeEvent public static void command(RegisterCommandsEvent event){event.getDispatcher().register(Commands.literal("jed").requires(s->s.hasPermission(2)).then(Commands.literal("gui").executes(c->{var p=c.getSource().getPlayer();if(p==null){c.getSource().sendFailure(JEDText.tr("builder.in_game"));return 0;}open(p);return 1;})));}
    private static Map<UUID,Session> sessions(MinecraftServer server){return SERVERS.computeIfAbsent(server,s->new HashMap<>());}
    public static void open(ServerPlayer p){
        if(!p.hasPermissions(2))return;var map=sessions(p.server);var old=map.get(p.getUUID());long now=System.nanoTime();if(old!=null&&now-old.lastOpen<250_000_000L)return;
        var session=new Session();session.lastOpen=now;map.put(p.getUUID(),session);send(p,session.id,0,snapshot(p,session,"",null));
    }
    public static void receive(JEDBuilderWire.Frame frame,IPayloadContext context){if(context.player() instanceof ServerPlayer p)receive(p,frame);}
    public static void receive(ServerPlayer p,JEDBuilderWire.Frame frame){
        if(!p.server.isSameThread())throw new IllegalStateException("Builder GUI requires the server thread");
        if(justepicdimensions.teams.JEDTeamGateway.receive(p,frame))return;if(justepicdimensions.monitor.JEDMonitoringGateway.receive(p,frame))return;
        if(!p.hasPermissions(2)){sessions(p.server).remove(p.getUUID());reject(p,frame.session(),frame.request(),"Builder requires operator level 2");return;}
        if(frame.session().equals(JEDBuilderWire.OPEN)){
            if(frame.request()==0&&frame.index()==0&&frame.count()==1&&frame.text().equals("open"))open(p);return;
        }
        var session=sessions(p.server).get(p.getUUID());
        if(session==null||!session.id.equals(frame.session())){reject(p,frame.session(),frame.request(),"Editor session expired. Reopen /jed gui; local edits have not been saved.");return;}
        long now=System.nanoTime();if(!session.rate.allow(frame,now,128)){session.assembly.clear();if(session.rate.report())error(p,session.id,frame.request(),"Too many builder messages; wait and retry");return;}
        try{
            if(frame.request()<=session.sequence)throw new JEDDefinitionException("Repeated builder request rejected");
            if(session.busy)throw new JEDDefinitionException("Wait for the current editor operation");
            String text=session.assembly.acceptWire(frame,System.nanoTime());if(text==null)return;
            session.sequence=frame.request();
            if(text.length()>BACKGROUND_WIRE_CHARS){
                session.busy=true;
                try{java.util.concurrent.CompletableFuture.supplyAsync(()->JEDBuilderWire.json(JEDBuilderWire.decode(text,MAX_REQUEST_BYTES)),net.minecraft.Util.backgroundExecutor()).whenCompleteAsync((json,failure)->{
                    session.busy=false;
                    if(sessions(p.server).get(p.getUUID())!=session||p.hasDisconnected())return;
                    if(!p.hasPermissions(2)){sessions(p.server).remove(p.getUUID());reject(p,session.id,frame.request(),"Operator permission changed");return;}
                    if(failure!=null)error(p,session.id,frame.request(),JEDBuilderService.root(failure));else dispatch(p,session,frame,json);
                },p.server);}catch(RuntimeException error){session.busy=false;throw error;}return;
            }
            dispatch(p,session,frame,JEDBuilderWire.json(JEDBuilderWire.decode(text,MAX_REQUEST_BYTES)));
        }catch(Exception e){session.assembly.clear();error(p,session.id,frame.request(),e);}
    }
    /** Only parsed JSON crosses back from the worker; all permissions, world and session work stays here. */
    private static void dispatch(ServerPlayer p,Session session,JEDBuilderWire.Frame frame,JsonObject json){
        try{
            JEDJson.keys(json,Set.of("op","name","stamp","documents","source","mode","target","registry","query","offset","bundle_action","file","dimensions","extras","token","replace","pregen_action","instance_action","group_uuid","player_uuid","run","worldgen_action","resource","seed","x","y","z","span","radius"),"builder request");
            String op=string(json,"op");
            if(op.equals("close")){sessions(p.server).remove(p.getUUID());return;}
            if(op.equals("registry")){
                var future=JEDBuilderOptions.request(p.server,string(json,"registry"),string(json,"query"),JEDJson.integer(json.get("offset"),"offset"),json.has("documents")?JEDJson.object(json.get("documents"),"draft choices"):new JsonObject());session.busy=true;
                future.whenComplete((reply,failure)->p.server.execute(()->{session.busy=false;if(sessions(p.server).get(p.getUUID())!=session||p.hasDisconnected())return;if(!p.hasPermissions(2)){error(p,session.id,frame.request(),"Operator permission changed");return;}if(failure!=null)error(p,session.id,frame.request(),JEDBuilderService.root(failure));else send(p,session.id,frame.request(),reply);}));return;
            }
            if(op.equals("instances")){session.busy=true;justepicdimensions.instance.JEDInstanceController.control(p,json).whenCompleteAsync((reply,failure)->{session.busy=false;if(sessions(p.server).get(p.getUUID())!=session||p.hasDisconnected())return;if(failure!=null)error(p,session.id,frame.request(),failure);else send(p,session.id,frame.request(),reply);},p.server);return;}
            if(op.equals("worldgen")){session.busy=true;try{JEDWorldgenEditor.request(p.server,json).whenCompleteAsync((reply,failure)->{session.busy=false;if(sessions(p.server).get(p.getUUID())!=session||p.hasDisconnected())return;if(!p.hasPermissions(2)){error(p,session.id,frame.request(),"Operator permission changed");return;}if(failure!=null)error(p,session.id,frame.request(),failure);else send(p,session.id,frame.request(),reply);},p.server);}catch(Exception failure){session.busy=false;throw failure;}return;}
            if(op.equals("pregen")){var dimension=ResourceLocation.parse(string(json,"target"));String action=string(json,"pregen_action");var reply=action.equals("start")&&json.has("radius")?justepicdimensions.pregen.JEDPregenerationController.startManual(p.server,dimension,JEDJson.integer(json.get("radius"),"radius")):justepicdimensions.pregen.JEDPregenerationController.control(p.server,dimension,action);reply.addProperty("kind","pregen");send(p,session.id,frame.request(),reply);return;}
            if(op.equals("bundle")){
                session.busy=true;java.util.concurrent.CompletableFuture<JsonObject> operation;
                try{operation=switch(string(json,"bundle_action")){case "dependencies"->justepicdimensions.bundle.JEDBundleService.dependencies(p.createCommandSourceStack(),justepicdimensions.bundle.JEDBundleCommands.dimensions(string(json,"dimensions")),string(json,"mode"),justepicdimensions.bundle.JEDBundleCommands.extras(string(json,"extras")));case "catalog"->justepicdimensions.bundle.JEDBundleService.catalog(p.createCommandSourceStack());case "export"->justepicdimensions.bundle.JEDBundleService.export(p.createCommandSourceStack(),string(json,"file"),justepicdimensions.bundle.JEDBundleCommands.dimensions(string(json,"dimensions")),string(json,"mode"),justepicdimensions.bundle.JEDBundleCommands.extras(string(json,"extras")));case "preview"->justepicdimensions.bundle.JEDBundleService.preview(p.createCommandSourceStack(),string(json,"file"));case "import"->justepicdimensions.bundle.JEDBundleService.install(p.createCommandSourceStack(),string(json,"file"),string(json,"token"),json.has("replace")&&json.get("replace").getAsBoolean());default->throw new JEDDefinitionException("Unknown bundle action");};}catch(Exception failure){session.busy=false;throw failure;}
                operation.whenComplete((reply,failure)->{session.busy=false;if(sessions(p.server).get(p.getUUID())!=session||p.hasDisconnected())return;if(failure!=null)error(p,session.id,frame.request(),failure);else{reply.addProperty("kind","bundle");send(p,session.id,frame.request(),reply);}});return;
            }
            if(op.equals("list")){send(p,session.id,frame.request(),snapshot(p,session,"",null));return;}
            String name=string(json,"name");
            if(op.equals("new")){idle(p);JEDBuilderStore.create(p.createCommandSourceStack(),name);session.name=name;}
            else if(op.equals("open")){JEDBuilderStore.open(p.createCommandSourceStack(),name);session.name=name;}
            else {
                var opened=JEDBuilderStore.open(p.createCommandSourceStack(),name);
                if(!opened.stamp().equals(string(json,"stamp")))throw new JEDDefinitionException("Draft changed outside this editor. Keep your local edits or reopen the draft to read the newer version.");
                if(op.equals("terrain")){var id=ResourceLocation.parse(string(json,"source"));var reply=new JsonObject();reply.addProperty("kind","terrain");reply.addProperty("source",id.toString());reply.add("dimension",JEDBuilderService.terrain(p.server,id));send(p,session.id,frame.request(),reply);return;}
                if(op.equals("update")){update(p.createCommandSourceStack(),name,string(json,"stamp"),JEDJson.object(json.get("documents"),"documents"),json.has("target")?string(json,"target"):opened.draft().target().toString());}
                else if(op.equals("undo")){idle(p);opened.draft().undo();opened.save();}
                else if(op.equals("copy")){idle(p);String mode=string(json,"mode");if(!Set.of("independent","inherit").contains(mode))throw new JEDDefinitionException("Unknown copy mode");JEDBuilderService.copy(p.createCommandSourceStack(),name,ResourceLocation.parse(string(json,"source")),mode);}
                else {
                    var action=JEDBuilderService.Action.valueOf(op.toUpperCase(Locale.ROOT));session.busy=true;int request=frame.request();
                    java.util.concurrent.CompletableFuture<JEDBuilderService.Outcome> future;try{future=JEDBuilderService.run(p.createCommandSourceStack(),name,ResourceLocation.parse(string(json,"target")),action);}catch(Exception e){session.busy=false;throw e;}
                    future.whenComplete((out,failure)->p.server.execute(()->{
                        session.busy=false;if(sessions(p.server).get(p.getUUID())!=session||p.hasDisconnected())return;
                        if(!p.hasPermissions(2)){sessions(p.server).remove(p.getUUID());error(p,session.id,request,"Operator permission changed");return;}
                        try{if(failure!=null)throw new JEDDefinitionException(JEDBuilderService.root(failure).getMessage(),null,JEDText.error(failure));send(p,session.id,request,snapshot(p,session,out.message(),action==JEDBuilderService.Action.PREVIEW?out.preview():null,out.display()));}catch(Exception e){error(p,session.id,request,e);}
                    }));return;
                }
                session.name=name;
            }
            send(p,session.id,frame.request(),snapshot(p,session,op.equals("update")?"Draft updated. No live dimension changed.":"",null));
        }catch(Exception e){session.assembly.clear();error(p,session.id,frame.request(),e);}
    }
    private static void idle(ServerPlayer p){if(JEDBuilderService.busy(p.server))throw new JEDDefinitionException("Wait for the current builder operation");}
    public static void update(net.minecraft.commands.CommandSourceStack source,String name,String stamp,JsonObject documents){
        if(!source.getServer().isSameThread())throw new IllegalStateException("Builder update requires the server thread");if(!source.hasPermission(2))throw new JEDDefinitionException("Builder requires operator level 2");
        update(source,name,stamp,documents,JEDBuilderStore.open(source,name).draft().target().toString());
    }
    public static void update(net.minecraft.commands.CommandSourceStack source,String name,String stamp,JsonObject documents,String target){
        if(!source.getServer().isSameThread())throw new IllegalStateException("Builder update requires the server thread");
        if(!source.hasPermission(2))throw new JEDDefinitionException("Builder requires operator level 2");
        if(JEDBuilderService.busy(source.getServer()))throw new JEDDefinitionException("Wait for the current builder operation");
        var open=JEDBuilderStore.open(source,name);if(!open.stamp().equals(stamp))throw new JEDDefinitionException("Draft changed outside this editor; reopen it before updating");open.draft().updateDocuments(documents,JEDBuilderDraft.parseTarget(target));open.save();
    }
    private static String string(JsonObject json,String name){return JEDJson.string(json.get(name),name);}
    private static JsonObject snapshot(ServerPlayer p,Session s,String message,JsonObject review){return snapshot(p,s,message,review,JEDText.fixed(message));}
    private static JsonObject snapshot(ServerPlayer p,Session s,String message,JsonObject review,Component display){
        var json=new JsonObject();json.addProperty("kind","snapshot");json.addProperty("message",message);JEDText.write(json,"message",display,p.registryAccess());var drafts=new JsonArray();JEDBuilderStore.list(p.createCommandSourceStack()).forEach(drafts::add);json.add("drafts",drafts);
        json.addProperty("name",s.name);
        if(!s.name.isEmpty()){var open=JEDBuilderStore.open(p.createCommandSourceStack(),s.name);json.addProperty("stamp",open.stamp());json.addProperty("target",open.draft().target().toString());json.add("documents",open.draft().content().getAsJsonObject("documents"));json.addProperty("mode",open.draft().mode());if(open.draft().source()!=null)json.addProperty("source",open.draft().source().toString());json.addProperty("undo",!open.draft().json().getAsJsonArray("undo").isEmpty());if(review==null)review=open.draft().review();}
        if(review!=null){var safe=review.deepCopy();safe.remove("draft");safe.remove("stamps");json.add("review",safe);}return json;
    }
    private static void send(ServerPlayer p,UUID session,int sequence,JsonObject value){for(var frame:JEDBuilderWire.split(session,sequence,value.toString()))PacketDistributor.sendToPlayer(p,new JEDBuilderWire.Response(frame));}
    private static void reject(ServerPlayer p,UUID session,int sequence,String message){if(justepicdimensions.network.JEDRejectionReplies.allow(p))error(p,session,sequence,message);}
    private static void error(ServerPlayer p,UUID session,int sequence,Throwable error){error(p,session,sequence,error.getMessage(),JEDText.error(error));}
    private static void error(ServerPlayer p,UUID session,int sequence,String message){error(p,session,sequence,message,JEDText.fixed(message==null?"Builder operation failed":message));}
    private static void error(ServerPlayer p,UUID session,int sequence,String message,Component display){var json=new JsonObject();json.addProperty("kind","error");json.addProperty("message",message==null?"Builder operation failed":message);JEDText.write(json,"message",display,p.registryAccess());send(p,session,sequence,json);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p){var map=SERVERS.get(p.server);if(map!=null)map.remove(p.getUUID());}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){SERVERS.remove(e.getServer());}
    private JEDBuilderGateway(){}
}
