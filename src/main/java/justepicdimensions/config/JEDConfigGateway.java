package justepicdimensions.config;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.definition.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDConfigGateway {
    public static final int MAX_REQUEST_BYTES=1024*1024;
    private static final Map<MinecraftServer,Map<UUID,Session>> SERVERS=new IdentityHashMap<>();
    private static final class Session {
        final UUID id=UUID.randomUUID();final JEDBuilderWire.Assembly assembly=new JEDBuilderWire.Assembly(MAX_REQUEST_BYTES);
        final JEDBuilderWire.RateLimit rate=new JEDBuilderWire.RateLimit();
        int sequence;long opened=System.nanoTime(),lastOperation;
    }
    private static Map<UUID,Session> sessions(MinecraftServer s){return SERVERS.computeIfAbsent(s,k->new HashMap<>());}
    public static void receive(JEDBuilderWire.Frame frame,IPayloadContext context){if(context.player() instanceof ServerPlayer p)receive(p,frame);}
    public static void receive(ServerPlayer p,JEDBuilderWire.Frame frame){
        if(!p.server.isSameThread())throw new IllegalStateException("Configuration payload requires the server thread");
        if(!p.hasPermissions(2)){sessions(p.server).remove(p.getUUID());reject(p,frame.session(),frame.request(),"Operator level 2 is required to edit server configuration");return;}
        var sessions=sessions(p.server);long now=System.nanoTime();
        try{
            if(frame.session().equals(JEDBuilderWire.OPEN)){
                if(frame.request()!=0||frame.count()!=1||frame.index()!=0)throw new JEDDefinitionException("Invalid configuration open message");
                UUID nonce=UUID.fromString(frame.text());var old=sessions.get(p.getUUID());
                if(old!=null&&now-old.opened<500_000_000L)throw new JEDDefinitionException("Wait before reopening configuration");
                var s=new Session();sessions.put(p.getUUID(),s);var json=JEDConfigService.snapshot(p.server);json.addProperty("nonce",nonce.toString());send(p,s.id,0,json);return;
            }
            var s=sessions.get(p.getUUID());
            if(s==null||!s.id.equals(frame.session())){reject(p,frame.session(),frame.request(),"Configuration session expired. Reopen /jed config; local edits have not been saved.");return;}
            if(!s.rate.allow(frame,now,64)){s.assembly.clear();if(s.rate.report())error(p,s.id,frame.request(),"Too many configuration messages; wait and retry");return;}
            if(frame.request()<=s.sequence)throw new JEDDefinitionException("Repeated configuration request rejected");
            String text=s.assembly.accept(frame,now);if(text==null)return;s.sequence=frame.request();
            var json=JEDBuilderWire.json(text);JEDJson.keys(json,Set.of("op","scope","stamp","values"),"configuration request");
            String op=JEDJson.string(json.get("op"),"op");
            if(op.equals("close")){sessions.remove(p.getUUID());return;}
            if(now-s.lastOperation<250_000_000L)throw new JEDDefinitionException("Wait briefly before the next configuration operation");s.lastOperation=now;
            JsonObject result;
            if(op.equals("refresh"))result=JEDConfigService.snapshot(p.server);
            else if(op.equals("save")||op.equals("apply")){
                result=JEDConfigService.save(p.server,JEDJson.string(json.get("scope"),"scope"),JEDJson.string(json.get("stamp"),"stamp"),JEDJson.object(json.get("values"),"values"),op.equals("apply"));
                com.mojang.logging.LogUtils.getLogger().info("JED configuration {} by {}: {}",op,p.getGameProfile().getName(),JEDConfigService.file(p.server,json.get("scope").getAsString()));
            }else throw new JEDDefinitionException("Unknown configuration operation");
            send(p,s.id,frame.request(),result);
        }catch(Exception error){var s=sessions.get(p.getUUID());if(s!=null)s.assembly.clear();error(p,frame.session(),frame.request(),error);}
    }
    private static void send(ServerPlayer p,UUID session,int request,JsonObject json){for(var f:JEDBuilderWire.split(session,request,json.toString()))PacketDistributor.sendToPlayer(p,new JEDConfigWire.Response(f));}
    private static void reject(ServerPlayer p,UUID session,int request,String message){if(justepicdimensions.network.JEDRejectionReplies.allow(p))error(p,session,request,message);}
    private static void error(ServerPlayer p,UUID session,int request,Throwable error){error(p,session,request,error.getMessage(),JEDText.error(error));}
    private static void error(ServerPlayer p,UUID session,int request,String message){error(p,session,request,message,JEDText.fixed(message==null?"Configuration operation failed":message));}
    private static void error(ServerPlayer p,UUID session,int request,String message,net.minecraft.network.chat.Component display){var json=new JsonObject();json.addProperty("kind","error");json.addProperty("detail",message==null?"Configuration operation failed":message);JEDText.write(json,"detail",display,p.registryAccess());send(p,session,request,json);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p){var map=SERVERS.get(p.server);if(map!=null)map.remove(p.getUUID());}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){SERVERS.remove(e.getServer());}
    private JEDConfigGateway(){}
}
