package justepicdimensions.client;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.monitor.*;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDMonitoringClient {
 private record Pending(JsonObject request,CompletableFuture<JsonObject> reply){}
 private static final ArrayDeque<Pending> QUEUE=new ArrayDeque<>();private static final JEDBuilderWire.Assembly IN=new JEDBuilderWire.Assembly();private static UUID session;private static int sequence;private static long deadline;private static boolean opening;private static Pending active;private static JEDLocalDashboard browser;
 public static CompletableFuture<JsonObject> request(JsonObject request){var result=new CompletableFuture<JsonObject>();var detached=request.deepCopy();Minecraft.getInstance().execute(()->{var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.player==null||!mc.player.hasPermissions(2)){result.completeExceptionally(new IllegalStateException("Connect as an operator to use monitoring"));return;}if(QUEUE.size()>=8){result.completeExceptionally(new IllegalStateException("Too many dashboard requests"));return;}QUEUE.addLast(new Pending(detached,result));pump();});return result;}
 private static void pump(){if(active!=null||opening||QUEUE.isEmpty())return;if(session==null){opening=true;deadline=System.currentTimeMillis()+15000;PacketDistributor.sendToServer(new JEDBuilderWire.Request(new JEDBuilderWire.Frame(JEDMonitoringGateway.OPEN,0,0,1,"open")));return;}active=QUEUE.removeFirst();sequence++;deadline=System.currentTimeMillis()+15000;PacketDistributor.sendToServer(new JEDBuilderWire.Request(new JEDBuilderWire.Frame(session,sequence,0,1,JEDMonitoringGateway.PREFIX+active.request())));}
 static boolean receive(JEDBuilderWire.Frame f){if(!f.session().equals(JEDMonitoringGateway.OPEN)&&!Objects.equals(f.session(),session))return false;try{if(f.session().equals(JEDMonitoringGateway.OPEN)){if(!opening)return true;var j=JEDBuilderWire.json(f.text());if(j.has("error"))throw new IllegalStateException(j.get("error").getAsString());session=UUID.fromString(j.get("session").getAsString());sequence=0;opening=false;pump();return true;}if(active==null||f.request()!=sequence)return true;String text=IN.accept(f,System.nanoTime());if(text==null)return true;var j=JEDBuilderWire.json(text);var completed=active;active=null;if(j.has("denied")&&j.get("denied").getAsBoolean()){completed.reply().completeExceptionally(new IllegalStateException(j.get("error").getAsString()));clear("Operator session ended");return true;}if(j.has("error"))completed.reply().completeExceptionally(new IllegalStateException(j.get("error").getAsString()));else completed.reply().complete(j);pump();}catch(Exception error){clear(String.valueOf(error.getMessage()));}return true;}
 public static java.net.URI browserURI()throws Exception{var mc=Minecraft.getInstance();if(mc.player==null||!mc.player.hasPermissions(2))throw new IllegalStateException("Operator permission required");if(browser==null)browser=new JEDLocalDashboard(JEDMonitoringClient::request);return browser.uri();}
 public static String openBrowser()throws Exception{var uri=browserURI();Util.getPlatform().openUri(uri);return uri.toString();}
 public static void clear(String message){opening=false;session=null;IN.clear();if(active!=null)active.reply().completeExceptionally(new IllegalStateException(message));active=null;while(!QUEUE.isEmpty())QUEUE.removeFirst().reply().completeExceptionally(new IllegalStateException(message));if(browser!=null){browser.close();browser=null;}}
 @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if((session!=null||opening||browser!=null)&&(mc.getConnection()==null||mc.player==null||!mc.player.hasPermissions(2))){clear("Operator connection ended");return;}if((active!=null||opening)&&System.currentTimeMillis()>deadline)clear("Dashboard request timed out; reconnect the dashboard");}
 @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){clear("Disconnected");}
 private JEDMonitoringClient(){}
}
