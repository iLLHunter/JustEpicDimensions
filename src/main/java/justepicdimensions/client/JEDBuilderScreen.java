package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.builder.*;
import justepicdimensions.JEDText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native widgets over a detached local document tree. The server owns persistence and activation. */
public final class JEDBuilderScreen extends Screen {
    private static final Gson PRETTY=new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
    private static final JEDBuilderFields.Field DISPLAY_NAME=JEDBuilderFields.all().stream().filter(f->f.document().equals("properties")&&f.path().equals("display_name")).findFirst().orElseThrow();
    JsonObject snapshot=new JsonObject();JEDBuilderEdit edit;String name="",stamp="",status="",target="justepicdimensions:new_dimension",search="",category="All";
    String newName="",copySource="minecraft:overworld",copyMode="independent",pendingOperation="";int page;boolean home=true;JEDBuilderFields.Field selected;
    final JEDBuilderInputs inputs=new JEDBuilderInputs();Runnable afterUpdate;private EditBox searchBox,valueBox,targetBox,displayNameBox;private AbstractWidget valueControl;private String localError="",savedTarget=target,targetError="";private boolean syncingName;private Button errorsButton;private final Map<JEDBuilderFields.Field,Button> fieldButtons=new LinkedHashMap<>();
    public JEDBuilderScreen(){super(tr("title"));}
    static Component tr(String key){return Component.translatable("justepicdimensions.gui."+key);}
    public boolean dirty(){return edit!=null&&(edit.dirty()||inputs.blocking()||!target.equals(savedTarget));}
    private void restore(){edit=new JEDBuilderEdit(snapshot.getAsJsonObject("documents"));target=savedTarget=snapshot.has("target")?snapshot.get("target").getAsString():"justepicdimensions:new_dimension";targetError="";inputs.clear();inputs.inspect(edit);}
    void receive(JsonObject value){var next=afterUpdate;afterUpdate=null;pendingOperation="";snapshot=value;name=value.get("name").getAsString();stamp=value.has("stamp")?value.get("stamp").getAsString():"";status=JEDBuilderClient.text(value,"message");if(value.has("documents")){restore();home=false;}else home=true;if(next!=null){next.run();return;}rebuildWidgets();}
    private Button button(String key,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(tr(key),b->{if(!JEDBuilderClient.busy)try{action.run();}catch(Exception e){status=JEDText.error(e).getString();}}).bounds(x,y,w,20).build());}
    private Button literal(String text,int x,int y,int w,Runnable action){var b=addRenderableWidget(Button.builder(Component.literal(text),v->{if(!JEDBuilderClient.busy)action.run();}).bounds(x,y,w,20).build());b.setTooltip(Tooltip.create(Component.literal(text)));return b;}
    private EditBox input(String label,int x,int y,int w,String value,java.util.function.Consumer<String> change){var box=new EditBox(font,x,y,w,20,JEDText.fixed(label));box.setMaxLength(JEDBuilderWire.MAX_CHARS);box.setValue(value);box.setResponder(change);addRenderableWidget(box);return box;}
    private JsonObject message(String op){var j=new JsonObject();j.addProperty("op",op);j.addProperty("name",name);j.addProperty("stamp",stamp);return j;}
    private void send(JsonObject j){try{JEDBuilderClient.request(j);status=tr("working").getString();}catch(Exception e){status=JEDText.error(e).getString();}}
    public void update(){
        if(edit==null)return;
        if(!validTarget())return;
        if(inputs.blocking()){focusIssue(inputs.issues().stream().filter(i->!i.remote()).findFirst().orElseThrow());return;}
        var j=message("update");j.addProperty("target",target);j.add("documents",edit.documents());send(j);
    }
    void serverError(Component message){status=message.getString();if(edit!=null){inputs.server(edit,message);inputs.issues().stream().filter(JEDBuilderInputs.Issue::remote).findFirst().ifPresent(this::focusIssue);}}
    void failed(Component message){
        String operation=pendingOperation;pendingOperation="";afterUpdate=null;
        if(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text&&Set.of("justepicdimensions.text.builder.preview_missing","justepicdimensions.text.builder.stale").contains(text.getKey()))message=JEDText.tr("justepicdimensions.gui.review_stale");
        serverError(message);minecraft.setScreen(operation.isEmpty()?this:new ReviewProblem(this));
    }
    void terrainError(Component message){if(edit==null){status=message.getString();return;}var field=JEDBuilderFields.all().stream().filter(f->f.document().equals("dimension")&&f.path().equals("generator")).findFirst().orElseThrow();inputs.failure(edit,field,JEDBuilderFieldInfo.tr("terrain_failed",message));focusIssue(inputs.issue(field));}
    void focusIssue(JEDBuilderInputs.Issue issue){
        home=false;search="";category="All";selected=issue.field();page=JEDBuilderFields.all().indexOf(selected)/rows();status=issue.message().getString();rebuildWidgets();
        if(valueControl!=null){setFocused(valueControl);if(valueBox!=null)valueBox.moveCursorToEnd(false);}
    }
    void accept(JEDBuilderFields.Field field,JsonElement value){inputs.accept(edit,field,value);localError="";status="";}
    private void enter(JEDBuilderFields.Field field,String text){
        if(syncingName)return;inputs.enter(edit,field,text);localError="";status="";
        if(!field.equals(DISPLAY_NAME))return;syncingName=true;
        try{if(displayNameBox!=null&&!displayNameBox.getValue().equals(text))displayNameBox.setValue(text);if(DISPLAY_NAME.equals(selected)&&valueBox!=null&&!valueBox.getValue().equals(text))valueBox.setValue(text);}finally{syncingName=false;}
    }
    private boolean validTarget(){try{JEDBuilderDraft.parseTarget(target);targetError="";return true;}catch(Exception error){targetError=JEDText.error(error).getString();minecraft.setScreen(new TargetProblem(this));return false;}}
    void operation(String op){
        if(JEDBuilderClient.busy||!validTarget())return;
        if(dirty()){status=JEDText.fixed("Press Update Draft before validation, preview or activation.").getString();minecraft.setScreen(this);return;}
        if(!inputs.preflight(edit,snapshot.get("mode").getAsString().equals("inherit")&&snapshot.has("source"))){minecraft.setScreen(new Issues(this));return;}
        var j=message(op);j.addProperty("target",target);pendingOperation=op;
        afterUpdate=op.equals("preview")?()->minecraft.setScreen(new Review(this)):op.equals("validate")?null:()->minecraft.setScreen(new OperationResult(this,op));
        try{JEDBuilderClient.request(j);status=tr("working").getString();minecraft.setScreen(new Working(this,op));}catch(Exception error){failed(JEDText.error(error));}
    }
    @Override protected void init(){if(home)home();else editor();}
    private int rows(){return Math.max(2,(height-180)/22);}
    private void home(){
        input("Draft name",8,43,width/2-12,newName,s->newName=s);button("new",width/2+4,43,width/2-12,()->{var j=message("new");j.addProperty("name",newName);send(j);});
        var drafts=snapshot.has("drafts")?snapshot.getAsJsonArray("drafts"):new JsonArray();int count=Math.max(1,(height-116)/22);page=Math.min(page,Math.max(0,(drafts.size()-1)/count));
        for(int i=page*count;i<Math.min(drafts.size(),(page+1)*count);i++){String draft=drafts.get(i).getAsString();literal(draft,8,72+(i-page*count)*22,width-16,()->{var j=message("open");j.addProperty("name",draft);send(j);});}
        button("previous",8,height-44,85,()->{page=Math.max(0,page-1);rebuildWidgets();});button("next",97,height-44,65,()->{if((page+1)*count<drafts.size())page++;rebuildWidgets();});var tools=button("tools",166,height-44,Math.max(40,Math.min(90,width-258)),()->minecraft.setScreen(new JEDPregenScreen.Tools(this)));tools.setMessage(JEDPregenScreen.label("tools","World tools"));button("close",width-88,height-44,80,this::onClose);
    }
    private void editor(){
        fieldButtons.clear();valueBox=null;valueControl=null;displayNameBox=null;
        int left=Math.min(210,width/2-12),right=left+16,rw=width-right-8;
        searchBox=input("Search settings",8,43,width-139,search,s->{search=s;page=0;refreshFields();});
        var categories=new ArrayList<String>();categories.add("All");JEDBuilderFields.all().stream().map(JEDBuilderFields.Field::category).distinct().forEach(categories::add);
        addRenderableWidget(new JEDDropdown(width-125,43,117,tr("choose"),category,JEDDropdown.local(categories),c->Component.literal(categoryText(c)),c->{category=c;page=0;rebuildWidgets();}));
        var fields=filtered();int count=rows();page=Math.min(page,Math.max(0,(fields.size()-1)/count));
        for(int i=page*count;i<Math.min(fields.size(),(page+1)*count);i++){var f=fields.get(i);var b=literal((inputs.issue(f)!=null?"! ":f.equals(selected)?"> ":"")+f.label(),8,71+(i-page*count)*22,left,()->{selected=f;rebuildWidgets();});b.setTooltip(Tooltip.create(JEDBuilderFieldInfo.help(f)));fieldButtons.put(f,b);}
        button("previous",8,height-109,Math.max(40,left/2-2),()->{page=Math.max(0,page-1);rebuildWidgets();});button("next",12+left/2,height-109,Math.max(40,left/2-2),()->{if((page+1)*count<fields.size())page++;rebuildWidgets();});
        if(selected!=null){var f=selected;var current=edit.value(f.document(),f.path());String text=inputs.issue(f)!=null&&!inputs.issue(f).remote()?inputs.issue(f).raw():f.text(current);var control=JEDBuilderControls.control(f);
            if(control==JEDBuilderControls.Control.FIXED||control==JEDBuilderControls.Control.RUNTIME){
                valueControl=addRenderableWidget(new JEDDropdown(right,84,rw,Component.literal(f.label()),text,JEDChoiceSources.field(this,f),JEDDropdown::label,s->{accept(f,f.parse(s));rebuildWidgets();}));
            }else if(control==JEDBuilderControls.Control.TERRAIN){
                var dropdown=addRenderableWidget(new JEDDropdown(right,84,rw,JEDBuilderFieldInfo.tr("terrain_setup"),"",JEDChoiceSources.server(this,"minecraft:dimension"),Component::literal,this::chooseTerrain));valueControl=dropdown;
                if(current!=null&&current.isJsonObject()&&current.getAsJsonObject().has("type"))dropdown.setMessage(JEDBuilderFieldInfo.tr("generator_selected",current.getAsJsonObject().get("type").getAsString()).copy().append(" ▾"));
                addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("terrain_setup"),b->minecraft.setScreen(new Terrain(this))).bounds(right,108,rw/2-2,20).build());
                button("json",right+rw/2+2,108,rw/2-2,()->minecraft.setScreen(new JsonEditor(this,f)));
            }else if(control==JEDBuilderControls.Control.BIOME_RULES){
                valueControl=addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("edit_rules"),b->{try{minecraft.setScreen(new JEDBiomeColorsScreen(this,f));}catch(Exception error){var json=new JsonEditor(this,f);json.error=JEDText.error(error).getString();minecraft.setScreen(json);}}).bounds(right,84,rw,20).build());
                button("json",right,108,rw,()->minecraft.setScreen(new JsonEditor(this,f)));
            }else if(f.kind()==JEDBuilderFields.Kind.JSON&&JEDStructuredFieldScreen.supports(f)){
                valueControl=addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("edit_value"),b->minecraft.setScreen(new JEDStructuredFieldScreen(this,f))).bounds(right,84,rw,20).build());
                button("json",right,108,rw,()->minecraft.setScreen(new JsonEditor(this,f)));
            }else if(f.kind()==JEDBuilderFields.Kind.JSON&&JEDStructuredFieldScreen.grouped(f,edit)){
                valueControl=addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("edit_fields"),b->{search=f.path()+".";page=0;selected=JEDBuilderFields.all().stream().filter(child->child.document().equals(f.document())&&child.path().startsWith(search)).findFirst().orElse(null);rebuildWidgets();}).bounds(right,84,rw,20).build());
                button("json",right,108,rw,()->minecraft.setScreen(new JsonEditor(this,f)));
            }else{
                valueBox=input(f.path(),right,84,rw-(f.kind()==JEDBuilderFields.Kind.COLOR?18:0),text,value->enter(f,value));valueControl=valueBox;valueBox.setHint(JEDBuilderFieldInfo.accepted(f));
                if(f.kind()==JEDBuilderFields.Kind.JSON)button("json",right,108,rw,()->minecraft.setScreen(new JsonEditor(this,f)));
            }
            if(f.path().equals("access.rule"))addRenderableWidget(Button.builder(JEDAccessRuleScreen.tr("edit_rule","Create / Edit Rule"),b->{try{JEDAccessRuleScreen.linked(this);}catch(Exception error){failed(JEDText.error(error));}}).bounds(right,108,rw,20).build());
            if(valueControl!=null)valueControl.setTooltip(Tooltip.create(JEDBuilderFieldInfo.help(f)));
            if(f.document().equals("properties")){button("source",right,132,rw/2-2,()->{edit.inherit(f.path());inputs.clear(f);rebuildWidgets();});if(f.resettable())button("reset",right+rw/2+2,132,rw/2-2,()->{edit.reset(f.path());inputs.clear(f);rebuildWidgets();});}
        }else addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("terrain_setup"),b->minecraft.setScreen(new Terrain(this))).bounds(right,71,rw,20).build());
        int y=height-53,w=(width-32)/6;button("drafts",8,y,w,()->leave(()->{home=true;page=0;rebuildWidgets();}));button("documents",12+w,y,w,()->minecraft.setScreen(new Documents(this)));
        var undo=button("undo",16+2*w,y,w,()->{if(dirty()){status=JEDText.fixed("Update or discard local edits before Undo.").getString();return;}send(message("undo"));});undo.active=snapshot.has("undo")&&snapshot.get("undo").getAsBoolean();
        button("validate",20+3*w,y,w,()->operation("validate"));button("review",24+4*w,y,w,()->operation("preview"));
        button("update",28+5*w,y,w,this::update);
        targetBox=input("Target dimension ID",right,height-77,rw,target,s->{target=s;targetError="";status="";});targetBox.setTooltip(Tooltip.create(JEDBuilderFieldInfo.tr("target_help")));targetBox.setHint(Component.literal("justepicdimensions:new_caldonia"));
        var nameIssue=inputs.issue(DISPLAY_NAME);String display=nameIssue!=null&&!nameIssue.remote()?nameIssue.raw():DISPLAY_NAME.text(edit.value("properties","display_name"));
        displayNameBox=input("Dimension Name",8,height-77,left,display,s->enter(DISPLAY_NAME,s));
        displayNameBox.setTooltip(Tooltip.create(JEDBuilderFieldInfo.tr("name_help")));
        button("close",width-73,height-28,65,this::onClose);
        literal(tr("copy").getString(),8,height-28,100,()->leave(()->minecraft.setScreen(new Copy(this))));
        errorsButton=addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("errors",inputs.issues().size()),b->minecraft.setScreen(new Issues(this))).bounds(113,height-28,Math.max(50,Math.min(95,width-199)),20).build());errorsButton.active=!inputs.issues().isEmpty();
    }
    private static String categoryText(String category){return Set.of("All","Native","General").contains(category)?JEDText.fixed(category).getString():JEDText.tr("category."+category).getString();}
    private List<JEDBuilderFields.Field> filtered(){String query=search.toLowerCase(Locale.ROOT);return JEDBuilderFields.all().stream().filter(f->category.equals("All")||f.category().equals(category)).filter(f->f.path().toLowerCase(Locale.ROOT).contains(query)||f.label().toLowerCase(Locale.ROOT).contains(query)||JEDBuilderFieldInfo.help(f).getString().toLowerCase(Locale.ROOT).contains(query)).toList();}
    private void refreshFields(){var focused=searchBox;int cursor=focused.getCursorPosition();rebuildWidgets();setFocused(searchBox);searchBox.setCursorPosition(cursor);}
    private void chooseTerrain(String source){var terrain=new Terrain(this);minecraft.setScreen(terrain);terrain.choose(source);}
    void browse(String registry,java.util.function.Consumer<String> selected){minecraft.setScreen(new RegistryPicker(this,registry,selected,this));}
    void leave(Runnable next){if(JEDBuilderClient.busy){status=JEDText.fixed("Wait for the current operation.").getString();return;}if(dirty())minecraft.setScreen(new Unsaved(this,next));else next.run();}
    @Override public void onClose(){leave(JEDBuilderClient::close);}
    @Override public boolean isPauseScreen(){return false;}
    // Vanilla only records mouse clicks as mouse input. After typing, a focused
    // field otherwise keeps its keyboard tooltip open while the pointer moves away.
    @Override public void mouseMoved(double x,double y){minecraft.setLastInputType(InputType.MOUSE);super.mouseMoved(x,y);}
    @Override public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);g.drawString(font,home?tr("private").getString():name+" · "+snapshot.get("mode").getAsString()+(dirty()?" *":""),8,29,0xb8d6ed);
        if(!home){
            if(selected!=null){int right=Math.min(210,width/2-12)+16;
                var issue=inputs.issue(selected);String state=JEDText.fixed(edit.hasValue(selected.document(),selected.path())?"Written in draft":edit.resetAt(selected.path())?"Reset to default":"Use source / default").getString();
                g.drawString(font,font.plainSubstrByWidth(state,width-right-8),right,68,0xa4d6a1);
            if(valueControl!=null)valueControl.setTooltip(Tooltip.create(issue==null?JEDBuilderFieldInfo.help(selected):issue.reason().copy().append("\n").append(JEDBuilderFieldInfo.help(selected))));
                if(valueBox!=null){valueBox.setTextColor(issue==null?0xffffff:0xff9999);if(selected.kind()==JEDBuilderFields.Kind.COLOR&&valueBox.getValue().matches("#[0-9a-fA-F]{6}"))g.fill(width-22,86,width-10,102,0xff000000|Integer.parseInt(valueBox.getValue().substring(1),16));}
                int available=height-91-159;if(available>=font.lineHeight){var content=issue==null?JEDBuilderFieldInfo.help(selected):issue.reason();var lines=font.split(content,width-right-8);for(int i=0;i<Math.min(lines.size(),available/font.lineHeight);i++)g.drawString(font,lines.get(i),right,159+i*font.lineHeight,issue==null?0xbdbdbd:0xff9999);}
            }else{int right=Math.min(210,width/2-12)+16;var lines=font.split(JEDBuilderFieldInfo.tr("finish_help"),width-right-8);for(int line=0;line<Math.min(lines.size(),Math.max(0,(height-91-98)/font.lineHeight));line++)g.drawString(font,lines.get(line),right,98+line*font.lineHeight,0xbdbdbd);}
            int right=Math.min(210,width/2-12)+16;g.drawString(font,font.plainSubstrByWidth(JEDBuilderFieldInfo.tr("target_label").getString(),width-right-8),right,height-87,targetError.isEmpty()?0xb8d6ed:0xff9999);if(targetBox!=null){targetBox.setTextColor(targetError.isEmpty()?0xffffff:0xff9999);targetBox.setTooltip(Tooltip.create(targetError.isEmpty()?JEDBuilderFieldInfo.tr("target_help"):Component.literal(targetError).append("\n").append(JEDBuilderFieldInfo.tr("target_help"))));}
            var nameIssue=inputs.issue(DISPLAY_NAME);g.drawString(font,font.plainSubstrByWidth(JEDBuilderFieldInfo.tr("name_label").getString(),right-16),8,height-87,nameIssue==null?0xb8d6ed:0xff9999);
            if(displayNameBox!=null){displayNameBox.setTextColor(nameIssue==null?0xffffff:0xff9999);displayNameBox.setTooltip(Tooltip.create(nameIssue==null?JEDBuilderFieldInfo.tr("name_help"):nameIssue.reason().copy().append("\n").append(JEDBuilderFieldInfo.tr("name_help"))));}
            if(errorsButton!=null){errorsButton.setMessage(JEDBuilderFieldInfo.tr("errors",inputs.issues().size()));errorsButton.active=!inputs.issues().isEmpty();}
            fieldButtons.forEach((field,button)->{var issue=inputs.issue(field);button.setMessage(Component.literal((issue!=null?"! ":field.equals(selected)?"> ":"")+field.label()));button.setTooltip(Tooltip.create(issue==null?JEDBuilderFieldInfo.help(field):issue.reason().copy().append("\n").append(JEDBuilderFieldInfo.help(field))));});
        }
        var selectedIssue=selected==null?null:inputs.issue(selected);String info=selectedIssue!=null?selectedIssue.reason().getString():localError.isEmpty()?status:localError;
        int start=home?8:213,space=home?width-16:width-start-80;
        if(space>0)g.drawString(font,font.plainSubstrByWidth(JEDBuilderClient.busy?tr("working").getString():info,space),start,height-22,inputs.issues().isEmpty()?0xdddddd:0xff9999);
        if(!info.isEmpty()&&space>0&&x>=start&&x<start+Math.min(space,font.width(info))&&y>=height-22&&y<height-22+font.lineHeight)g.renderTooltip(font,font.split(selectedIssue==null?Component.literal(info):selectedIssue.reason(),Math.max(40,Math.min(width-20,420))),x,y);
    }
    private static final class Issues extends Sub {
        int page;Issues(JEDBuilderScreen parent){super(parent,"title");}
        protected void init(){var all=parent.inputs.issues();int count=Math.max(1,(height-88)/64);page=Math.min(page,Math.max(0,(all.size()-1)/count));
            for(int i=page*count;i<Math.min(all.size(),(page+1)*count);i++){var issue=all.get(i);var b=addRenderableWidget(Button.builder(Component.literal(issue.field().label()),v->{minecraft.setScreen(parent);parent.focusIssue(issue);}).bounds(8,48+(i-page*count)*64,width-16,20).build());b.setTooltip(Tooltip.create(issue.message()));}
            button("previous",8,height-32,80,()->{page=Math.max(0,page-1);rebuildWidgets();});button("next",92,height-32,80,()->{if((page+1)*count<all.size()){page++;rebuildWidgets();}});button("back",width-88,height-32,80,this::onClose);
            if(all.stream().anyMatch(i->i.field().document().equals("dimension")))addRenderableWidget(Button.builder(JEDBuilderFieldInfo.tr("terrain_setup"),b->minecraft.setScreen(new Terrain(parent))).bounds(8,height-56,width-16,20).build());
        }
        public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);g.drawString(font,font.plainSubstrByWidth(JEDBuilderFieldInfo.tr("errors_help").getString(),width-16),8,32,0xffbbbb);var all=parent.inputs.issues();int count=Math.max(1,(height-88)/64);for(int i=page*count;i<Math.min(all.size(),(page+1)*count);i++){var lines=font.split(all.get(i).reason(),width-24);for(int line=0;line<Math.min(3,lines.size());line++)g.drawString(font,lines.get(line),12,72+(i-page*count)*64+line*font.lineHeight,0xffaaaa);}}
    }
    abstract static class Sub extends Screen {
        final JEDBuilderScreen parent;Sub(JEDBuilderScreen p,String title){this(p,tr(title));}Sub(JEDBuilderScreen p,Component title){super(title);parent=p;}
        Button button(String key,int x,int y,int w,Runnable r){return addRenderableWidget(Button.builder(tr(key),b->{if(!JEDBuilderClient.busy)try{r.run();}catch(Exception e){parent.status=JEDText.error(e).getString();}}).bounds(x,y,w,20).build());}
        void readOnly(String text,int height){
            var box=new MultiLineEditBox(font,8,36,width-16,height,Component.empty(),getTitle()){
                @Override public void setValue(String value){super.setValue(value);setScrollAmount(0);}
                @Override public boolean charTyped(char c,int modifiers){return false;}
                @Override public boolean keyPressed(int key,int scan,int modifiers){return (key>=262&&key<=269||Screen.isSelectAll(key)||Screen.isCopy(key))&&super.keyPressed(key,scan,modifiers);}
            };box.setCharacterLimit(Integer.MAX_VALUE);box.setValue(text);addRenderableWidget(box);
        }
        EditBox input(String label,int x,int y,int w,String value,java.util.function.Consumer<String> r){var b=new EditBox(font,x,y,w,20,JEDText.fixed(label));b.setMaxLength(JEDBuilderWire.MAX_CHARS);b.setValue(value);b.setResponder(r);addRenderableWidget(b);return b;}
        public boolean isPauseScreen(){return false;}public void onClose(){minecraft.setScreen(parent);}public void mouseMoved(double x,double y){minecraft.setLastInputType(InputType.MOUSE);super.mouseMoved(x,y);}public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);}
    }
    private static final class Unsaved extends Sub {
        final Runnable next;Unsaved(JEDBuilderScreen p,Runnable n){super(p,"unsaved");next=n;}
        protected void init(){button("update",width/2-130,height/2,260,()->{if(parent.inputs.blocking()){minecraft.setScreen(parent);parent.update();return;}parent.afterUpdate=next;parent.update();});button("discard",width/2-130,height/2+24,128,()->{parent.restore();minecraft.setScreen(parent);next.run();});button("cancel",width/2+2,height/2+24,128,this::onClose);}
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawWordWrap(font,tr("unsaved_help"),width/2-130,45,260,0xdddddd);}
    }
    private static final class TargetProblem extends Sub {
        TargetProblem(JEDBuilderScreen parent){super(parent,JEDBuilderFieldInfo.tr("target_label"));}
        protected void init(){button("back",width/2-60,height-32,120,this::onClose);}
        public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);g.drawWordWrap(font,Component.literal(parent.targetError).append("\n\n").append(JEDBuilderFieldInfo.tr("target_help")),12,42,width-24,0xffbbbb);}
        public void onClose(){super.onClose();if(parent.targetBox!=null){parent.setFocused(parent.targetBox);parent.targetBox.moveCursorToEnd(false);}}
    }
    private static final class Terrain extends Sub {
        Terrain(JEDBuilderScreen parent){super(parent,JEDBuilderFieldInfo.tr("terrain_setup"));}
        protected void init(){
            addRenderableWidget(new JEDDropdown(8,88,width-16,JEDBuilderFieldInfo.tr("terrain_setup"),"",JEDChoiceSources.server(parent,"minecraft:dimension"),Component::literal,this::choose));
            addRenderableWidget(Button.builder(JEDWorldgenScreen.text("Worldgen editor"),b->minecraft.setScreen(new JEDWorldgenScreen(parent,this))).bounds(8,116,width-16,20).build());
            button("back",width/2-60,height-32,120,this::onClose);
        }
        void choose(String source){var current=parent.edit.documents().get("dimension");if(current!=null&&current.isJsonObject()&&!current.getAsJsonObject().isEmpty())minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes->{minecraft.setScreen(this);if(yes)fetch(source);},JEDBuilderFieldInfo.tr("terrain_replace"),JEDBuilderFieldInfo.tr("terrain_preserve")));else fetch(source);}
        void fetch(String source){
            JEDBuilderClient.terrainReply=json->{parent.inputs.document(parent.edit,"dimension",json.get("dimension").toString());parent.inputs.inspect(parent.edit);parent.selected=JEDBuilderFields.all().stream().filter(f->f.document().equals("dimension")&&f.path().equals("generator")).findFirst().orElseThrow();parent.status=JEDBuilderFieldInfo.tr("terrain_selected",source).getString();minecraft.setScreen(parent);};
            var request=parent.message("terrain");request.addProperty("source",source);try{JEDBuilderClient.request(request);parent.status=tr("working").getString();}catch(Exception error){JEDBuilderClient.terrainReply=null;parent.terrainError(JEDText.error(error));minecraft.setScreen(parent);}
        }
        public void onClose(){if(!JEDBuilderClient.busy)super.onClose();}
        public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);var lines=font.split(JEDBuilderFieldInfo.tr("terrain_preserve"),width-16);for(int line=0;line<Math.min(4,lines.size());line++)g.drawString(font,lines.get(line),8,30+line*font.lineHeight,0xb8d6ed);}
    }
    private static final class Copy extends Sub {
        Copy(JEDBuilderScreen p){super(p,"copy");}protected void init(){
            addRenderableWidget(new JEDDropdown(8,45,width-16,JEDText.fixed("Source dimension"),parent.copySource,JEDChoiceSources.server(parent,"minecraft:dimension"),Component::literal,s->parent.copySource=s));
            addRenderableWidget(new JEDDropdown(8,74,width-16,tr("mode"),parent.copyMode,JEDDropdown.local(List.of("independent","inherit")),JEDDropdown::label,s->parent.copyMode=s));
            button("copy",8,height-32,width/2-12,()->{var j=parent.message("copy");j.addProperty("source",parent.copySource);j.addProperty("mode",parent.copyMode);parent.send(j);});button("cancel",width/2+4,height-32,width/2-12,this::onClose);
        }
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawWordWrap(font,tr("copy_help"),8,105,width-16,0xdddddd);}
    }
    private static Screen openDocument(JEDBuilderScreen parent,String name){try{return JEDAccessRuleScreen.supports(name)?JEDAccessRuleScreen.document(parent,name):new JsonEditor(parent,name);}catch(RuntimeException invalid){return new JsonEditor(parent,name);}}
    private static final class Documents extends Sub {
        String name="";int page;Documents(JEDBuilderScreen p){super(p,"documents");}protected void init(){input("Document name or namespaced resource.json",8,40,width-96,name,s->name=s);button("add",width-80,40,72,()->{JEDBuilderDraft.checkDocument(name);parent.edit.document(name,"{}");minecraft.setScreen(openDocument(parent,name));});var docs=parent.edit.documents().keySet().stream().sorted().toList();int count=Math.max(1,(height-118)/22);for(int i=page*count;i<Math.min(docs.size(),(page+1)*count);i++){String doc=docs.get(i);var b=button("json",8,69+(i-page*count)*22,width-100,()->minecraft.setScreen(openDocument(parent,doc)));b.setMessage(Component.literal(doc));button("remove",width-88,69+(i-page*count)*22,80,()->{parent.inputs.drop(parent.edit,doc);rebuildWidgets();});}button("previous",8,height-40,85,()->{page=Math.max(0,page-1);rebuildWidgets();});button("next",97,height-40,65,()->{if((page+1)*count<docs.size())page++;rebuildWidgets();});button("back",width-88,height-40,80,this::onClose);}
    }
    private static final class JsonEditor extends Sub {
        final String document;final JEDBuilderFields.Field field;String text,error="";final String initialText;MultiLineEditBox box;
        JsonEditor(JEDBuilderScreen p,String d){super(p,"json");document=d;field=null;text=PRETTY.toJson(p.edit.documents().get(d));initialText=text;}
        JsonEditor(JEDBuilderScreen p,JEDBuilderFields.Field f){super(p,"json");document=f.document();field=f;var v=p.edit.value(document,f.path());text=p.inputs.issue(f)!=null&&!p.inputs.issue(f).remote()?p.inputs.issue(f).raw():v==null?JEDBuilderFieldInfo.example(f):PRETTY.toJson(v);initialText=text;}
        protected void init(){box=new MultiLineEditBox(font,8,37,width-16,height-98,Component.literal("JSON"),Component.literal(document));box.setCharacterLimit(JEDBuilderWire.MAX_CHARS);box.setValue(text);box.setValueListener(s->text=s);addRenderableWidget(box);button("local",8,height-32,width/2-12,()->{try{if(field==null)parent.inputs.document(parent.edit,document,text);else parent.accept(field,field.parse(text));parent.localError="";parent.status="";minecraft.setScreen(parent);}catch(Exception e){error=JEDText.error(e).getString();}});button("cancel",width/2+4,height-32,width/2-12,this::onClose);}
        @Override public void onClose(){if(!text.equals(initialText)){minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(discard->{if(discard)minecraft.setScreen(parent);else minecraft.setScreen(this);},tr("discard_json"),tr("local_help")));}else super.onClose();}
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawString(font,font.plainSubstrByWidth(field==null?document:document+":"+field.path(),width-16),8,27,0xb8d6ed);var lines=font.split(Component.literal(error),width-16);for(int line=0;line<Math.min(2,lines.size());line++)g.drawString(font,lines.get(line),8,height-55+line*font.lineHeight,0xffaaaa);if(!error.isEmpty()&&y>=height-55&&y<height-55+2*font.lineHeight)g.renderTooltip(font,font.split(Component.literal(error),width-20),x,y);}
    }
    private static final class Review extends Sub {
        Review(JEDBuilderScreen p){super(p,"review");}
        protected void init(){
            var review=parent.snapshot.getAsJsonObject("review");
            var summary=JEDText.tr("gui.review_target",review.get("target").getAsString()).copy().append("\n\n").append(JEDText.tr("justepicdimensions.gui.review_files",review.getAsJsonArray("changes").size())).append("\n\n").append(JEDText.tr("justepicdimensions.gui.review_next"));
            for(var notice:review.getAsJsonArray("notices"))summary.append("\n\n").append(notice.getAsString());
            readOnly(summary.getString(),height-124);
            button("save_load",8,height-80,width-16,()->parent.operation("load"));
            button("review_details",8,height-55,width/2-12,()->minecraft.setScreen(new ReviewDetails(parent,this)));
            button("advanced_actions",width/2+4,height-55,width/2-12,()->minecraft.setScreen(new AdvancedActions(parent,this)));
            button("preview",8,height-30,width/2-12,()->parent.operation("preview"));button("back",width/2+4,height-30,width/2-12,this::onClose);
        }
    }
    private static final class ReviewDetails extends Sub {
        final Review back;ReviewDetails(JEDBuilderScreen parent,Review back){super(parent,"review_details");this.back=back;}
        protected void init(){readOnly(reviewText(parent.snapshot.getAsJsonObject("review")),height-76);button("back",8,height-30,width-16,this::onClose);}
        public void onClose(){minecraft.setScreen(back);}
    }
    private static final class AdvancedActions extends Sub {
        final Review back;AdvancedActions(JEDBuilderScreen parent,Review back){super(parent,"advanced_actions");this.back=back;}
        protected void init(){
            readOnly(JEDText.tr("justepicdimensions.gui.advanced_help").getString(),height-101);
            int w=(width-24)/3;var review=parent.snapshot.getAsJsonObject("review");boolean registered=review.has("registered")&&review.get("registered").getAsBoolean();
            button("save",8,height-55,w,()->parent.operation("save"));
            button("create",12+w,height-55,w,()->parent.operation("create")).active=!registered;
            button("apply",16+2*w,height-55,w,()->parent.operation("apply")).active=registered;
            button("back",8,height-30,width-16,this::onClose);
        }
        public void onClose(){minecraft.setScreen(back);}
    }
    private static final class Working extends Sub {
        final String operation;Working(JEDBuilderScreen parent,String operation){super(parent,"working");this.operation=operation;}
        protected void init(){readOnly(tr(operation.equals("load")?"save_load":operation).getString()+"\n\n"+JEDText.tr("justepicdimensions.gui.operation_wait").getString(),height-52);}
        public void onClose(){}
    }
    private static final class OperationResult extends Sub {
        final String operation,command;boolean copied;OperationResult(JEDBuilderScreen parent,String operation){super(parent,"operation_done");this.operation=operation;command="/tpj "+parent.target;}
        protected void init(){
            String next=JEDText.tr("justepicdimensions.gui.result_"+operation).getString();
            boolean entry=operation.equals("load")||operation.equals("apply");
            if(entry)next+="\n\n"+command;
            readOnly(parent.status+"\n\n"+next,height-(entry?101:76));
            if(entry)button(copied?"command_copied":"copy_teleport",8,height-55,width-16,()->{minecraft.keyboardHandler.setClipboard(command);copied=true;rebuildWidgets();});
            int w=(width-24)/3;button("review",8,height-30,w,()->parent.operation("preview"));button("back",12+w,height-30,w,this::onClose);button("close",16+2*w,height-30,w,JEDBuilderClient::close);
        }
    }
    private static final class ReviewProblem extends Sub {
        ReviewProblem(JEDBuilderScreen parent){super(parent,"operation_failed");}
        protected void init(){readOnly(parent.status+"\n\n"+JEDText.tr("justepicdimensions.gui.review_retry").getString(),height-76);button("preview",8,height-30,width/2-12,()->parent.operation("preview"));button("back",width/2+4,height-30,width/2-12,this::onClose);}
    }
    private static String reviewText(JsonObject review){
        var text=new StringBuilder();text.append(JEDText.tr("gui.review_target",review.get("target").getAsString()).getString()).append("\n\n").append(JEDText.tr("gui.review_directory").getString()).append("\n").append(review.get("source_directory").getAsString()).append("\n\n").append(JEDText.tr("gui.review_mode",review.get("copy_mode").getAsString()).getString());
        if(review.has("copy_source"))text.append("\n").append(JEDText.tr("gui.review_source",review.get("copy_source").getAsString()).getString());
        for(var notice:review.getAsJsonArray("notices"))text.append("\n\n").append(notice.getAsString());
        for(var change:review.getAsJsonArray("changes")){var row=change.getAsJsonObject();text.append("\n\n").append(JEDText.tr("gui.review_file",row.get("file").getAsString()).getString()).append("\n").append(JEDText.tr("gui.review_activation",JEDText.fixed(row.get("activation").getAsString())).getString()).append("\n\n").append(JEDText.tr("gui.before").getString()).append("\n").append(PRETTY.toJson(row.get("before"))).append("\n\n").append(JEDText.tr("gui.after").getString()).append("\n").append(PRETTY.toJson(row.get("after")));}
        return text.toString();
    }
    private static final class RegistryPicker extends Sub {
        final String registry;final java.util.function.Consumer<String> selected;final Screen back;String query="";int offset,total;List<String> values=List.of();
        RegistryPicker(JEDBuilderScreen p,String r,java.util.function.Consumer<String> c,Screen back){super(p,"browse");registry=r;selected=c;this.back=back;}
        protected void init(){input("Registry search",8,37,width-100,query,s->query=s);button("search",width-84,37,76,()->{offset=0;fetch();});int count=Math.max(1,(height-105)/22);for(int i=0;i<Math.min(count,values.size());i++){String id=values.get(i);var b=button("choose",8,66+i*22,width-16,()->{selected.accept(id);if(minecraft.screen==this)minecraft.setScreen(back);});b.setMessage(Component.literal(id));}button("previous",8,height-30,85,()->{offset=Math.max(0,offset-count);fetch();});button("next",97,height-30,65,()->{if(offset+count<total)offset+=count;fetch();});button("back",width-88,height-30,80,this::onClose);}
        public void onClose(){minecraft.setScreen(back);}
        void fetch(){JEDBuilderClient.registryReply=json->{total=json.get("total").getAsInt();values=new ArrayList<>();for(var v:json.getAsJsonArray("values"))values.add(v.getAsString());rebuildWidgets();};var j=new JsonObject();j.addProperty("op","registry");j.addProperty("registry",registry);j.addProperty("query",query);j.addProperty("offset",offset);parent.send(j);}
    }
}
