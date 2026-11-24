package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.bundle.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;

/** Ordinary builder session and native review widgets; archive paths stay on the server. */
final class JEDBundleScreen extends JEDBuilderScreen.Sub {
    String filename="dimension_bundle",dimensions,extras="[]",mode="linked",message="";JsonObject review;
    final List<String> available=new ArrayList<>();final Set<String> selected=new LinkedHashSet<>();boolean selecting;int scroll;
    JEDBundleScreen(JEDBuilderScreen parent){super(parent,label("title","Dimension bundles"));dimensions=parent.target;}
    static Component label(String key,String fallback){return Component.translatableWithFallback("justepicdimensions.bundle."+key,fallback);}
    protected void init(){
        var file=input("Bundle filename",8,45,width-16,filename,s->{filename=s;review=null;});file.setTooltip(Tooltip.create(label("filename","Server bundle filename")));
        var dims=button("dimensions",8,77,width/2-12,this::selectDimensions);dims.setMessage(label("select_dimensions","Click to select dimensions").copy().append(selected.isEmpty()?"":" ("+selected.size()+")"));
        var dependencies=button("dependencies",width/2+4,77,width/2-12,()->request("dependencies",false));dependencies.setMessage(label("check_files","Check required files"));dependencies.active=!selected.isEmpty();
        if(selecting){dimensionList();return;}
        var additional=input("Extra dependency paths as a JSON array",8,109,width-16,extras,s->extras=s);additional.setTooltip(Tooltip.create(label("extras_help","Optional: usually leave []. Referenced dependencies are included automatically. Add config-relative paths only for extra files, for example [\"assets/pack/textures/sky/sun.png\"].")));
        var modeButton=button("copy",8,139,width/2-12,()->{mode=mode.equals("linked")?"resolved":"linked";rebuildWidgets();});modeButton.setMessage(label(mode,mode.equals("linked")?"Preserve profile links":"Resolved settings"));
        var export=button("save",width/2+4,139,width/2-12,()->request("export",false));export.setMessage(label("export","Export bundle"));export.active=!selected.isEmpty();
        var preview=button("preview",8,165,width-16,()->request("preview",false));preview.setMessage(label("preview","Preview import"));
        if(!message.isBlank()){var details=button("error",8,190,width-16,()->problem(message));details.setMessage(label("error_details","View full error"));}
        button("back",8,height-30,width-16,this::onClose);
    }
    void selectDimensions(){
        if(selecting){selecting=false;rebuildWidgets();return;}
        var request=new JsonObject();request.addProperty("op","bundle");request.addProperty("bundle_action","catalog");
        JEDBuilderClient.bundleReply=reply->{if(reply.has("error")){problem(reply.get("error").getAsString());return;}available.clear();reply.getAsJsonArray("dimensions").forEach(id->available.add(id.getAsString()));
            if(selected.isEmpty()&&!dimensions.isBlank())for(String id:dimensions.split(","))if(available.contains(id))selected.add(id);
            selected.retainAll(available);syncSelection();selecting=true;scroll=0;minecraft.setScreen(this);};JEDBuilderClient.request(request);
    }
    void syncSelection(){dimensions=String.join(",",selected);review=null;}
    int visibleRows(){return Math.max(1,(height-180)/22);}
    void dimensionList(){
        addRenderableWidget(Checkbox.builder(label("all","ALL"),font).pos(10,104).selected(!available.isEmpty()&&selected.containsAll(available)).onValueChange((box,on)->{selected.clear();if(on)selected.addAll(available);syncSelection();rebuildWidgets();}).build());
        int rows=visibleRows();scroll=Math.max(0,Math.min(scroll,Math.max(0,available.size()-rows)));
        for(int i=scroll;i<Math.min(available.size(),scroll+rows);i++){String id=available.get(i);String shown=font.plainSubstrByWidth(id,Math.max(20,width-48));if(!shown.equals(id))shown+="…";
            addRenderableWidget(Checkbox.builder(Component.literal(shown),font).pos(10,128+(i-scroll)*22).selected(selected.contains(id)).tooltip(Tooltip.create(Component.literal(id))).onValueChange((box,on)->{if(on)selected.add(id);else selected.remove(id);syncSelection();rebuildWidgets();}).build());}
        var up=button("up",8,height-52,36,()->{scroll--;rebuildWidgets();});up.setMessage(Component.literal("↑"));up.active=scroll>0;
        var down=button("down",48,height-52,36,()->{scroll++;rebuildWidgets();});down.setMessage(Component.literal("↓"));down.active=scroll+rows<available.size();
        var done=button("done",88,height-52,width-96,()->{selecting=false;rebuildWidgets();});done.setMessage(Component.translatable("gui.done"));
        button("back",8,height-28,width-16,this::onClose);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){if(selecting&&y>=104){scroll+=(dy<0?1:-1);rebuildWidgets();return true;}return super.mouseScrolled(x,y,dx,dy);}
    public boolean keyPressed(int key,int scan,int modifiers){if(selecting&&key==256){selecting=false;rebuildWidgets();return true;}return super.keyPressed(key,scan,modifiers);}
    void problem(String text){message=text;minecraft.setScreen(new Problem(parent,this,text));}
    private static final class Dependencies extends JEDBuilderScreen.Sub {
        final JEDBundleScreen back;final JsonObject result;
        Dependencies(JEDBuilderScreen parent,JEDBundleScreen back,JsonObject result){super(parent,label("check_files","Check required files"));this.back=back;this.result=result;}
        protected void init(){
            var text=new StringBuilder("These files are included automatically when you export. You do not need to add them to the JSON array.\n\n");
            for(var path:result.getAsJsonArray("included"))text.append("- ").append(path.getAsString()).append('\n');
            if(!result.getAsJsonArray("requirements").isEmpty()){
                text.append("\nExternal or unavailable dependencies\nKeep the supplying mods/resource packs installed. For files absent from your setup, provide them before exporting/importing.\n\n");
                for(var raw:result.getAsJsonArray("requirements")){var item=raw.getAsJsonObject();String kind=item.get("kind").getAsString(),id=item.get("id").getAsString();
                    text.append("- ").append(kind).append(": ").append(id).append('\n');
                    if(kind.equals("client_asset")||kind.equals("resource")){var location=net.minecraft.resources.ResourceLocation.tryParse(id);if(location!=null)text.append("  Config path, if supplied locally: ").append(kind.equals("client_asset")?"assets/":"data/").append(location.getNamespace()).append('/').append(location.getPath()).append('\n');}
                }
            }
            readOnly(text.toString(),Math.max(20,height-80));button("back",8,height-30,width-16,this::onClose);
        }
        public void onClose(){minecraft.setScreen(back);}
    }
    private static final class Problem extends JEDBuilderScreen.Sub {
        final JEDBundleScreen back;final String text;
        Problem(JEDBuilderScreen parent,JEDBundleScreen back,String text){super(parent,label("error","Bundle operation failed"));this.back=back;this.text=text;}
        protected void init(){readOnly(text,Math.max(20,height-80));button("back",8,height-30,width-16,this::onClose);}
        public void onClose(){minecraft.setScreen(back);}
    }
    void request(String action,boolean replace){try{var request=new JsonObject();request.addProperty("op","bundle");request.addProperty("bundle_action",action);request.addProperty("file",filename);request.addProperty("dimensions",dimensions);request.addProperty("mode",mode);request.addProperty("extras",extras);if(review!=null)request.addProperty("token",review.get("token").getAsString());request.addProperty("replace",replace);JEDBuilderClient.bundleReply=this::receive;JEDBuilderClient.request(request);}catch(Exception error){problem(error.getMessage());}}
    void receive(JsonObject reply){if(reply.has("error")){problem(reply.get("error").getAsString());return;}message="";if(reply.get("action").getAsString().equals("dependencies")){minecraft.setScreen(new Dependencies(parent,this,reply));}else if(reply.get("action").getAsString().equals("preview")){review=reply;minecraft.setScreen(new Details(parent,this,reply));}else{review=null;minecraft.setScreen(new Details(parent,this,reply));}}
    public void render(GuiGraphics graphics,int x,int y,float partial){super.render(graphics,x,y,partial);graphics.drawString(font,label("filename","Server bundle filename"),8,33,0xffbbbbbb);graphics.drawString(font,label("dimensions_label","Dimensions to export"),8,65,0xffbbbbbb);if(!selecting)graphics.drawString(font,label("extras","Extra dependency paths as a JSON array"),8,97,0xffbbbbbb);}
    private static final class Details extends JEDBuilderScreen.Sub {
        final JEDBundleScreen back;final JsonObject result;
        Details(JEDBuilderScreen parent,JEDBundleScreen back,JsonObject result){super(parent,"review_details");this.back=back;this.result=result;}
        protected void init(){readOnly(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result),height-100);if(result.get("action").getAsString().equals("preview")){var save=button("save",8,height-55,width-16,()->{boolean conflict=result.getAsJsonArray("changes").asList().stream().anyMatch(row->row.getAsJsonObject().get("status").getAsString().equals("conflict"));if(conflict)minecraft.setScreen(new ConfirmScreen(yes->{minecraft.setScreen(this);if(yes)back.request("import",true);},label("replace","Replace the conflicting files?"),label("replace_help","The preview lists every affected file. Previous files will be backed up. Active dimensions remain unchanged.")));else back.request("import",false);});save.setMessage(label("import","Import reviewed files"));}button("back",8,height-30,width-16,this::onClose);}
        public void onClose(){minecraft.setScreen(back);}
    }
}
