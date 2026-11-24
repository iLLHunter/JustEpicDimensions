package justepicdimensions.client;
import com.google.gson.*;
import java.util.*;
import justepicdimensions.monitor.JEDMonitoringPolicy;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Read-only overview and history, with explicit manual scan and local-browser controls. */
final class JEDMonitoringScreen extends JEDBuilderScreen.Sub {
 String dimension="",error="",metric="tick_ms_mean";int page=1,range=3600,ticks,scroll;boolean requested;JsonObject result=new JsonObject();
 static Component label(String key,String fallback){return Component.translatableWithFallback("justepicdimensions.monitor."+key,fallback);}
 JEDMonitoringScreen(JEDBuilderScreen parent){super(parent,label("title","Dimension monitoring"));}
 protected void init(){int w=Math.max(30,(width-24)/3);var refresh=button("refresh",8,36,w,()->request("status"));refresh.setMessage(label("refresh","Refresh"));var disk=button("disk",12+w,36,w,()->request("disk"));disk.setMessage(label("disk","Refresh disk"));disk.active=!dimension.isEmpty();var browser=button("browser",16+2*w,36,w,()->{try{JEDMonitoringClient.openBrowser();}catch(Exception e){error=e.getMessage();}});browser.setMessage(label("browser","Open dashboard"));
  if(dimension.isEmpty()){button("previous",8,62,70,()->{page=Math.max(1,page-1);request("status");});button("next",82,62,70,()->{if(result.has("pages"))page=Math.min(result.get("pages").getAsInt(),page+1);request("status");});}else{var overview=button("overview",8,62,Math.min(100,w),()->{dimension="";result=new JsonObject();rebuildWidgets();request("status");});overview.setMessage(label("overview","Overview"));var choose=button("metric",12+w,62,w,()->{var metrics=JEDMonitoringPolicy.METRICS.stream().sorted().toList();metric=metrics.get((metrics.indexOf(metric)+1)%metrics.size());request("status");});choose.setMessage(Component.literal(metric));choose.setTooltip(Tooltip.create(Component.literal(metric)));var time=button("range",16+w*2,62,w,()->{range=range==3600?86400:range==86400?604800:3600;request("status");});time.setMessage(Component.literal(range==3600?"1h":range==86400?"24h":"7d"));}
  button("back",8,height-30,width-16,this::onClose);if(result.isEmpty())request("status");
 }
 void request(String action){if(requested)return;var j=new JsonObject();j.addProperty("action",action);j.addProperty("page",page);j.addProperty("dimension",dimension);j.addProperty("metric",metric);j.addProperty("seconds",range);requested=true;JEDMonitoringClient.request(j).whenComplete((reply,failure)->{requested=false;if(failure!=null)error=String.valueOf(failure.getMessage());else{error="";if(!reply.has("points")&&result.has("points"))reply.add("points",result.get("points"));result=reply;page=result.get("page").getAsInt();rebuildWidgets();}});}
 public void tick(){super.tick();if(++ticks%100==0&&minecraft.screen==this)request("status");}
 public void render(GuiGraphics g,int mx,int my,float delta){super.render(g,mx,my,delta);if(!error.isEmpty()){g.drawString(font,font.plainSubstrByWidth(error,width-16),8,87,0xffff7777);return;}if(!result.has("dimensions"))return;
  if(dimension.isEmpty()){overview(g,mx,my);g.drawString(font,Component.literal(page+" / "+result.get("pages").getAsInt()+" · "+result.get("total").getAsInt()),8,height-45,0xffaaaaaa);
  }else if(result.has("selected")){var r=result.getAsJsonObject("selected");int y=88;String[] lines={dimension,(r.get("recording").getAsBoolean()?"Recording":"Recording off")+" · "+r.get("history_mode").getAsString(),"Live bytes: "+value(r,"disk_live_bytes")+" · History bytes: "+value(r,"disk_history_bytes"),"Disk age: "+(r.has("disk_measured_at")?Math.max(0,(System.currentTimeMillis()-r.get("disk_measured_at").getAsLong())/1000)+"s":"not measured")+(r.has("disk_scanning")&&r.get("disk_scanning").getAsBoolean()?" · scanning":""),"Unload: "+r.get("unload_blocker").getAsString()};for(String line:lines){g.drawString(font,font.plainSubstrByWidth(line,width-16),8,y,0xffdddddd);y+=11;}if(r.has("disk_error"))g.drawString(font,font.plainSubstrByWidth(r.get("disk_error").getAsString(),width-16),8,y,0xffff7777);chart(g,result.has("points")?result.getAsJsonArray("points"):new JsonArray(),8,y+14,width-16,Math.max(12,height-y-62));}
  if(result.has("io_error")&&!result.get("io_error").getAsString().isEmpty())g.drawString(font,font.plainSubstrByWidth(result.get("io_error").getAsString(),width-16),8,height-58,0xffff7777);
 }
 static String value(JsonObject r,String key){if(!r.has(key))return "—";double v=r.get(key).getAsDouble();return v==Math.rint(v)?String.format(Locale.ROOT,"%.0f",v):String.format(Locale.ROOT,"%.2f",v);}
 private static final String[] OVERVIEW_METRICS={"loaded_chunks","loaded_entities","tick_ms_mean"};
 private record Columns(int dimensionWidth,int[] widths,int[] right){}
 private Columns columns(String[] headers){
  int[] widths=new int[3],right=new int[3];
  // Fixed metric bounds keep every page aligned; long IDs retain their complete hover text.
  String[] limits={Integer.toString(Integer.MAX_VALUE),Integer.toString(Integer.MAX_VALUE),String.format(Locale.ROOT,"%.2f",Long.MAX_VALUE/1_000_000.0)};
  for(int i=0;i<3;i++)widths[i]=Math.max(font.width(headers[i+1]),font.width(limits[i]));
  int gap=10,edge=width-8-font.width(" !"),available=Math.max(3,edge-8-48-3*gap),total=Arrays.stream(widths).sum();
  if(total>available)for(int i=0;i<3;i++)widths[i]=Math.max(1,(int)((long)widths[i]*available/total));
  for(int i=2;i>=0;i--){right[i]=edge;edge-=widths[i]+gap;}
  int nameLimit=font.width("w".repeat(justepicdimensions.monitor.JEDMonitoringGateway.MAX_DIMENSION_ID_LENGTH));
  return new Columns(Math.min(nameLimit,Math.max(0,edge-8)),widths,right);
 }
 private String fit(String text,int available){if(font.width(text)<=available)return text;String suffix=font.width("…")<=available?"…":"";return font.plainSubstrByWidth(text,Math.max(0,available-font.width(suffix)))+suffix;}
 private void cell(GuiGraphics g,String text,int right,int available,int y,int color){String shown=fit(text,available);g.drawString(font,shown,right-font.width(shown),y,color);}
 private void overview(GuiGraphics g,int mx,int my){
  String[] headers={label("column.dimension","Dimension").getString(),label("column.chunks","Chunks").getString(),label("column.entities","Entities").getString(),label("column.tick","Mean ms/tick").getString()};
  var rows=result.getAsJsonArray("dimensions");var columns=columns(headers);
  g.drawString(font,fit(headers[0],columns.dimensionWidth()),8,88,0xffaaaaaa);
  for(int i=0;i<3;i++)cell(g,headers[i+1],columns.right()[i],columns.widths()[i],88,0xffaaaaaa);
  int visible=Math.max(1,(height-143)/14);scroll=Math.min(scroll,Math.max(0,rows.size()-visible));
  for(int i=scroll;i<Math.min(rows.size(),scroll+visible);i++){
   var row=rows.get(i).getAsJsonObject();boolean alert=!row.getAsJsonArray("alerts").isEmpty();int color=alert?0xffffaa55:0xffdddddd,y=104+(i-scroll)*14;
   String id=row.get("id").getAsString();g.drawString(font,fit(id,columns.dimensionWidth()),8,y,color);
   for(int column=0;column<3;column++)cell(g,value(row,OVERVIEW_METRICS[column]),columns.right()[column],columns.widths()[column],y,color);
   if(alert)g.drawString(font,"!",width-8-font.width("!"),y,color);
   if(mx>=8&&mx<width-8&&my>=y&&my<y+14)g.renderTooltip(font,Component.literal(id+" | "+value(row,"loaded_chunks")+" | "+value(row,"loaded_entities")+" | "+value(row,"tick_ms_mean")+(alert?" !":"")),mx,my);
  }
  if(mx>=8&&mx<width-8&&my>=88&&my<98)g.renderTooltip(font,Component.literal(String.join(" | ",headers)),mx,my);
 }
 void chart(GuiGraphics g,JsonArray points,int x,int y,int w,int h){g.fill(x,y,x+w,y+h,0xff15202a);if(points.isEmpty()){g.drawString(font,label("no_history","No history in this range"),x+4,y+3,0xffaaaaaa);return;}double max=0;for(var e:points)max=Math.max(max,e.getAsJsonObject().get("max").getAsDouble());if(max<=0)max=1;long first=points.get(0).getAsJsonObject().get("time").getAsLong(),last=points.get(points.size()-1).getAsJsonObject().get("time").getAsLong();for(var e:points){var p=e.getAsJsonObject();int px=x+(int)((p.get("time").getAsLong()-first)*(w-1.0)/Math.max(1,last-first));int py=y+h-1-(int)(p.get("value").getAsDouble()/max*(h-4));int peak=y+h-1-(int)(p.get("max").getAsDouble()/max*(h-4));g.fill(px,peak,px+1,y+h,0xff486776);g.fill(px,py,px+2,py+2,0xff70cfbc);}g.drawString(font,Component.literal(String.format(Locale.ROOT,"max %.2f",max)),x+4,y+2,0xffaaaaaa);}
 public boolean mouseClicked(double x,double y,int button){if(dimension.isEmpty()&&button==0&&result.has("dimensions")&&x>=8&&x<width-8&&y>=104&&y<height-39){int index=scroll+((int)y-104)/14;var rows=result.getAsJsonArray("dimensions");if(index<rows.size()){dimension=rows.get(index).getAsJsonObject().get("id").getAsString();result=new JsonObject();rebuildWidgets();request("status");return true;}}return super.mouseClicked(x,y,button);}
 public boolean mouseScrolled(double x,double y,double dx,double dy){if(dimension.isEmpty()){scroll=Math.max(0,scroll-(int)Math.signum(dy));return true;}return super.mouseScrolled(x,y,dx,dy);}
}
