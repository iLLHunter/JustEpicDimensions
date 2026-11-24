package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.access.JEDAccessRule;
import justepicdimensions.builder.*;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Detached, recursive All/Any/Not editor. Only its final Keep action updates the builder draft. */
final class JEDAccessRuleScreen extends Screen {
    private static final List<String> KINDS=List.of("all","any","not","advancement","score","team","teams","permission_level","permission","item");
    private static final Gson PRETTY=new GsonBuilder().setPrettyPrinting().create();
    final String resource;final Screen back;final Consumer<JsonObject> saved;final JsonObject original;JsonObject node;String kind,error="";int page;
    final Map<String,String> values=new LinkedHashMap<>(),readValues=new LinkedHashMap<>();final List<AbstractWidget> boxes=new ArrayList<>();
    private record Field(String key,String label,String type){}
    static Component tr(String key,String english,Object...args){return JEDAccessRule.text("editor."+key,english,args);}
    static boolean supports(String document){var id=ResourceLocation.tryParse(document);return id!=null&&id.getPath().startsWith(JEDAccessRule.DIRECTORY+"/")&&id.getPath().endsWith(".json");}
    static Screen document(JEDBuilderScreen builder,String name){
        var current=builder.edit.documents().getAsJsonObject(name);var file=current==null?new JsonObject():current.deepCopy();
        var condition=file.has("condition")?file.getAsJsonObject("condition"):fresh("all");
        return new JEDAccessRuleScreen(builder,name,condition,next->{file.addProperty("format_version",1);file.add("condition",next);builder.edit.document(name,PRETTY.toJson(file));});
    }
    static void linked(JEDBuilderScreen builder){
        var selected=builder.edit.value("properties","access.rule");String name=selected==null?builder.target+"_access":selected.getAsString();
        var id=JEDBuilderDraft.parseTarget(name);var resource=ResourceLocation.fromNamespaceAndPath(id.getNamespace(),JEDAccessRule.DIRECTORY+"/"+id.getPath()+".json").toString();
        Consumer<JsonObject> open=file->{var copy=file.deepCopy();var condition=copy.has("condition")?copy.getAsJsonObject("condition"):fresh("all");minecraft(builder).setScreen(new JEDAccessRuleScreen(builder,name,condition,next->{copy.addProperty("format_version",1);copy.add("condition",next);builder.edit.document(resource,PRETTY.toJson(copy));builder.accept(JEDBuilderFields.all().stream().filter(f->f.path().equals("access.rule")).findFirst().orElseThrow(),new JsonPrimitive(name));}));};
        if(builder.edit.documents().has(resource)){open.accept(builder.edit.documents().getAsJsonObject(resource));return;}
        if(JEDBuilderClient.busy)return;
        JEDBuilderClient.registryReply=reply->{if(reply.get("registry").getAsString().equals("justepicdimensions:access_rule_document")&&reply.get("query").getAsString().equals(name))open.accept(reply.getAsJsonObject("document"));};
        var request=new JsonObject();request.addProperty("op","registry");request.addProperty("registry","justepicdimensions:access_rule_document");request.addProperty("query",name);request.addProperty("offset",0);JEDBuilderClient.request(request);
    }
    private static net.minecraft.client.Minecraft minecraft(JEDBuilderScreen parent){return net.minecraft.client.Minecraft.getInstance();}
    JEDAccessRuleScreen(Screen back,JsonObject node,Consumer<JsonObject> saved){this(back,null,node,saved);}
    JEDAccessRuleScreen(Screen back,String resource,JsonObject node,Consumer<JsonObject> saved){super(resource==null?tr("title","Access Conditions"):tr("named_title","Access Conditions: %s",resource));this.resource=resource;this.back=back;this.saved=saved;this.node=node.deepCopy();this.original=node.deepCopy();kind=node.size()==1&&KINDS.contains(node.keySet().iterator().next())?node.keySet().iterator().next():"all";if(!node.has(kind))this.node=fresh(kind);validateShape(this.node,0);read();}
    static JsonObject fresh(String kind){var node=new JsonObject();node.add(kind,kind.equals("all")||kind.equals("any")?new JsonArray():kind.equals("not")?fresh("all"):kind.equals("team")?new JsonPrimitive(""):kind.equals("permission_level")?new JsonPrimitive(0):new JsonObject());return node;}
    private static void validateShape(JsonObject node,int depth){
        if(depth>32||node.size()!=1||!KINDS.contains(node.keySet().iterator().next()))throw new IllegalArgumentException(tr("choose_kind","Choose one condition type").getString());
        String kind=node.keySet().iterator().next();var value=node.get(kind);
        if(kind.equals("all")||kind.equals("any")){if(!value.isJsonArray())throw new IllegalArgumentException(tr("group_array","Condition groups require arrays").getString());for(var child:value.getAsJsonArray())validateShape(child.getAsJsonObject(),depth+1);}
        else if(kind.equals("not"))validateShape(value.getAsJsonObject(),depth+1);
        else if(!kind.equals("team")&&!kind.equals("permission_level")&&!value.isJsonObject())throw new IllegalArgumentException(tr("field_object","Condition fields require an object").getString());
    }
    private List<Field> fields(){return switch(kind){
        case "team"->List.of(new Field("$","Team","text"));case "permission_level"->List.of(new Field("$","Permission Level","integer"));
        case "advancement"->List.of(new Field("id","Advancement ID","text"),new Field("complete","Completed (optional)","boolean"),new Field("criteria","Criteria (JSON)","json"));
        case "score"->List.of(new Field("objective","Scoreboard Objective","text"),new Field("min","Minimum","integer"),new Field("max","Maximum","integer"));
        case "permission"->List.of(new Field("provider","Permission Provider","provider"),new Field("node","Permission Node","text"),new Field("value","Granted (default: true)","boolean"));
        case "item"->List.of(new Field("predicate.items","Items or #tag","items"),new Field("min","Minimum Quantity (default: 1)","integer"),new Field("max","Maximum Quantity","integer"),new Field("locations","Item Locations (list)","locations"),new Field("predicate.components","Data Components (JSON)","json"),new Field("predicate.predicates","Component Predicates (JSON)","json"),new Field("predicate.count","Per-stack Count (JSON)","json"));
        default->List.of();
    };}
    private boolean group(){return Set.of("all","any","not").contains(kind);}
    private JsonArray conditionChildren(){if(kind.equals("not")){var values=new JsonArray();values.add(node.get("not"));return values;}return node.getAsJsonArray(kind);}
    private void child(int i,JsonObject next){if(kind.equals("not"))node.add("not",next);else conditionChildren().set(i,next);}
    private void read(){values.clear();for(var f:fields()){var v=f.key().equals("$")?node.get(kind):JEDBuilderDraft.find(node.get(kind),f.key());values.put(f.key(),v==null?"":v.isJsonPrimitive()&&!f.type().equals("json")?v.getAsString():v.toString());}readValues.clear();readValues.putAll(values);}
    private void flush(){
        if(group()||kind.equals("teams"))return;var settings=new JsonObject();
        for(var f:fields()){
            String text=values.getOrDefault(f.key(),"").strip();if(text.isEmpty()){if(f.key().equals("$"))throw new IllegalArgumentException(tr("required","A value is required").getString());continue;}JsonElement v;
            if(f.type().equals("integer"))v=new JsonPrimitive(Integer.parseInt(text));
            else if(f.type().equals("boolean")){if(!Set.of("true","false").contains(text))throw new IllegalArgumentException(tr("boolean","Use true or false").getString());v=new JsonPrimitive(Boolean.parseBoolean(text));}
            else if(f.type().equals("json"))v=JEDBuilderWire.value(text);
            else if(f.type().equals("items")||f.type().equals("locations")){
                if(text.startsWith("[")||text.startsWith("\""))v=JEDBuilderWire.value(text);
                else if(f.type().equals("items")&&text.startsWith("#"))v=new JsonPrimitive(text);
                else{var array=new JsonArray();for(String part:text.split(","))array.add(part.strip());v=array;}
            }else v=new JsonPrimitive(text);
            if(f.key().equals("$")){node.add(kind,v);return;}JEDBuilderDraft.set(settings,f.key(),v);
        }
        if(kind.equals("item")&&!settings.has("predicate"))settings.add("predicate",new JsonObject());
        if(!kind.equals("team")&&!kind.equals("permission_level"))node.add(kind,settings);
    }
    private Button button(Component text,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(text,b->{try{action.run();}catch(Exception e){error=String.valueOf(e.getMessage());}}).bounds(x,y,w,20).build());}
    protected void init(){
        boxes.clear();
        addRenderableWidget(new JEDDropdown(8,36,width-16,tr("kind","Condition Type"),kind,JEDDropdown.local(KINDS),v->tr("kind."+v,kindName(v)),v->{if(v.equals(kind))return; minecraft.setScreen(new ConfirmScreen(yes->{if(yes){kind=v;node=fresh(v);page=0;read();}minecraft.setScreen(this);},tr("replace_title","Replace Condition?"),tr("replace_help","Changing the type replaces this condition and its children.")));}));
        if(kind.equals("teams")){
            button(tr("teams_edit","Edit allowed teams / parties"),8,72,width-16,()->minecraft.setScreen(new JEDTeamsRuleScreen(this,node.getAsJsonObject("teams"),next->node.add("teams",next))));
        }else if(group()){
            var children=conditionChildren();int count=Math.max(1,(height-164)/24);page=Math.min(page,Math.max(0,(children.size()-1)/count));
            for(int i=page*count;i<Math.min(children.size(),(page+1)*count);i++){
                int index=i,y=72+(i-page*count)*24;var value=children.get(i).getAsJsonObject();String childKind=value.size()==1?value.keySet().iterator().next():"all";
                var b=button(tr("child","%s: %s",i+1,tr("kind."+childKind,kindName(childKind))),8,y,width-142,()->minecraft.setScreen(new JEDAccessRuleScreen(this,value,next->child(index,next))));b.setTooltip(Tooltip.create(Component.literal(value.toString())));
                if(!kind.equals("not")){
                    button(Component.literal("↑"),width-130,y,36,()->{if(index>0){var other=children.get(index-1);children.set(index-1,children.get(index));children.set(index,other);rebuildWidgets();}});
                    button(Component.literal("↓"),width-90,y,36,()->{if(index+1<children.size()){var other=children.get(index+1);children.set(index+1,children.get(index));children.set(index,other);rebuildWidgets();}});
                    button(Component.literal("−"),width-50,y,42,()->{children.remove(index);rebuildWidgets();});
                }
            }
            if(!kind.equals("not"))button(tr("add","Add Condition"),8,height-82,Math.max(100,(width-24)/2),()->{children.add(fresh("team"));page=(children.size()-1)/count;rebuildWidgets();});
            button(Component.translatable("justepicdimensions.gui.previous"),8,height-56,90,()->{page=Math.max(0,page-1);rebuildWidgets();});button(Component.translatable("justepicdimensions.gui.next"),102,height-56,90,()->{if((page+1)*count<children.size())page++;rebuildWidgets();});
        }else{
            int count=Math.max(1,(height-138)/39);var fields=fields();page=Math.min(page,Math.max(0,(fields.size()-1)/count));
            for(int i=page*count;i<Math.min(fields.size(),(page+1)*count);i++){
                var f=fields.get(i);int y=82+(i-page*count)*39;var label=tr("field."+kind+"."+f.key().replace('.','_').replace("$","value"),f.label());
                if(f.type().equals("boolean")||f.type().equals("provider")){var choices=f.type().equals("provider")?List.of("","neoforge","luckperms"):List.of("","true","false");var dropdown=new JEDDropdown(8,y,width-16,label,values.get(f.key()),JEDDropdown.local(choices),v->v.isEmpty()?tr("default","Default"):v.equals("luckperms")?Component.literal("LuckPerms"):v.equals("neoforge")?Component.literal("NeoForge"):JEDDropdown.label(v),v->{values.put(f.key(),v);error="";});boxes.add(dropdown);addRenderableWidget(dropdown);continue;}
                var box=new EditBox(font,8,y,width-16,20,label);box.setMaxLength(JEDBuilderWire.MAX_CHARS);box.setValue(values.get(f.key()));box.setResponder(v->{values.put(f.key(),v);error="";});box.setTooltip(Tooltip.create(label));boxes.add(box);addRenderableWidget(box);
            }
            button(Component.translatable("justepicdimensions.gui.previous"),8,height-56,90,()->{page=Math.max(0,page-1);rebuildWidgets();});button(Component.translatable("justepicdimensions.gui.next"),102,height-56,90,()->{if((page+1)*count<fields.size())page++;rebuildWidgets();});
        }
        button(Component.translatable("justepicdimensions.gui.json"),width-98,height-56,90,()->{flush();minecraft.setScreen(new Json(this));});
        button(Component.translatable("justepicdimensions.gui.local"),8,height-28,width/2-12,()->{flush();if(group()&&conditionChildren().isEmpty())throw new IllegalArgumentException(tr("empty_group","Add a condition to this group").getString());saved.accept(node.deepCopy());minecraft.setScreen(back);});
        button(Component.translatable("justepicdimensions.gui.cancel"),width/2+4,height-28,width/2-12,this::onClose);
    }
    private static String kindName(String value){return switch(value){case "all"->"All";case "any"->"Any";case "not"->"Not";case "advancement"->"Advancement";case "score"->"Score";case "team"->"Team";case "teams"->"Teams / parties";case "permission_level"->"Permission Level";case "permission"->"Permission Node";default->"Item";};}
    public void onClose(){boolean invalidEdits=false;try{flush();}catch(Exception ignored){invalidEdits=!values.equals(readValues);}if(invalidEdits||!node.equals(original))minecraft.setScreen(new ConfirmScreen(discard->minecraft.setScreen(discard?back:this),Component.translatable("justepicdimensions.gui.discard_json"),Component.translatable("justepicdimensions.gui.local_help")));else minecraft.setScreen(back);}
    public boolean isPauseScreen(){return false;}
    public void mouseMoved(double x,double y){minecraft.setLastInputType(InputType.MOUSE);super.mouseMoved(x,y);}
    public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);g.drawCenteredString(font,font.plainSubstrByWidth(title.getString(),width-16),width/2,12,0xffffff);if(resource!=null)g.drawCenteredString(font,font.plainSubstrByWidth(tr("shared_help","Edits affect every dimension using this rule.").getString(),width-16),width/2,24,0xffcccccc);for(var box:boxes)g.drawString(font,font.plainSubstrByWidth(box.getMessage().getString(),width-16),8,box.getY()-11,0xffcccccc);g.drawString(font,font.plainSubstrByWidth(error,width-16),8,group()?height-99:height-83,0xffffaaaa);if(y>=10&&y<23)g.renderTooltip(font,title,x,y);else if(resource!=null&&y>=23&&y<35)g.renderTooltip(font,tr("shared_help","Edits affect every dimension using this rule."),x,y);}
    private static final class Json extends Screen {
        final JEDAccessRuleScreen parent;MultiLineEditBox box;String value,error="";
        Json(JEDAccessRuleScreen parent){super(Component.translatable("justepicdimensions.gui.json"));this.parent=parent;value=PRETTY.toJson(parent.node);}
        protected void init(){box=new MultiLineEditBox(font,8,36,width-16,Math.max(30,height-90),Component.empty(),title);box.setCharacterLimit(JEDBuilderWire.MAX_CHARS);box.setValue(value);box.setValueListener(v->value=v);addRenderableWidget(box);
            addRenderableWidget(Button.builder(Component.translatable("justepicdimensions.gui.local"),b->{try{var next=JEDBuilderWire.value(value).getAsJsonObject();if(next.size()!=1||!KINDS.contains(next.keySet().iterator().next()))throw new IllegalArgumentException(tr("choose_kind","Choose one condition type").getString());validateShape(next,0);parent.node=next.deepCopy();parent.kind=next.keySet().iterator().next();parent.read();minecraft.setScreen(parent);}catch(Exception e){error=String.valueOf(e.getMessage());}}).bounds(8,height-28,width/2-12,20).build());
            addRenderableWidget(Button.builder(Component.translatable("justepicdimensions.gui.cancel"),b->onClose()).bounds(width/2+4,height-28,width/2-12,20).build());}
        public void onClose(){if(!value.equals(PRETTY.toJson(parent.node)))minecraft.setScreen(new ConfirmScreen(discard->minecraft.setScreen(discard?parent:this),Component.translatable("justepicdimensions.gui.discard_json"),Component.translatable("justepicdimensions.gui.local_help")));else minecraft.setScreen(parent);}
        public boolean isPauseScreen(){return false;}
        public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);g.drawCenteredString(font,title,width/2,12,0xffffff);g.drawString(font,font.plainSubstrByWidth(error,width-16),8,height-48,0xffffaaaa);}
    }
}
