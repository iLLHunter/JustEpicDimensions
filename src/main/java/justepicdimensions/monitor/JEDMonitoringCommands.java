package justepicdimensions.monitor;
import com.google.gson.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.*;
import justepicdimensions.JEDText;
import justepicdimensions.admin.JEDAdminSnapshot;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Chat and console view of monitoring. The full JSON stays in /jed debug dump and the dashboard. */
public final class JEDMonitoringCommands {
 public static LiteralArgumentBuilder<CommandSourceStack> tree(){var root=Commands.literal("monitor").requires(s->s.hasPermission(2)).executes(c->run(c.getSource(),null,false,1));root.then(Commands.literal("page").then(Commands.argument("page",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(c->run(c.getSource(),null,false,com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"page")))));root.then(Commands.argument("dimension",ResourceLocationArgument.id()).suggests((c,b)->{JEDAdminSnapshot.ids(c.getSource().getServer()).forEach(id->b.suggest(id.toString()));return b.buildFuture();}).executes(c->run(c.getSource(),ResourceLocationArgument.getId(c,"dimension"),false,1)).then(Commands.literal("refresh-disk").executes(c->run(c.getSource(),ResourceLocationArgument.getId(c,"dimension"),true,1))));return root;}
 private static int run(CommandSourceStack source,ResourceLocation id,boolean disk,int page){
  if(!source.hasPermission(2))return 0;
  // Current values only: without a history query the reply is immediate, so console and RCON receive it as well.
  var request=new JsonObject();request.addProperty("action",disk?"disk":"status");request.addProperty("no_history",true);request.addProperty("page_size",32);request.addProperty("page",page);if(id!=null)request.addProperty("dimension",id.toString());
  try{
   var future=JEDMonitoringController.view(source.getServer(),request);
   if(future.isDone()){show(source,future.join(),id,disk);return 1;}
   future.whenCompleteAsync((reply,error)->{if(source.getEntity() instanceof ServerPlayer p&&(p.hasDisconnected()||!p.hasPermissions(2)))return;if(error!=null)source.sendFailure(Component.literal("JED monitoring: "+JEDText.error(error).getString()));else show(source,reply,id,disk);},source.getServer());return 1;
  }catch(Exception error){source.sendFailure(Component.literal("JED monitoring: "+error.getMessage()));return 0;}
 }
 private static void show(CommandSourceStack source,JsonObject reply,ResourceLocation id,boolean disk){
  if(id==null){
   source.sendSuccess(()->JEDText.tr("monitor.header",reply.get("page").getAsInt(),reply.get("pages").getAsInt(),reply.get("total").getAsInt()),false);
   for(var raw:reply.getAsJsonArray("dimensions")){var row=raw.getAsJsonObject();source.sendSuccess(()->overview(row),false);}
   return;
  }
  var row=reply.getAsJsonObject("selected");
  if(disk)source.sendSuccess(()->JEDText.tr("monitor.disk_requested"),false);
  for(var line:detail(row))source.sendSuccess(()->line,false);
 }
 static Component overview(JsonObject r){
  var line=Component.empty();String id=text(r,"id");
  if(flag(r,"loaded"))line.append(JEDText.tr("monitor.row",id,integer(r,"players"),integer(r,"loaded_chunks"),ms(r,"tick_ms_mean"),ms(r,"tick_ms_p95"),bytes(r,"disk_total_bytes")));
  else line.append(JEDText.tr("monitor.row_unloaded",id,bytes(r,"disk_total_bytes")));
  if(!flag(r,"recording"))line.append(" · ").append(JEDText.tr("monitor.row_history_off"));
  var alerts=alerts(r);if(alerts!=null)line.append(" · ").append(JEDText.tr("monitor.row_alerts",alerts));
  return line;
 }
 static List<Component> detail(JsonObject r){
  var lines=new ArrayList<Component>();
  String name=r.has("display_name")?text(r,"display_name"):text(r,"id");
  lines.add(JEDText.tr("monitor.detail_header",text(r,"id"),name,JEDText.tr(flag(r,"loaded")?"monitor.state_loaded":"monitor.state_unloaded")));
  lines.add(flag(r,"recording")?JEDText.tr("monitor.history",text(r,"history_mode"),integer(r,"sample_seconds"),duration(r.has("retention_seconds")?r.get("retention_seconds").getAsLong():0)):JEDText.tr("monitor.history_off"));
  if(flag(r,"loaded")){
   lines.add(JEDText.tr("monitor.now",integer(r,"players"),integer(r,"loaded_chunks"),integer(r,"loaded_entities")));
   lines.add(r.has("tick_samples")?JEDText.tr("monitor.tick",ms(r,"tick_ms_mean"),ms(r,"tick_ms_p95"),ms(r,"tick_ms_max"),integer(r,"tick_samples")):JEDText.tr("monitor.tick_none"));
  }
  if(r.has("disk_error")&&!r.get("disk_error").isJsonNull())lines.add(JEDText.tr("monitor.disk_error",text(r,"disk_error")));
  else if(flag(r,"disk_scanning"))lines.add(JEDText.tr("monitor.disk_scanning"));
  else if(!r.has("disk_measured_at"))lines.add(JEDText.tr("monitor.disk_none"));
  else lines.add(JEDText.tr("monitor.disk",bytes(r,"disk_live_bytes"),bytes(r,"disk_history_bytes"),bytes(r,"disk_total_bytes"),duration(Math.max(0,(System.currentTimeMillis()-r.get("disk_measured_at").getAsLong())/1000))));
  var blockers=new ArrayList<String>();if(r.has("unload_blockers")&&r.get("unload_blockers").isJsonArray())for(var b:r.getAsJsonArray("unload_blockers"))blockers.add(b.isJsonPrimitive()?b.getAsString():b.toString());
  lines.add(JEDText.tr("monitor.blockers",blockers.isEmpty()?JEDText.tr("monitor.none"):Component.literal(String.join(", ",blockers))));
  var alerts=alerts(r);lines.add(JEDText.tr("monitor.alerts",alerts==null?JEDText.tr("monitor.none"):alerts));
  return lines;
 }
 /** Active alert names, each with the reason its action was blocked; null when none are active. */
 private static Component alerts(JsonObject r){
  if(!r.has("alerts")||!r.get("alerts").isJsonArray()||r.getAsJsonArray("alerts").isEmpty())return null;
  var out=Component.empty();boolean first=true;
  for(var raw:r.getAsJsonArray("alerts")){var a=raw.getAsJsonObject();if(!first)out.append(", ");first=false;String blocked=a.has("blocked")?a.get("blocked").getAsString():"";out.append(blocked.isEmpty()?Component.literal(a.get("id").getAsString()):JEDText.tr("monitor.alert_blocked",a.get("id").getAsString(),blocked));}
  return out;
 }
 private static boolean flag(JsonObject r,String key){return r.has(key)&&r.get(key).isJsonPrimitive()&&r.get(key).getAsBoolean();}
 private static String text(JsonObject r,String key){return r.has(key)&&!r.get(key).isJsonNull()?r.get(key).getAsString():"?";}
 private static String integer(JsonObject r,String key){return r.has(key)&&r.get(key).isJsonPrimitive()?Long.toString(r.get(key).getAsLong()):"?";}
 private static String ms(JsonObject r,String key){return r.has(key)&&r.get(key).isJsonPrimitive()?String.format(Locale.ROOT,r.get(key).getAsDouble()<10?"%.2f":"%.1f",r.get(key).getAsDouble()):"–";}
 static String bytes(JsonObject r,String key){if(!r.has(key)||!r.get(key).isJsonPrimitive())return "–";double v=r.get(key).getAsDouble();String[] units={"B","KB","MB","GB","TB"};int u=0;while(v>=1024&&u<units.length-1){v/=1024;u++;}return u==0?String.format(Locale.ROOT,"%.0f %s",v,units[u]):String.format(Locale.ROOT,"%.1f %s",v,units[u]);}
 static String duration(long seconds){if(seconds>=86400)return amount(seconds/86400.0)+" d";if(seconds>=3600)return amount(seconds/3600.0)+" h";if(seconds>=60)return (seconds/60)+" min";return seconds+" s";}
 private static String amount(double value){double rounded=Math.round(value*10)/10.0;return rounded==Math.rint(rounded)?Long.toString((long)rounded):Double.toString(rounded);}
 private JEDMonitoringCommands(){}
}
