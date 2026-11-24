package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/** Server-backed choices; the controller rechecks every action at execution time. */
final class JEDInstanceScreen extends JEDBuilderScreen.Sub {
    static final String NEW_RUN="\u0000new";
    String source,target="",run="default",group="",member="",message="";
    boolean groups,requested,loaded,canExit;int page;
    JsonArray rows=new JsonArray(),sources=new JsonArray(),groupChoices=new JsonArray(),players=new JsonArray();
    static Component label(String key,String fallback){return Component.translatableWithFallback("justepicdimensions.instance."+key,fallback);}
    JEDInstanceScreen(JEDBuilderScreen parent){super(parent,label("title","Instanced dimensions"));source=parent.target;}
    static JsonObject find(JsonArray choices,String key,String value){for(var raw:choices){var row=raw.getAsJsonObject();if(row.has(key)&&row.get(key).getAsString().equals(value))return row;}return null;}
    static boolean yes(JsonObject row,String key){return row!=null&&row.has(key)&&row.get(key).getAsBoolean();}
    static boolean contains(JsonArray values,String id){return values.asList().stream().anyMatch(value->value.getAsString().equals(id));}
    List<String> ids(JsonArray choices,String key){return choices.asList().stream().map(value->value.getAsJsonObject().get(key).getAsString()).toList();}
    Component named(JsonArray choices,String id){var row=find(choices,"id",id);return Component.literal(row==null?id:row.get("name").getAsString()+(choices==sources?" ("+id+")":""));}
    JsonObject selected(){return find(rows,"dimension",target);}
    JsonObject sourceRow(){return find(sources,"id",source);}
    void sourceSelected(String id){source=id;target="";group="";run="default";message="";rebuildWidgets();}
    void instanceSelected(String id){var row=find(rows,"dimension",id);if(row==null)return;target=id;source=row.get("source").getAsString();run=row.get("run").getAsString();group=row.get("ownership").getAsString().equals("group")?row.get("owner").getAsString():"";message="";rebuildWidgets();}
    List<String> runs(){var values=new TreeSet<String>();values.add("default");for(var raw:rows){var row=raw.getAsJsonObject();if(row.get("source").getAsString().equals(source))values.add(row.get("run").getAsString());}var result=new ArrayList<>(values);if(yes(sourceRow(),"multiple"))result.add(NEW_RUN);return result;}
    Component instanceLabel(String id){var row=find(rows,"dimension",id);if(row==null)return Component.literal(id);return Component.literal(row.get("source").getAsString()+" / "+row.get("run").getAsString()+" / "+row.get("owner_name").getAsString()+" / "+row.get("status").getAsString());}
    protected void init(){
        int half=(width-24)/2;
        var from=addRenderableWidget(new JEDDropdown(8,34,half,label("source","Source dimension"),find(sources,"id",source)==null?"":source,JEDDropdown.local(()->ids(sources,"id"),id->named(sources,id),Component::literal),id->id.isEmpty()?label("source","Select source dimension"):named(sources,id),this::sourceSelected));from.active=!requested;
        if(groups){var name=button("group_name",16+half,34,half,()->name(false));name.setMessage(label("group_name","Name a new group…"));name.active=!requested&&yes(sourceRow(),"can_group_create");}
        else{var runs=addRenderableWidget(new JEDDropdown(16+half,34,half,label("run","Run name"),run,JEDDropdown.local(this::runs,id->id.equals(NEW_RUN)?label("new_run","New run…"):Component.literal(id),id->null),id->id.equals(NEW_RUN)?label("new_run","New run…"):Component.literal(id),id->{if(id.equals(NEW_RUN))name(true);else{run=id;target="";rebuildWidgets();}}));runs.active=!requested&&sourceRow()!=null;}
        var instances=addRenderableWidget(new JEDDropdown(8,58,width-16,label("target","Choose an instance"),target,JEDDropdown.local(()->ids(rows,"dimension"),this::instanceLabel,id->instanceLabel(id).copy().append("\n"+id)),id->id.isEmpty()?label("target","Select an instance"):instanceLabel(id),this::instanceSelected));instances.active=!requested&&!rows.isEmpty();
        if(groups){var picker=addRenderableWidget(new JEDDropdown(8,82,width-16,label("player","Choose a player"),member,JEDDropdown.local(()->ids(players,"id"),id->named(players,id),id->named(players,id).copy().append("\nUUID: "+id)),id->id.isEmpty()?label("player","Select a player"):named(players,id),id->{member=id;rebuildWidgets();}));picker.active=!requested;}
        else{boolean explicit=sourceRow()!=null&&sourceRow().get("ownership").getAsString().equals("group");var picker=addRenderableWidget(new JEDDropdown(8,82,width-16,label("group","Choose a JED group"),group,JEDDropdown.local(()->groupChoices.asList().stream().map(JsonElement::getAsJsonObject).filter(row->contains(row.getAsJsonArray("create_sources"),source)).map(row->row.get("id").getAsString()).toList(),id->named(groupChoices,id),id->named(groupChoices,id).copy().append("\nUUID: "+id)),id->id.isEmpty()?label(explicit?"group":"group_optional",explicit?"Choose a JED group":sourceRow()!=null&&sourceRow().get("ownership").getAsString().equals("team")?"Owning team/party is chosen when creating":"No group required"):named(groupChoices,id),id->{group=id;rebuildWidgets();}));picker.active=!requested&&explicit;}
        var actions=groups?List.of("group_create","member_add","member_remove","manager_add","manager_remove","list"):List.of("create","select","enter","exit","reset","retire","list");
        int w=Math.max(30,(width-24)/3);
        for(int i=0;i<actions.size();i++){String action=actions.get(i);var b=button(action,8+(i%3)*(w+4),108+(i/3)*24,w,()->{
            if(action.equals("group_create")){name(false);return;}
            if(action.equals("retire")||action.equals("reset"))minecraft.setScreen(new ConfirmScreen(confirmed->{minecraft.setScreen(this);if(confirmed)request(action);},label(action+"_confirm",action.equals("retire")?"Retire this instance?":"Reset this instance?"),label("recoverable","Affected world files move to recoverable history; saved player references are relocated.")));
            else request(action);
        });b.setMessage(label(action,switch(action){case "group_create"->"New group";case "member_add"->"Add member";case "member_remove"->"Remove member";case "manager_add"->"Add manager";case "manager_remove"->"Remove manager";case "list"->"Refresh";default->Character.toUpperCase(action.charAt(0))+action.substring(1);}));b.active=!requested&&allowed(action);}
        int quarter=(width-32)/4;
        var mode=button("mode",8,height-30,quarter,()->{groups=!groups;if(!groups&&selected()==null)run="default";rebuildWidgets();});mode.setMessage(label(groups?"runs":"groups",groups?"Instance runs":"Group members"));mode.active=!requested;
        var permissions=button("team_permissions",12+quarter,height-30,quarter,()->JEDTeamClient.open("permissions",net.minecraft.resources.ResourceLocation.parse(source)));permissions.setMessage(label("team_permissions","Team permissions"));permissions.active=!requested&&sourceRow()!=null&&sourceRow().get("ownership").getAsString().equals("team");
        var ownership=button("team_ownership",16+quarter*2,height-30,quarter,()->JEDTeamClient.open("admin",null));ownership.setMessage(label("team_ownership","Ownership"));ownership.active=!requested;
        button("back",20+quarter*3,height-30,quarter,this::onClose);
        if(!loaded&&!requested&&JEDBuilderClient.session!=null)request("list");
    }
    boolean allowed(String action){
        if(action.equals("list"))return true;if(action.equals("exit"))return canExit;
        var src=sourceRow();var row=selected();var flags=row==null?null:row.getAsJsonObject("allowed");
        if(action.equals("create")){if(!yes(src,"can_create")||run.isBlank()||!yes(src,"multiple")&&!run.equals("default"))return false;if(src.get("ownership").getAsString().equals("group")){var g=find(groupChoices,"id",group);return g!=null&&contains(g.getAsJsonArray("create_sources"),source);}return true;}
        if(action.equals("group_create"))return yes(src,"can_group_create");
        if(action.contains("member_")||action.contains("manager_")){if(row==null||!row.get("ownership").getAsString().equals("group")||member.isEmpty())return false;boolean manager=action.startsWith("manager");if(!yes(flags,manager?"managers":"members"))return false;var g=find(groupChoices,"id",row.get("owner").getAsString());if(g==null)return false;boolean present=contains(g.getAsJsonArray(manager?"managers":"members"),member);return action.endsWith("add")?!present&&(!manager||contains(g.getAsJsonArray("members"),member)):present;}
        return yes(flags,action);
    }
    void name(boolean newRun){minecraft.setScreen(new Name(this,newRun));}
    private static final class Name extends JEDBuilderScreen.Sub {
        final JEDInstanceScreen back;final boolean runName;String value="";
        Name(JEDInstanceScreen back,boolean runName){super(back.parent,label(runName?"new_run":"group_name",runName?"New run":"New group"));this.back=back;this.runName=runName;}
        protected void init(){var input=input("Name",8,40,width-16,value,s->value=s);input.setMaxLength(64);var save=button("create",8,70,width-16,()->{if(value.isBlank()||runName&&!value.matches("[a-z0-9_-]{1,64}"))return;back.run=value;back.target="";minecraft.setScreen(back);if(!runName)back.request("group_create");});save.setMessage(label(runName?"use_run":"group_create",runName?"Use run name":"New group"));save.active=false;input.setResponder(text->{value=text;save.active=!value.isBlank()&&(!runName||value.matches("[a-z0-9_-]{1,64}"));});button("back",8,height-30,width-16,this::onClose);}
        public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);g.drawWordWrap(font,label("name_help",runName?"Use 1–64 lowercase letters, digits, underscores or hyphens.":"Use a group name of 1–64 characters."),8,104,width-16,0xffdddddd);}
        public void onClose(){minecraft.setScreen(back);}
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){return super.mouseScrolled(x,y,dx,dy);}
    void receive(JsonObject reply){loaded=true;rows=reply.getAsJsonArray("instances");sources=reply.getAsJsonArray("sources");groupChoices=reply.getAsJsonArray("groups");players=reply.getAsJsonArray("players");canExit=reply.get("can_exit").getAsBoolean();message=reply.has("created")?reply.get("created").getAsString():"";if(reply.has("created")){String id=reply.get("created").getAsString();if(find(rows,"dimension",id)!=null)instanceSelected(id);else if(find(groupChoices,"id",id)!=null)group=id;}rebuildWidgets();}
    void request(String action){if(requested||JEDBuilderClient.busy)return;var j=new JsonObject();j.addProperty("op","instances");j.addProperty("instance_action",action);j.addProperty("source",source);j.addProperty("target",target);j.addProperty("run",run);j.addProperty("group_uuid",group);j.addProperty("player_uuid",member);requested=true;rebuildWidgets();JEDBuilderClient.instanceReply=reply->{requested=false;if(reply.has("error")){message=reply.get("error").getAsString();rebuildWidgets();}else receive(reply);};try{JEDBuilderClient.request(j);}catch(Exception error){requested=false;message=error.getMessage();rebuildWidgets();}}
    public void render(GuiGraphics graphics,int x,int y,float partial){super.render(graphics,x,y,partial);int top=186;var lines=new ArrayList<Component>();if(!message.isEmpty())lines.add(Component.literal(message));var row=selected();if(row!=null){lines.add(instanceLabel(target));lines.add(Component.literal(target));}for(var line:lines)for(var wrapped:font.split(line,width-16)){if(top>height-44)return;graphics.drawString(font,wrapped,8,top,0xffdddddd);top+=11;}}
    public void onClose(){if(!requested)super.onClose();}
}
