package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.config.*;
import justepicdimensions.JEDText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;

/** Detached edits over three explicit scopes. Only the footer buttons write files. */
public final class JEDConfigScreen extends Screen {
    private static final class Page {
        final JsonObject document;
        JsonObject values;
        Page(JsonObject document){this.document=document.deepCopy();values=document.getAsJsonObject("values").deepCopy();}
        boolean dirty(){return !values.equals(document.getAsJsonObject("values"));}
    }
    private final Screen parent;
    private final Map<String,Page> pages=new HashMap<>();
    private String scope="client",query="",status="",savingScope="";
    private int page;
    private JsonObject server;
    private EditBox search;
    private final List<Button> editing=new ArrayList<>();
    public JEDConfigScreen(Screen parent){super(tr("title"));this.parent=parent;loadLocal("client");}
    static Component tr(String key,Object... args){return Component.translatable("justepicdimensions.config."+key,args);}
    private Page current(){return pages.get(scope);}
    private boolean dirty(){return pages.values().stream().anyMatch(Page::dirty);}
    private boolean editable(){return scope.equals("client")||!JEDConfigClient.remote()||JEDConfigClient.permitted();}
    private void loadLocal(String target){try{pages.put(target,new Page(JEDConfigClient.local(target.equals("client"))));}catch(Exception error){failure(JEDText.error(error).getString());}}
    private void select(String target){
        scope=target;page=0;query="";
        if(!pages.containsKey(target)){
            if(target.equals("client")||!JEDConfigClient.remote())loadLocal(target);
            else if(JEDConfigClient.permitted()){status=tr("working").getString();JEDConfigClient.fetch();}
        }
        rebuildWidgets();
    }
    void receive(JsonObject json){
        server=json;var files=json.getAsJsonObject("files");
        for(String key:List.of("global","world")){
            var old=pages.get(key);
            var document=files.getAsJsonObject(key);
            if(!document.has("error")&&(old==null||!old.dirty()||key.equals(savingScope)))pages.put(key,new Page(document));
        }
        savingScope="";
        status=json.has("message_key")?tr(json.get("message_key").getAsString(),json.has("detail")?JEDConfigClient.text(json,"detail"):"").getString():"";
        if(json.has("backup"))status+=" "+tr("backup",json.get("backup").getAsString()).getString();
        if(!scope.equals("client")&&files.getAsJsonObject(scope).has("error"))status+=" "+JEDConfigClient.text(files.getAsJsonObject(scope),"error");
        rebuildWidgets();
    }
    void failure(String text){savingScope="";status=text==null?tr("failed").getString():JEDText.fixed(text).getString();if(minecraft!=null)rebuildWidgets();}
    private Button button(Component text,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(text,b->{try{action.run();}catch(Exception error){failure(JEDText.error(error).getString());}}).bounds(x,y,w,20).build());}
    private Button button(String key,int x,int y,int w,Runnable action){return button(tr(key),x,y,w,action);}
    private List<JEDConfigFields.Field> fields(){
        String filter=query.toLowerCase(Locale.ROOT);
        return JEDConfigFields.fields(scope.equals("client")).stream().filter(f->f.key().toLowerCase(Locale.ROOT).contains(filter)
            ||Component.translatable(f.translation()).getString().toLowerCase(Locale.ROOT).contains(filter)
            ||tr("category."+f.category()).getString().toLowerCase(Locale.ROOT).contains(filter)).toList();
    }
    private int rows(){return Math.max(1,(height-189)/36);}
    @Override protected void init(){
        editing.clear();int tab=(width-24)/3;
        for(int i=0;i<3;i++){
            String key=List.of("client","global","world").get(i);
            var b=button(tr("scope."+key).copy().append(key.equals(scope)?" *":""),8+i*(tab+4),31,tab,()->select(key));
            b.active=(!key.equals("world")||JEDConfigClient.remote())&&(key.equals("client")||!JEDConfigClient.remote()||JEDConfigClient.permitted());
            b.setTooltip(Tooltip.create(tr(key.equals("client")?"client_help":!JEDConfigClient.remote()?"local_global_help":JEDConfigClient.permitted()?key+"_help":"operator_help")));
        }
        search=new EditBox(font,8,86,width-16,20,tr("search"));search.setMaxLength(256);search.setValue(query);
        search.setResponder(text->{query=text;page=0;int cursor=search.getCursorPosition();rebuildWidgets();setFocused(search);search.setCursorPosition(cursor);});addRenderableWidget(search);
        var values=current();var fields=fields();int count=rows();page=Math.min(page,Math.max(0,(fields.size()-1)/count));
        if(values!=null)for(int i=page*count;i<Math.min(fields.size(),(page+1)*count);i++){
            var field=fields.get(i);int y=113+(i-page*count)*36;
            var edit=button("edit",width-124,y,54,()->minecraft.setScreen(new FieldEditor(field)));
            var reset=button("reset",width-66,y,58,()->{values.values.add(field.key(),field.defaultValue());rebuildWidgets();});
            edit.setTooltip(Tooltip.create(help(field)));reset.setTooltip(Tooltip.create(tr("default",display(field.defaultValue()))));
            editing.add(edit);editing.add(reset);
        }
        var previous=button("previous",8,height-77,52,()->{page=Math.max(0,page-1);rebuildWidgets();});previous.active=page>0;
        var next=button("next",64,height-77,52,()->{if((page+1)*rows()<fields().size())page++;rebuildWidgets();});next.active=(page+1)*count<fields.size();
        button("refresh",120,height-77,65,this::refresh);
        var builder=button("builder",width-122,height-77,114,()->leave(()->{JEDConfigClient.close();JEDBuilderClient.open();}));
        builder.active=JEDConfigClient.remote()&&JEDConfigClient.permitted();builder.setTooltip(Tooltip.create(tr("builder_help")));
        int saveWidth=(width-102)/2;
        var save=button("save",8,height-28,saveWidth,()->save(false));
        var apply=button("save_apply",12+saveWidth,height-28,saveWidth,()->save(true));
        editing.add(save);editing.add(apply);
        apply.setTooltip(Tooltip.create(tr("apply_help")));
        button("close",width-78,height-28,70,this::onClose);
        updateButtons();
    }
    private void updateButtons(){for(var b:editing)b.active=current()!=null&&editable()&&!JEDConfigClient.busy();}
    private Component help(JEDConfigFields.Field field){return Component.translatableWithFallback(field.translation()+".help",JEDConfigComments.text(field.key(),scope.equals("client"))).copy().append("\n"+field.key()+"\n")
        .append(tr("category."+field.category())).append("\n").append(tr("timing."+field.timing().name().toLowerCase(Locale.ROOT))).append("\n").append(tr("default",display(field.defaultValue())));}
    private void refresh(){
        if(JEDConfigClient.busy()){status=tr("working").getString();return;}
        Runnable action=()->{pages.remove(scope);if(scope.equals("client")||!JEDConfigClient.remote()){loadLocal(scope);rebuildWidgets();}else{status=tr("working").getString();JEDConfigClient.fetch();}};
        if(current()!=null&&current().dirty())confirm("refresh_unsaved",action);else action.run();
    }
    private void save(boolean apply){
        if(current()==null||!editable()||JEDConfigClient.busy())return;
        var current=current();JEDConfigFields.validate(current.values,scope.equals("client"));
        if(scope.equals("client")||!JEDConfigClient.remote()){
            var saved=JEDConfigClient.saveLocal(scope.equals("client"),current.document.get("stamp").getAsString(),current.values,apply);
            pages.put(scope,new Page(saved));status=tr(saved.get("message_key").getAsString()).getString();if(saved.has("backup"))status+=" "+tr("backup",saved.get("backup").getAsString()).getString();rebuildWidgets();
        }else{
            var json=new JsonObject();json.addProperty("op",apply?"apply":"save");json.addProperty("scope",scope);json.addProperty("stamp",current.document.get("stamp").getAsString());json.add("values",current.values.deepCopy());
            savingScope=scope;JEDConfigClient.request(json);status=tr("working").getString();updateButtons();
        }
    }
    private void confirm(String help,Runnable action){minecraft.setScreen(new ConfirmScreen(discard->{minecraft.setScreen(this);if(discard)action.run();},tr("unsaved"),tr(help),tr("discard"),tr("cancel")));}
    private void leave(Runnable action){if(JEDConfigClient.busy()){status=tr("working").getString();return;}if(dirty())confirm("unsaved_help",action);else action.run();}
    @Override public void onClose(){leave(()->{JEDConfigClient.close();minecraft.setScreen(parent);});}
    @Override public boolean isPauseScreen(){return !JEDConfigClient.remote();}
    @Override public void tick(){super.tick();JEDConfigClient.timeout();updateButtons();}
    private JsonElement active(String key){
        if(scope.equals("client"))return justepicdimensions.lighting.JEDLightingConfig.runtimeValues().get(key);
        return server==null?null:server.getAsJsonObject("active").get(key);
    }
    private static String display(JsonElement value){if(value==null||value.isJsonNull())return "—";String s=value.isJsonPrimitive()?value.getAsString():value.toString();return s.isEmpty()?"\"\"":s;}
    private String path(){return current()==null?"":current().document.get("path").getAsString();}
    private Component sourceHelp(){
        var text=Component.literal(path());
        if(server!=null&&!scope.equals("client")){text.append("\n").append(tr("active_file",server.get("active_path").getAsString()));text.append("\n").append(tr("selected_file",server.get("selected_path").getAsString()));text.append("\n").append(tr("json_directory",server.get("json_source").getAsString()));}
        if(server!=null&&!scope.equals("client")&&server.has("selection_error"))text.append("\n").append(server.get("selection_error").getAsString());
        if(current()!=null&&!current().document.get("exists").getAsBoolean())text.append("\n").append(tr("missing_file"));
        return text;
    }
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        super.render(g,x,y,partial);g.drawCenteredString(font,title,width/2,12,0xffffff);
        g.drawString(font,font.plainSubstrByWidth(tr("file",path()).getString(),width-16),8,58,0xbbcddd);
        String info=scope.equals("client")?tr("client_help").getString():server==null?tr("local_global_help").getString():tr(server.get("active_path").getAsString().equals(path())?"file_active":"file_inactive").getString();
        g.drawString(font,font.plainSubstrByWidth(info+(current()!=null&&current().dirty()?" · "+tr("unsaved_marker").getString():""),width-16),8,71,0xd8d8b0);
        var fields=fields();int count=rows();var data=current();
        if(data!=null)for(int i=page*count;i<Math.min(fields.size(),(page+1)*count);i++){
            var field=fields.get(i);int row=113+(i-page*count)*36;String label=Component.translatable(field.translation()).getString();
            g.drawString(font,font.plainSubstrByWidth(label,width-140),8,row,0xffffff);
            var saved=data.document.getAsJsonObject("values").get(field.key());var draft=data.values.get(field.key());
            String line=tr("saved_active",display(saved),display(active(field.key()))).getString();
            if(!Objects.equals(saved,draft))line=tr("edited_value",display(draft)).getString()+" · "+line;
            g.drawString(font,font.plainSubstrByWidth(line,width-16),8,row+23,Objects.equals(saved,draft)?0xbbbbbb:0xffdd88);
            if(x>=8&&x<width-132&&y>=row&&y<row+20)g.renderTooltip(font,font.split(help(field),Math.min(420,width-20)),x,y);
            if(x>=8&&y>=row+21&&y<row+34)g.renderTooltip(font,font.split(Component.literal(line),Math.min(420,width-20)),x,y);
        }
        if(data==null)g.drawCenteredString(font,tr(JEDConfigClient.busy()?"working":"unavailable"),width/2,120,0xffbbbb);
        String notice=JEDConfigClient.busy()?tr("working").getString():status;
        if(notice.isEmpty()&&server!=null&&!scope.equals("client")&&!server.getAsJsonArray("pending").isEmpty())notice=tr("pending",server.getAsJsonArray("pending").toString()).getString();
        g.drawString(font,font.plainSubstrByWidth(notice,width-16),8,height-48,0xddeebb);
        if(y>=height-53&&y<height-30&&!notice.isEmpty())g.renderTooltip(font,font.split(Component.literal(notice),Math.min(420,width-20)),x,y);
        if(y>=55&&y<83)g.renderTooltip(font,font.split(sourceHelp(),Math.min(420,width-20)),x,y);
    }
    private final class FieldEditor extends Screen {
        private final JEDConfigFields.Field field;private final String ownerScope=scope;
        private String text,error="";private EditBox value;private JEDDropdown choice;
        FieldEditor(JEDConfigFields.Field field){super(Component.translatable(field.translation()));this.field=field;text=field.text(pages.get(ownerScope).values.get(field.key()));}
        private void done(){try{pages.get(ownerScope).values.add(field.key(),field.parse(text));minecraft.setScreen(JEDConfigScreen.this);}catch(Exception e){error=JEDText.error(e).getString();}}
        @Override protected void init(){
            var options=new ArrayList<>(JEDConfigFields.choices(field));
            if(field.kind()==JEDConfigFields.Kind.RESOURCE&&server!=null&&server.has("choices")&&server.getAsJsonObject("choices").has(field.key())){for(var option:server.getAsJsonObject("choices").getAsJsonArray(field.key()))options.add(option.getAsString());}
            else if(field.key().equals("initial_spawn_dimension")&&minecraft.getConnection()!=null){options.add("");minecraft.getConnection().levels().stream().map(k->k.location().toString()).sorted().forEach(options::add);}
            value=null;choice=null;
            if(!options.isEmpty())choice=addRenderableWidget(new JEDDropdown(16,59,width-32,title,text,JEDDropdown.local(options),s->{if(s.isEmpty())return justepicdimensions.builder.JEDBuilderFieldInfo.tr("default_value");var id=net.minecraft.resources.ResourceLocation.tryParse(s);if(field.key().equals("world_preset")&&id!=null)return Component.translatableWithFallback("generator."+id.getNamespace()+"."+id.getPath().replace('/','.'),s).copy().append(" ("+s+")");return JEDDropdown.label(s);},s->text=s));
            else{value=new EditBox(font,16,59,width-32,20,title);value.setMaxLength(field.kind()==JEDConfigFields.Kind.ITEM_LIST?JEDConfigFiles.MAX_BYTES:1024);value.setValue(text);value.setResponder(s->text=s);addRenderableWidget(value);setInitialFocus(value);}
            addRenderableWidget(Button.builder(tr("reset"),b->{text=field.text(field.defaultValue());if(choice!=null)choice.setValue(text);else value.setValue(text);}).bounds(16,height-56,width-32,20).build());
            addRenderableWidget(Button.builder(tr("keep_edit"),b->done()).bounds(16,height-30,width/2-20,20).build());
            addRenderableWidget(Button.builder(tr("cancel"),b->onClose()).bounds(width/2+4,height-30,width/2-20,20).build());
        }
        @Override public void onClose(){minecraft.setScreen(JEDConfigScreen.this);}
        @Override public boolean isPauseScreen(){return JEDConfigScreen.this.isPauseScreen();}
        @Override public void tick(){super.tick();JEDConfigClient.timeout();}
        @Override public void render(GuiGraphics g,int x,int y,float p){
            super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,15,0xffffff);
            g.drawString(font,font.plainSubstrByWidth(tr("saved_active",display(pages.get(ownerScope).document.getAsJsonObject("values").get(field.key())),display(active(field.key()))).getString(),width-32),16,39,0xbbcddd);
            var lines=font.split(help(field),width-32);int available=Math.max(0,(height-174)/font.lineHeight);for(int i=0;i<Math.min(lines.size(),available);i++)g.drawString(font,lines.get(i),16,113+i*font.lineHeight,0xdddddd);
            if(y>=110&&y<height-62)g.renderTooltip(font,font.split(help(field),Math.min(420,width-20)),x,y);
            if(!error.isEmpty()){g.drawString(font,font.plainSubstrByWidth(error,width-32),16,height-68,0xffaaaa);if(y>=height-72&&y<height-58)g.renderTooltip(font,font.split(Component.literal(error),width-20),x,y);}
        }
    }
}
