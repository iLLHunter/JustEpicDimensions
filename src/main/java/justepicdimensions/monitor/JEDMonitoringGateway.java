package justepicdimensions.monitor;
import com.google.gson.*;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.definition.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** An independent operator session survives closing the editor, but never logout or revocation. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDMonitoringGateway {
 public static final UUID OPEN=new UUID(0,14);
 public static final String PREFIX="JEDMON1:";
 public static final int MAX_DIMENSION_ID_LENGTH=256;
 private static final Map<MinecraftServer,Map<UUID,Session>> OWNERS=new IdentityHashMap<>();
 private static final class Session{final UUID id=UUID.randomUUID();final JEDBuilderWire.Assembly assembly=new JEDBuilderWire.Assembly();final JEDBuilderWire.RateLimit rate;Session(JEDBuilderWire.RateLimit rate){this.rate=rate;}int sequence;boolean busy;}
 private static void send(ServerPlayer p,UUID id,int request,JsonObject j){for(var f:JEDBuilderWire.split(id,request,j.toString()))PacketDistributor.sendToPlayer(p,new JEDBuilderWire.Response(f));}
 private static void reject(ServerPlayer p,UUID id,int request,JsonObject j){if(justepicdimensions.network.JEDRejectionReplies.allow(p))send(p,id,request,j);}
 private static JsonObject error(String message,boolean denied){var j=new JsonObject();j.addProperty("kind","monitoring");j.addProperty("error",message);j.addProperty("denied",denied);return j;}
 public static boolean receive(ServerPlayer p,JEDBuilderWire.Frame f){var sessions=OWNERS.computeIfAbsent(p.server,k->new HashMap<>());var current=sessions.get(p.getUUID());boolean opening=f.session().equals(OPEN);if(!opening&&(current==null||!current.id.equals(f.session()))){if(f.count()==1&&f.text().startsWith(PREFIX)){reject(p,f.session(),f.request(),error("Monitoring session expired; reopen the dashboard",true));return true;}return false;}
  if(!p.hasPermissions(2)||p.hasDisconnected()){sessions.remove(p.getUUID());reject(p,f.session(),f.request(),error("Monitoring requires current operator level 2",true));return true;}
  if(opening){if(f.request()!=0||f.index()!=0||f.count()!=1||!f.text().equals("open"))return true;var rate=current==null?new JEDBuilderWire.RateLimit():current.rate;if(current!=null&&current.busy||!rate.allow(f,System.nanoTime(),4))return true;var s=new Session(rate);sessions.put(p.getUUID(),s);var j=new JsonObject();j.addProperty("kind","monitoring");j.addProperty("session",s.id.toString());send(p,OPEN,0,j);return true;}
  var s=current;if(s.busy){send(p,s.id,f.request(),error("Wait for the current monitoring request",false));return true;}try{if(f.text().length()>4096||f.count()!=1||f.index()!=0||!s.rate.allow(f,System.nanoTime(),4)||f.request()<=s.sequence)throw new JEDDefinitionException("Monitoring request rejected: size, rate or sequence");s.sequence=f.request();if(s.busy)throw new JEDDefinitionException("Wait for the current monitoring request");if(!f.text().startsWith(PREFIX))throw new JEDDefinitionException("Invalid monitoring message");String text=f.text().substring(PREFIX.length());var j=JEDBuilderWire.json(text);JEDJson.keys(j,Set.of("action","page","page_size","dimension","metric","seconds"),"monitoring request");if(j.has("dimension")&&JEDJson.string(j.get("dimension"),"dimension").length()>MAX_DIMENSION_ID_LENGTH)throw new JEDDefinitionException("Dimension ID too long");s.busy=true;
   JEDMonitoringController.view(p.server,j).whenCompleteAsync((reply,failure)->{s.busy=false;if(sessions.get(p.getUUID())!=s||p.hasDisconnected())return;if(!p.hasPermissions(2)){sessions.remove(p.getUUID());send(p,s.id,f.request(),error("Operator permission changed",true));return;}if(failure!=null)send(p,s.id,f.request(),error(String.valueOf(failure.getMessage()),false));else send(p,s.id,f.request(),reply);},p.server);
  }catch(Exception failure){s.busy=false;send(p,s.id,f.request(),error(String.valueOf(failure.getMessage()),false));}return true;
 }
 @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p){var map=OWNERS.get(p.server);if(map!=null)map.remove(p.getUUID());}}
 @SubscribeEvent public static void stopped(ServerStoppedEvent e){OWNERS.remove(e.getServer());}
 private JEDMonitoringGateway(){}
}
