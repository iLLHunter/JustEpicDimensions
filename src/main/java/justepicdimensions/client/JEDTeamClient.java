package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.teams.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client UI only. Authorization and every current membership check remain on the server. */
public final class JEDTeamClient {
    private static UUID token;private static int sequence;private static boolean busy;
    private static final JEDBuilderWire.Assembly IN=new JEDBuilderWire.Assembly();
    private static Screen back;private static Consumer<JsonObject> picked;private static String pickerSystem;private static TeamScreen active;
    static Component text(String key,String fallback,Object...args){return Component.translatableWithFallback("justepicdimensions.teams."+key,fallback,args);}
    public static void open(String mode,ResourceLocation source){
        var connection=Minecraft.getInstance().getConnection();if(connection==null)return;
        connection.sendCommand("tpj instance "+(mode.equals("admin")?"ownership":"permissions "+source));
    }
    static void picker(String system,Consumer<JsonObject> callback){
        closeSession();back=Minecraft.getInstance().screen;picked=callback;pickerSystem=system;
        PacketDistributor.sendToServer(new JEDBuilderWire.Request(new JEDBuilderWire.Frame(JEDTeamGateway.CHANNEL,0,0,1,"open:picker")));
    }
    public static boolean receive(JEDBuilderWire.Frame frame){
        if(!frame.session().equals(JEDTeamGateway.CHANNEL))return false;
        try{
            if(frame.request()==0&&frame.index()==0)IN.clear();String raw=IN.accept(frame,System.nanoTime());if(raw==null)return true;
            var json=JEDBuilderWire.json(raw);UUID received=UUID.fromString(json.get("token").getAsString());
            if(frame.request()==0){if(token!=null&&!token.equals(received))closeSession();token=received;sequence=0;busy=false;
                if(!json.get("mode").getAsString().equals("picker")){if(!(Minecraft.getInstance().screen instanceof TeamScreen))back=Minecraft.getInstance().screen;picked=null;pickerSystem=null;}
                active=new TeamScreen(json);Minecraft.getInstance().setScreen(active);return true;
            }
            if(!received.equals(token)||frame.request()!=sequence)return true;busy=false;
            if(json.has("close")&&json.get("close").getAsBoolean()){token=null;active=null;Minecraft.getInstance().setScreen(back);return true;}
            if(active!=null)active.receive(json);
        }catch(RuntimeException error){busy=false;if(active!=null){active.message=String.valueOf(error.getMessage());active.refresh();}}
        return true;
    }
    private static void request(JsonObject json){if(token==null||busy)return;sequence++;busy=true;if(active!=null)active.refresh();for(var frame:JEDBuilderWire.split(token,sequence,JEDTeamGateway.PREFIX+json))PacketDistributor.sendToServer(new JEDBuilderWire.Request(frame));}
    private static void closeSession(){if(token!=null){var json=new JsonObject();json.addProperty("action","cancel");for(var frame:JEDBuilderWire.split(token,++sequence,JEDTeamGateway.PREFIX+json))PacketDistributor.sendToServer(new JEDBuilderWire.Request(frame));}token=null;busy=false;active=null;IN.clear();}
    public static void logout(){token=null;sequence=0;busy=false;active=null;picked=null;back=null;IN.clear();}
    static abstract class Form extends Screen {
        int scroll,maxY;final List<AbstractWidget> fields=new ArrayList<>();String message="";
        Form(Component title){super(title);}
        void refresh(){rebuildWidgets();}
        <T extends AbstractWidget>T control(T widget,int logicalY){maxY=Math.max(maxY,logicalY+30);widget.setY(logicalY-scroll);widget.visible=widget.getY()>=38&&widget.getY()+widget.getHeight()<=height-62;widget.active=widget.visible&&!busy;return addRenderableWidget(widget);}
        EditBox field(String key,String label,int y,String value,Consumer<String> changed){var box=new EditBox(font,8,0,width-16,20,text(key,label));box.setMaxLength(512);box.setValue(value);box.setResponder(changed);box.setTooltip(Tooltip.create(text(key,label)));fields.add(box);return control(box,y);}
        Button button(String key,String label,int y,Runnable clicked){return control(Button.builder(text(key,label),b->{try{clicked.run();}catch(RuntimeException error){message=String.valueOf(error.getMessage());}}).bounds(8,0,width-16,20).build(),y);}
        void dropdown(String key,String label,int y,String value,List<String> options,java.util.function.Function<String,Component> names,Consumer<String> selected){JEDDropdown.Source source=(query,offset,reply)->{String search=query.toLowerCase(Locale.ROOT);var matching=options.stream().filter(v->v.toLowerCase(Locale.ROOT).contains(search)||names.apply(v).getString().toLowerCase(Locale.ROOT).contains(search)).toList();reply.accept(new JEDDropdown.Page(matching.stream().skip(offset).limit(80).toList(),offset,matching.size()));return true;};control(new JEDDropdown(8,0,width-16,text(key,label),value,source,names,selected),y);}
        void footer(){footer("back","Back");}
        void footer(String key,String label){addRenderableWidget(Button.builder(text(key,label),b->onClose()).bounds(8,height-28,width-16,20).build());}
        public boolean mouseScrolled(double x,double y,double dx,double dy){scroll=Math.max(0,Math.min(Math.max(0,maxY-(height-62)),scroll+(dy<0?32:-32)));rebuildWidgets();return true;}
        public boolean isPauseScreen(){return false;}
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,font.plainSubstrByWidth(title.getString(),width-16),width/2,10,0xffffff);for(var field:fields)if(field.visible&&field.getY()>=48)g.drawString(font,font.plainSubstrByWidth(field.getMessage().getString(),width-16),8,field.getY()-10,0xffcccccc);g.drawString(font,font.plainSubstrByWidth(message,width-16),8,height-48,0xffffaaaa);if(y>=height-52&&y<height-32&&!message.isEmpty())g.renderTooltip(font,Component.literal(message),x,y);}
    }
    private static final class TeamScreen extends Form {
        final String mode,source;JsonArray groups;String selected="",instance="",player="",permission="reset",level="managers",system="minecraft";JsonObject settings=new JsonObject();int page;boolean memberTab;
        TeamScreen(JsonObject json){super(text("title","Team / party controls"));mode=json.get("mode").getAsString();source=json.has("source")?json.get("source").getAsString():"";groups=json.has("groups")?json.getAsJsonArray("groups"):new JsonArray();message=json.has("error")?json.get("error").getAsString():"";if(!groups.isEmpty())selected=identity(groups.get(0).getAsJsonObject());}
        static String identity(JsonObject group){return group.get("system").getAsString()+":"+group.get("id").getAsString();}
        JsonObject group(){for(var row:groups)if(identity(row.getAsJsonObject()).equals(selected))return row.getAsJsonObject();throw new IllegalArgumentException("Select a team or party");}
        Component groupLabel(String id){for(var row:groups){var group=row.getAsJsonObject();if(identity(group).equals(id))return Component.literal(group.get("name").getAsString()+" | "+group.get("system").getAsString()+" | "+group.get("id").getAsString());}return Component.literal(id);}
        JsonObject action(String name){var request=new JsonObject();request.addProperty("action",name);if(!source.isEmpty())request.addProperty("source",source);if(!selected.isEmpty()){var group=group();request.addProperty("system",group.get("system").getAsString());request.addProperty("id",group.get("id").getAsString());}if(!instance.isBlank())request.addProperty("instance",instance);return request;}
        void receive(JsonObject json){
            if(json.has("groups")){groups=json.getAsJsonArray("groups");if(groups.asList().stream().noneMatch(v->identity(v.getAsJsonObject()).equals(selected)))selected=groups.isEmpty()?"":identity(groups.get(0).getAsJsonObject());}
            if(json.has("settings")){settings=json.getAsJsonObject("settings");var value=settings.getAsJsonObject(permission);level=value==null?"inherit":value.get("level").getAsString();}
            message=json.has("error")?json.get("error").getAsString():json.has("message")?json.get("message").getAsString():"";
            if(json.has("preview")){var preview=json.getAsJsonObject("preview");String confirmation=json.get("confirmation").getAsString();Minecraft.getInstance().setScreen(new ConfirmScreen(yes->{Minecraft.getInstance().setScreen(this);if(yes){var request=new JsonObject();request.addProperty("action","confirm");request.addProperty("confirmation",confirmation);request(request);}},text("confirm_owner","Apply ownership change?"),text("owner_change","Current: %s; replacement: %s",preview.get("previous").getAsString(),preview.get("replacement").getAsString())));return;}
            rebuildWidgets();
        }
        protected void init(){fields.clear();maxY=0;
            if(mode.equals("chooser")||mode.equals("picker")){groupPage();return;}
            var ids=groups.asList().stream().map(v->identity(v.getAsJsonObject())).toList();dropdown("group","Team / party",42,selected,ids,this::groupLabel,v->{selected=v;settings=new JsonObject();});
            field("instance","Instance ID (blank = team defaults)",86,instance,v->instance=v);
            field("player","Member / replacement owner UUID",130,player,v->player=v);
            if(mode.equals("admin")){
                button("native_owner","Assign native-team JED owner",166,()->{var j=action("preview");j.addProperty("operation","native_owner");j.addProperty("player",player);request(j);});
                button("repair_owner","Repair instance ownership",194,()->{var j=action("preview");j.addProperty("operation","repair_owner");request(j);});
            }else{
                dropdown("action","Action",166,permission,new ArrayList<>(new TreeSet<>(JEDTeamPermissions.ACTIONS)),v->text("action."+v,v),v->{permission=v;var value=settings.getAsJsonObject(v);level=value==null?"inherit":value.get("level").getAsString();rebuildWidgets();});
                dropdown("level","Delegation level",198,level,List.of("inherit","owner","managers","members"),v->text("level."+v,switch(v){case "owner"->"Owner only";case "managers"->"Owner + officers/moderators/JED managers";case "members"->"All owning-team members";default->"Inherit";}),v->level=v);
                button("view","View current settings",230,()->request(action("view")));
                button("set","Apply delegation level",258,()->{var j=action("set");j.addProperty("permission",permission);j.addProperty("level",level);request(j);});
                button("grant","Grant action to member",286,()->grant(true));button("revoke","Revoke individual grant",314,()->grant(false));
                button("manager_add","Appoint native-team JED manager",342,()->manager(true));button("manager_remove","Remove native-team JED manager",370,()->manager(false));
                if(settings.has(permission))button("grants","Show individual grants",398,()->message=settings.getAsJsonObject(permission).getAsJsonArray("grants").toString());
            }
            button("refresh","Refresh teams / parties",430,()->request(action("groups")));footer();
        }
        void grant(boolean add){var j=action("grant");j.addProperty("permission",permission);j.addProperty("player",player);j.addProperty("add",add);request(j);}
        void manager(boolean add){var j=action("native_manager");j.addProperty("player",player);j.addProperty("add",add);request(j);}
        void groupPage(){
            List<JsonObject> rows=groups.asList().stream().map(JsonElement::getAsJsonObject).filter(g->!mode.equals("picker")||pickerSystem==null||g.get("system").getAsString().equals(pickerSystem)).toList();
            int count=Math.max(1,(height-120)/48);int pages=Math.max(1,(rows.size()+count-1)/count);page=Math.max(0,Math.min(page,pages-1));
            for(int i=page*count;i<Math.min(rows.size(),(page+1)*count);i++){var group=rows.get(i);int y=42+(i-page*count)*48;var name=Component.literal(group.get("name").getAsString());var tooltip=Component.literal(group.get("system").getAsString()+" | "+group.get("id").getAsString()+" | "+group.get("type").getAsString());
                addRenderableWidget(Button.builder(name,b->{selected=identity(group);if(mode.equals("chooser"))request(action("choose"));else{var callback=picked;var returnTo=back;closeSession();picked=null;Minecraft.getInstance().setScreen(returnTo);if(callback!=null)callback.accept(group);}}).bounds(8,y,width-16,20).tooltip(Tooltip.create(tooltip)).build());
            }
            addRenderableWidget(Button.builder(text("previous","Previous"),b->{page--;rebuildWidgets();}).bounds(8,height-82,(width-24)/2,20).build());addRenderableWidget(Button.builder(text("next","Next"),b->{page++;rebuildWidgets();}).bounds(width/2+4,height-82,(width-24)/2,20).build());footer("cancel","Cancel");
            if(rows.isEmpty()&&message.isEmpty())message=text("no_groups","No available groups in this system").getString();
        }
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);if(mode.equals("chooser")||mode.equals("picker")){var rows=groups.asList().stream().map(JsonElement::getAsJsonObject).filter(v->!mode.equals("picker")||pickerSystem==null||v.get("system").getAsString().equals(pickerSystem)).toList();int count=Math.max(1,(height-120)/48);for(int i=page*count;i<Math.min(rows.size(),(page+1)*count);i++){var group=rows.get(i);int rowY=42+(i-page*count)*48;g.drawString(font,font.plainSubstrByWidth(group.get("system").getAsString(),width-16),8,rowY+22,0xffcccccc);g.drawString(font,font.plainSubstrByWidth(group.get("id").getAsString(),width-16),8,rowY+33,0xffcccccc);}}else if(!source.isEmpty())g.drawCenteredString(font,font.plainSubstrByWidth(source,width-16),width/2,24,0xffcccccc);}
        public void onClose(){var returnTo=back;closeSession();picked=null;Minecraft.getInstance().setScreen(returnTo);}
    }
    private JEDTeamClient(){}
}
