package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import justepicdimensions.builder.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

abstract class JEDWorldgenPage extends Screen {
    JEDWorldgenPage(Component title){super(title);}
    <T extends AbstractWidget> T widget(T widget){return addRenderableWidget(widget);}
    public boolean isPauseScreen(){return false;}
}

/** Typed views over complete detached native JSON. Unknown fields survive form edits. */
final class JEDWorldgenScreen extends JEDWorldgenPage {
    final JEDBuilderScreen builder;final Screen back;final JsonObject initial;JsonObject documents;String error="";int page;
    private record Item(String text,Runnable action){}
    private record Field(String path,String label,String kind,String choices){Field(String path,String label,String kind){this(path,label,kind,"");}}
    private final List<Item> items=new ArrayList<>();
    JEDWorldgenScreen(JEDBuilderScreen builder,Screen back){super(text("Worldgen editor"));this.builder=builder;this.back=back;documents=builder.edit.documents();initial=documents.deepCopy();}
    static Component text(String label){return Component.translatableWithFallback("justepicdimensions.worldgen.editor."+label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("_$",""),label);}
    private JsonElement value(String document,String path){return JEDBuilderDraft.find(documents.get(document),path);}
    private void set(String document,String path,JsonElement value){if(!documents.has(document))documents.add(document,new JsonObject());JEDBuilderDraft.set(documents.getAsJsonObject(document),path,value);}
    private String generator(){var type=value("dimension","generator.type");return type==null?"":type.getAsString();}
    private Button button(JEDWorldgenPage screen,String label,int x,int y,int w,Runnable action){return screen.widget(Button.builder(text(label),b->{if(JEDBuilderClient.busy)return;try{action.run();}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();}}).bounds(x,y,w,20).build());}
    private void show(Screen screen){minecraft.setScreen(screen);}
    @Override protected void init(){
        items.clear();items.add(new Item("Generator settings",this::settings));items.add(new Item("Biomes and distribution",this::biomes));items.add(new Item("Flat layers",this::layers));items.add(new Item("Structures",this::structures));items.add(new Item("Build heights",()->reference(this,"dimension","type","dimension_type",(data,save)->form(this,"Build heights",data,List.of(new Field("min_y","Minimum Y","integer"),new Field("height","Total height","integer")),List.of(),save))));items.add(new Item("Biome map preview",()->preview(this)));
        int count=Math.max(1,(height-104)/24);page=Math.min(page,Math.max(0,(items.size()-1)/count));
        for(int i=page*count;i<Math.min(items.size(),(page+1)*count);i++){var item=items.get(i);button(this,item.text,8,38+(i-page*count)*24,width-16,item.action);}
        button(this,"Previous",8,height-62,90,()->{page=Math.max(0,page-1);rebuildWidgets();});button(this,"Next",102,height-62,90,()->{if((page+1)*count<items.size())page++;rebuildWidgets();});
        button(this,"Keep Local Edits",8,height-28,width/2-12,()->{for(var entry:documents.entrySet())if(!Objects.equals(initial.get(entry.getKey()),entry.getValue()))builder.inputs.document(builder.edit,entry.getKey(),entry.getValue().toString());builder.inputs.inspect(builder.edit);show(builder);});
        button(this,"Cancel",width/2+4,height-28,width/2-12,this::onClose);
    }
    @Override public void onClose(){if(JEDBuilderClient.busy)return;if(!documents.equals(initial))show(new ConfirmScreen(discard->show(discard?back:this),text("Discard worldgen edits?"),text("These edits have not been added to the local draft.")));else show(back);}
    @Override public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);g.drawString(font,font.plainSubstrByWidth(error,width-16),8,height-78,0xffffaaaa);}
    private void request(String action,JsonObject fields,Consumer<JsonObject> reply){if(JEDBuilderClient.busy)return;fields.addProperty("op","worldgen");fields.addProperty("worldgen_action",action);fields.add("documents",documents.deepCopy());fields.addProperty("target",builder.target);JEDBuilderClient.worldgenReply=result->{if(result.has("error")){error=result.get("error").getAsString();return;}try{reply.accept(result);}catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();}};JEDBuilderClient.request(fields);}
    private void requestResource(String resource,Consumer<JsonObject> reply){var request=new JsonObject();request.addProperty("resource",resource);request("resource",request,r->reply.accept(r.getAsJsonObject("document")));}
    private void reference(Screen returnTo,String document,String path,String directory,BiConsumer<JsonObject,Consumer<JsonObject>> editor){
        var selected=value(document,path);if(selected==null)throw new IllegalArgumentException("Choose terrain or a resource first");if(selected.isJsonObject()){editor.accept(selected.getAsJsonObject().deepCopy(),next->set(document,path,next));return;}
        String resource=file(selected.getAsString(),directory);var choices=new ArrayList<Item>();
        for(boolean shared:new boolean[]{false,true})choices.add(new Item(shared?"Edit shared resource — affects every reference":"Edit dimension-specific copy (default)",()->requestResource(resource,current->{var original=current.deepCopy();editor.accept(current,next->{if(next.equals(original))return;String destination=shared?resource:file(unique(directory),directory);documents.add(destination,next.deepCopy());set(document,path,new JsonPrimitive(entry(destination,directory)));});})));
        menu(returnTo,"Resource editing",choices);
    }
    private static String file(String id,String directory){var key=ResourceLocation.parse(id);return key.getNamespace()+":"+directory+"/"+key.getPath()+".json";}
    private static String entry(String file,String directory){var key=ResourceLocation.parse(file);return key.getNamespace()+":"+key.getPath().substring(directory.length()+1,key.getPath().length()-5);}
    private String unique(String directory){if(directory.equals("worldgen/structure_set")){var target=ResourceLocation.parse(builder.target);return "justepicdimensions:builder/structure_sets/"+target.getNamespace()+"/"+target.getPath()+"/"+UUID.randomUUID().toString().replace("-","");}return builder.target+"_"+directory.substring(directory.lastIndexOf('/')+1)+"_"+UUID.randomUUID().toString().replace("-","");}
    private void settings(){if(!generator().equals("minecraft:noise"))throw new IllegalArgumentException("This generator retains its Advanced JSON controls");String path="generator.settings";reference(this,"dimension",path,"worldgen/noise_settings",(data,save)->form(this,"Generator settings",data,List.of(new Field("sea_level","Sea level","integer"),new Field("default_block.Name","Base terrain block","resource","minecraft:block"),new Field("default_fluid.Name","Default fluid","resource","minecraft:block")),List.of(),save));}
    private void layers(){if(!Set.of("minecraft:flat","justepicdimensions:flat").contains(generator()))throw new IllegalArgumentException("Flat layers require a flat generator; other generators retain Advanced JSON");var settings=value("dimension","generator.settings");if(settings==null||!settings.isJsonObject())throw new IllegalArgumentException("This flat generator has no editable settings");var data=settings.getAsJsonObject().deepCopy();rows(this,"Flat layers (bottom to top)",array(data,"layers"),row->form(null,"Flat layer",row,List.of(new Field("height","Layer thickness","integer"),new Field("block","Block","resource","minecraft:block")),List.of(),null),next->{data.add("layers",next);set("dimension","generator.settings",data);});}
    private void biomes(){
        if(!Set.of("minecraft:noise","minecraft:flat","justepicdimensions:flat").contains(generator()))throw new IllegalArgumentException("This generator retains its Advanced JSON controls");
        if(generator().equals("minecraft:flat")){var options=new ArrayList<Item>();options.add(new Item("Single biome",()->form(this,"Flat biome",documents.getAsJsonObject("dimension"),List.of(new Field("generator.settings.biome","Biome","resource","minecraft:worldgen/biome")),List.of(),next->documents.add("dimension",next))));options.add(new Item("Use JED flat for biome distribution",()->show(new ConfirmScreen(yes->{show(this);if(yes){set("dimension","generator.type",new JsonPrimitive("justepicdimensions:flat"));biomes();}},text("Use configurable flat biomes?"),text("Keeps the flat settings and enables JED's existing flat generator.")))));menu(this,"Flat biomes",options);return;}
        var selected=value("dimension","generator.biome_source");if(selected!=null&&!selected.isJsonObject())throw new IllegalArgumentException("This addon biome source retains Advanced JSON");var data=selected==null?new JsonObject():selected.getAsJsonObject().deepCopy();if(selected==null&&generator().equals("justepicdimensions:flat")){var biome=value("dimension","generator.settings.biome");if(biome!=null){data.addProperty("type","minecraft:fixed");data.add("biome",biome.deepCopy());}}var options=new ArrayList<Item>();options.add(new Item("Edit current distribution",()->source(this,data,next->{if(selected!=null||!next.equals(data))set("dimension","generator.biome_source",next);})));
        for(String type:List.of("minecraft:fixed","minecraft:multi_noise","justepicdimensions:weighted_regions","minecraft:checkerboard","minecraft:the_end"))options.add(new Item(switch(type){case "minecraft:fixed"->"Choose a single biome";case "minecraft:multi_noise"->"Choose native climate ranges";case "minecraft:checkerboard"->"Choose native checkerboard";case "minecraft:the_end"->"Choose native End distribution";default->"Choose weighted regions";},()->show(new ConfirmScreen(yes->{if(!yes){biomes();return;}var next=new JsonObject();next.addProperty("type",type);if(type.equals("justepicdimensions:weighted_regions")){next.addProperty("shape","irregular");next.add("entries",new JsonArray());next.add("height_bands",new JsonArray());}else if(type.equals("minecraft:multi_noise")||type.equals("minecraft:checkerboard"))next.add("biomes",new JsonArray());source(this,next,result->set("dimension","generator.biome_source",result));},text("Replace biome distribution?"),text("The other generator settings remain in the draft.")))));
        menu(this,"Biome distribution",options);
    }
    private void source(Screen returnTo,JsonObject data,Consumer<JsonObject> saved){
        String type=data.has("type")?data.get("type").getAsString():"";
        if(type.equals("minecraft:the_end")){form(returnTo,"Native End distribution (no parameters)",data,List.of(),List.of(),saved);return;}
        if(type.equals("minecraft:checkerboard")){form(returnTo,"Native checkerboard",data,List.of(new Field("scale","Native checkerboard scale","integer")),List.of(new FormAction("Checkerboard biomes",f->rows(f,"Checkerboard biomes",array(f.data,"biomes"),row->form(null,"Checkerboard biome",row,List.of(new Field("id","Biome","resource","minecraft:worldgen/biome")),List.of(),null),next->f.data.add("biomes",next)))),saved);return;}
        if(type.equals("minecraft:fixed")){form(returnTo,"Single biome",data,List.of(new Field("biome","Biome","resource","minecraft:worldgen/biome")),List.of(),saved);return;}
        if(type.equals("minecraft:multi_noise")&&data.has("preset")){var request=new JsonObject();request.addProperty("resource",data.get("preset").getAsString());request("climate",request,reply->{var expanded=data.deepCopy();expanded.remove("preset");reply.getAsJsonObject("document").entrySet().forEach(e->expanded.add(e.getKey(),e.getValue()));var before=expanded.deepCopy();source(returnTo,expanded,next->saved.accept(next.equals(before)?data.deepCopy():next));});return;}
        if(type.equals("justepicdimensions:weighted_regions")){
            form(returnTo,"Weighted biome regions",data,List.of(new Field("region_size","Region size (blocks)","integer"),new Field("shape","Region shape","choice","irregular grid")),List.of(
                new FormAction("Biome weights",f->weights(f,f.data,"entries")),
                new FormAction("Height bands",f->rows(f,"Height bands",array(f.data,"height_bands"),row->form(null,"Height band",row,List.of(new Field("min_y","Minimum Y (inclusive)","integer"),new Field("max_y","Maximum Y (exclusive)","integer")),List.of(new FormAction("Biome weights",child->weights(child,child.data,"entries"))),null),next->f.data.add("height_bands",next)))
            ),saved);return;
        }
        if(type.equals("minecraft:multi_noise")){
            form(returnTo,"Native climate distribution",data,List.of(),List.of(new FormAction("Climate entries",f->rows(f,"Climate entries",array(f.data,"biomes"),row->form(null,"Climate entry",row,List.of(
                new Field("biome","Biome","resource","minecraft:worldgen/biome"),new Field("parameters.temperature","Temperature","range"),new Field("parameters.humidity","Humidity","range"),new Field("parameters.continentalness","Continentalness","range"),new Field("parameters.erosion","Erosion","range"),new Field("parameters.depth","Depth","range"),new Field("parameters.weirdness","Weirdness","range"),new Field("parameters.offset","Offset","decimal")
            ),List.of(),null),next->f.data.add("biomes",next)))),saved);return;
        }
        throw new IllegalArgumentException("This biome-source type retains its Advanced JSON editor");
    }
    private void weights(Form screen,JsonObject data,String key){rows(screen,"Biome weights",array(data,key),row->form(null,"Biome weight",row,List.of(new Field("biome","Biome","resource","minecraft:worldgen/biome"),new Field("weight","Weight","integer")),List.of(),null),next->data.add(key,next));}
    private void ensureSets(Consumer<JsonArray> ready){var selected=value("properties","generation.structure_sets");if(selected!=null){ready.accept(selected.getAsJsonArray().deepCopy());return;}request("structure_defaults",new JsonObject(),r->ready.accept(r.getAsJsonArray("sets")));}
    private void structures(){
        if(!Set.of("minecraft:noise","minecraft:flat","justepicdimensions:flat").contains(generator()))throw new IllegalArgumentException("This generator retains its Advanced JSON controls");
        var options=new ArrayList<Item>();
        options.add(new Item("Selected native structure sets",()->ensureSets(sets->rows(this,"Structure sets",sets,row->form(null,"Structure set",row,List.of(new Field("id","Structure set","resource","minecraft:worldgen/structure_set")),List.of(new FormAction("Edit definition",f->{var id=f.data.get("id");if(id==null)throw new IllegalArgumentException("Choose a structure set");String name=file(id.getAsString(),"worldgen/structure_set");requestResource(name,data->setForm(f,name,data,false,next->f.data.addProperty("id",entry(next,"worldgen/structure_set"))));})),null),array->set("properties","generation.structure_sets",array)))));
        options.add(new Item("Add authored building (.nbt / .schem)",this::authored));
        options.add(new Item("Use generator's default structure sets",()->{var properties=documents.getAsJsonObject("properties");if(properties!=null&&properties.has("generation"))properties.getAsJsonObject("generation").remove("structure_sets");show(this);}));
        menu(this,"Structure generation",options);
    }
    private void setForm(Screen returnTo,String name,JsonObject data,boolean fresh,Consumer<String> renamed){
        String type=data.has("placement")&&data.get("placement").isJsonObject()&&data.getAsJsonObject("placement").has("type")?data.getAsJsonObject("placement").get("type").getAsString():"";var fields=new ArrayList<Field>();if(type.equals("minecraft:random_spread")){fields.add(new Field("placement.spacing","Spacing (chunks)","integer"));fields.add(new Field("placement.separation","Separation (chunks)","integer"));}fields.add(new Field("placement.frequency","Placement chance (0–1)","decimal"));
        resourceForm(returnTo,name,data,"worldgen/structure_set",fresh,fields,List.of(new FormAction("Structure entries",f->rows(f,"Structure entries",array(f.data,"structures"),row->form(null,"Structure entry",row,List.of(new Field("structure","Structure definition","resource","minecraft:worldgen/structure"),new Field("weight","Weight","integer")),List.of(new FormAction("Edit structure",child->{var id=child.data.get("structure");if(id==null)throw new IllegalArgumentException("Choose a structure");String resource=file(id.getAsString(),"worldgen/structure");requestResource(resource,structure->structureForm(child,resource,structure,false,newName->child.data.addProperty("structure",entry(newName,"worldgen/structure"))));})),null),next->f.data.add("structures",next)))),renamed);
    }
    private void authored(){
        var data=new JsonObject();data.addProperty("type","justepicdimensions:authored");data.add("spawn_overrides",new JsonObject());data.addProperty("step","surface_structures");data.addProperty("terrain_adaptation","none");var any=new JsonObject();any.addProperty("type","neoforge:any");data.add("biomes",any);data.addProperty("height_mode","surface");data.add("placement",new JsonObject());String name=file(unique("worldgen/structure"),"worldgen/structure");
        structureForm(this,name,data,true,result->ensureSets(sets->{String setId=unique("worldgen/structure_set"),setName=file(setId,"worldgen/structure_set");var set=new JsonObject();var placement=new JsonObject();placement.addProperty("type","minecraft:random_spread");placement.addProperty("salt",setId.hashCode()&Integer.MAX_VALUE);set.add("placement",placement);var entries=new JsonArray();var item=new JsonObject();item.addProperty("structure",entry(result,"worldgen/structure"));item.addProperty("weight",1);entries.add(item);set.add("structures",entries);setForm(this,setName,set,true,saved->{sets.add(entry(saved,"worldgen/structure_set"));set("properties","generation.structure_sets",sets);});}));
    }
    private void structureForm(Screen returnTo,String name,JsonObject data,boolean fresh,Consumer<String> renamed){
        var fields=new ArrayList<Field>();fields.add(new Field("terrain_adaptation","Terrain adaptation","choice","none bury beard_thin beard_box encapsulate"));
        boolean authored=data.has("type")&&data.get("type").getAsString().equals("justepicdimensions:authored");
        if(authored){fields.add(new Field("placement.asset","Building file","resource","justepicdimensions:structures"));fields.add(new Field("height_mode","Height placement","choice","surface underground cave_floor ceiling fixed range"));fields.add(new Field("min_y","Fixed / minimum Y","integer"));fields.add(new Field("max_y","Maximum Y","integer"));fields.add(new Field("placement.rotation","Rotation","choice","none clockwise_90 clockwise_180 counterclockwise_90"));fields.add(new Field("placement.mirror","Mirror","choice","none left_right front_back"));fields.add(new Field("placement.centered","Center horizontally","choice","false true"));fields.add(new Field("placement.paste_biomes","Paste schematic biomes","choice","false true"));}
        var actions=new ArrayList<FormAction>();actions.add(new FormAction("Biome restrictions",this::biomeFilter));if(authored)actions.add(new FormAction("Placement offsets",this::offset));resourceForm(returnTo,name,data,"worldgen/structure",fresh,fields,actions,renamed);
    }
    private void offset(Form parent){
        var raw=JEDBuilderDraft.find(parent.data,"placement.offset");var data=new JsonObject();var array=raw==null?new JsonArray():raw.getAsJsonArray();for(int i=0;i<3;i++)if(i<array.size())data.add(List.of("x","y","z").get(i),array.get(i));
        form(parent,"Placement offsets",data,List.of(new Field("x","X","integer"),new Field("y","Y","integer"),new Field("z","Z","integer")),List.of(),next->{var values=new JsonArray();for(String axis:List.of("x","y","z")){if(!next.has(axis))throw new IllegalArgumentException("Enter all three offsets");values.add(next.get(axis));}JEDBuilderDraft.set(parent.data,"placement.offset",values);});
    }
    private void biomeFilter(Form parent){
        var selected=parent.data.get("biomes");var values=new JsonArray();
        if(selected!=null){
            if(selected.isJsonPrimitive())values.add(selected);
            else if(selected.isJsonArray())values=selected.getAsJsonArray().deepCopy();
            else if(selected.isJsonObject()&&selected.getAsJsonObject().has("type")&&selected.getAsJsonObject().get("type").getAsString().equals("neoforge:or")){
                for(var item:selected.getAsJsonObject().getAsJsonArray("values"))if(item.isJsonArray())item.getAsJsonArray().forEach(values::add);else values.add(item);
            }else if(!selected.isJsonObject()||!selected.getAsJsonObject().has("type")||!selected.getAsJsonObject().get("type").getAsString().equals("neoforge:any"))throw new IllegalArgumentException("This compound biome set retains Advanced JSON");
        }
        var unchanged=values.deepCopy();
        rows(parent,"Biome restrictions",values,row->form(null,"Biome restriction",row,List.of(new Field("kind","Match","choice","biome tag"),new Field("id","Biome or tag","biome_match")),List.of(),null),next->{
            if(next.equals(unchanged))return;
            if(next.isEmpty()){var any=new JsonObject();any.addProperty("type","neoforge:any");parent.data.add("biomes",any);}
            else{var or=new JsonObject();or.addProperty("type","neoforge:or");var sets=new JsonArray();for(var element:next){if(element.getAsString().startsWith("#"))sets.add(element);else{var singleton=new JsonArray();singleton.add(element);sets.add(singleton);}}or.add("values",sets);parent.data.add("biomes",or);}
        });
    }
    private void resourceForm(Screen returnTo,String name,JsonObject data,String directory,boolean fresh,List<Field> fields,List<FormAction> actions,Consumer<String> renamed){
        var original=data.deepCopy();var form=new Form(returnTo,"Edit "+directory.substring(directory.lastIndexOf('/')+1),data,fields,actions,next->{var scope=next.remove("$jed_shared");if(next.equals(original)&&!fresh)return;String destination=name;if(!fresh&&(scope==null||!scope.getAsBoolean()))destination=file(unique(directory),directory);documents.add(destination,next.deepCopy());renamed.accept(destination);});form.scope=!fresh;show(form);
    }
    private record FormAction(String label,Consumer<Form> open){}
    private Form form(Screen returnTo,String title,JsonObject data,List<Field> fields,List<FormAction> actions,Consumer<JsonObject> saved){var form=new Form(returnTo,title,data,fields,actions,saved);if(returnTo!=null)show(form);return form;}
    private void menu(Screen back,String title,List<Item> items){form(back,title,new JsonObject(),List.of(),items.stream().map(item->new FormAction(item.text,f->item.action.run())).toList(),j->{});}
    private static JsonArray array(JsonObject data,String name){var value=data.get(name);return value==null?new JsonArray():value.getAsJsonArray().deepCopy();}
    private void rows(Screen back,String title,JsonArray data,Function<JsonObject,Form> editor,Consumer<JsonArray> saved){show(new Rows(back,title,data,editor,saved));}
    private final class Form extends JEDWorldgenPage {
        Screen back;final JsonObject original,data;final JsonObject documentSnapshot=documents.deepCopy();final List<Field> fields;final List<FormAction> actions;Consumer<JsonObject> saved;final Map<String,String> raw=new HashMap<>(),invalid=new HashMap<>();int page;boolean scope,shared;String problem="";
        Form(Screen back,String label,JsonObject data,List<Field> fields,List<FormAction> actions,Consumer<JsonObject> saved){super(text(label));this.back=back;this.original=data.deepCopy();this.data=data.deepCopy();this.fields=fields;this.actions=actions;this.saved=saved;}
        protected void init(){
            int count=Math.max(1,(height-112)/38),total=fields.size()+actions.size()+(scope?1:0);page=Math.min(page,Math.max(0,(total-1)/count));
            for(int row=page*count;row<Math.min(total,(page+1)*count);row++){
                int y=49+(row-page*count)*38,index=row-(scope?1:0);
                if(scope&&row==0){widget(new JEDDropdown(8,y,width-16,text("Save scope"),Boolean.toString(shared),JEDDropdown.local(List.of("false","true")),v->text(v.equals("true")?"Shared resource — affects every reference":"Dimension-specific copy (default)"),v->shared=Boolean.parseBoolean(v)));continue;}
                if(index>=fields.size()){var action=actions.get(index-fields.size());button(this,action.label,8,y,width-16,()->action.open.accept(this));continue;}
                var field=fields.get(index);var current=JEDBuilderDraft.find(data,field.path);String value=raw.containsKey(field.path)?raw.get(field.path):current==null?"":current.isJsonPrimitive()?current.getAsString():current.toString();
                if(field.kind.equals("choice")||field.kind.equals("resource")||field.kind.equals("biome_match")){
                    var options=field.kind.equals("choice")?JEDDropdown.local(List.of(field.choices.split(" "))):JEDChoiceSources.server(builder,field.kind.equals("biome_match")?(data.has("kind")&&data.get("kind").getAsString().equals("tag")?"tags:minecraft:worldgen/biome":"minecraft:worldgen/biome"):field.choices,()->documents);
                    widget(new JEDDropdown(8,y,width-16,text(field.label),value,options,v->field.path.equals("height_mode")?text(switch(v){case "underground"->"Buried";case "cave_floor"->"Cave floor";case "ceiling"->"Hanging from a ceiling";case "fixed"->"Fixed Y";case "range"->"Y range";default->"Surface";}):field.path.equals("shape")?text(v.equals("grid")?"Square grid":"Irregular regions"):Component.literal(v),v->{JEDBuilderDraft.set(data,field.path,field.kind.equals("choice")&&Set.of("true","false").contains(v)?new JsonPrimitive(Boolean.parseBoolean(v)):new JsonPrimitive(v));raw.remove(field.path);invalid.remove(field.path);if(field.path.equals("kind"))rebuildWidgets();}));
                }else if(field.kind.equals("range")){
                    JsonArray range=current!=null&&current.isJsonArray()?current.getAsJsonArray():null;
                    for(int edge=0;edge<2;edge++){final int bound=edge;String key=field.path+"."+edge;String v=raw.getOrDefault(key,range!=null&&range.size()==2?range.get(edge).getAsString():current==null?"":current.getAsString());
                        box(this,8+edge*(width-16)/2,y,(width-16)/2-4,v,input->{raw.put(key,input);try{var a=new JsonArray();for(int e=0;e<2;e++){String n=e==bound?input:raw.getOrDefault(field.path+"."+e,range!=null&&range.size()==2?range.get(e).getAsString():current==null?"":current.getAsString());a.add(new java.math.BigDecimal(n));}JEDBuilderDraft.set(data,field.path,a);invalid.remove(field.path);}catch(Exception ex){invalid.put(field.path,"Enter both range endpoints");}});
                    }
                }else box(this,8,y,width-16,value,input->{raw.put(field.path,input);try{JsonElement next=field.kind.equals("integer")?new JsonPrimitive(Integer.parseInt(input)):field.kind.equals("decimal")?new JsonPrimitive(new java.math.BigDecimal(input)):new JsonPrimitive(input);JEDBuilderDraft.set(data,field.path,next);invalid.remove(field.path);}catch(Exception e){invalid.put(field.path,"Invalid "+field.label);}});
            }
            button(this,"Previous",8,height-62,90,()->{page=Math.max(0,page-1);rebuildWidgets();});button(this,"Next",102,height-62,90,()->{if((page+1)*count<total)page++;rebuildWidgets();});
            button(this,"Keep Local Edits",8,height-28,width/2-12,()->{if(!invalid.isEmpty()){problem=invalid.values().iterator().next();return;}if(saved!=null){var next=data.deepCopy();if(scope)next.addProperty("$jed_shared",shared);saved.accept(next);}if(minecraft.screen==this)show(back==null?JEDWorldgenScreen.this:back);});button(this,"Cancel",width/2+4,height-28,width/2-12,this::onClose);
        }
        public void onClose(){if(JEDBuilderClient.busy)return;if(!data.equals(original)||!invalid.isEmpty()||!documents.equals(documentSnapshot))show(new ConfirmScreen(discard->{if(discard){documents=documentSnapshot.deepCopy();show(back==null?JEDWorldgenScreen.this:back);}else show(this);},text("Discard form edits?"),text("Your draft remains unchanged.")));else show(back==null?JEDWorldgenScreen.this:back);}
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);int count=Math.max(1,(height-112)/38);for(int row=page*count;row<Math.min(fields.size()+(scope?1:0),(page+1)*count);row++){String label=scope&&row==0?"Save scope":fields.get(row-(scope?1:0)).label;g.drawString(font,font.plainSubstrByWidth(text(label).getString(),width-16),8,38+(row-page*count)*38,0xffbbbbbb);}g.drawString(font,font.plainSubstrByWidth(problem.isEmpty()?error:problem,width-16),8,height-78,0xffffaaaa);}
    }
    private void box(JEDWorldgenPage screen,int x,int y,int w,String value,Consumer<String> responder){var box=new EditBox(font,x,y,w,20,text("Value"));box.setMaxLength(512);box.setValue(value);box.setResponder(responder);screen.widget(box);}
    private final class Rows extends JEDWorldgenPage {
        final Screen back;final JsonArray original,data;final JsonObject documentSnapshot=documents.deepCopy();final Function<JsonObject,Form> editor;final Consumer<JsonArray> saved;int page;
        Rows(Screen back,String title,JsonArray data,Function<JsonObject,Form> editor,Consumer<JsonArray> saved){super(text(title));this.back=back;this.original=data.deepCopy();this.data=data.deepCopy();this.editor=editor;this.saved=saved;}
        private boolean ids(){return title.getString().equals(text("Checkerboard biomes").getString())||title.getString().equals(text("Structure sets").getString())||title.getString().equals(text("Biome restrictions").getString());}
        private JsonObject object(JsonElement value){if(value.isJsonObject())return value.getAsJsonObject().deepCopy();var row=new JsonObject();String id=value.getAsString();row.addProperty("id",id.startsWith("#")?id.substring(1):id);if(title.getString().equals(text("Biome restrictions").getString()))row.addProperty("kind",id.startsWith("#")?"tag":"biome");return row;}
        private JsonElement element(JsonObject row){if(!ids())return row;String id=row.has("id")?row.get("id").getAsString():"";if(id.isEmpty())throw new IllegalArgumentException("Choose an ID");return new JsonPrimitive(row.has("kind")&&row.get("kind").getAsString().equals("tag")?"#"+id:id);}
        private void edit(int index){var row=index<0?new JsonObject():object(data.get(index));var form=editor.apply(row);form.back=this;form.saved=next->{if(index<0)data.add(element(next));else data.set(index,element(next));};show(form);}
        protected void init(){
            int count=Math.max(1,(height-136)/24);page=Math.min(page,Math.max(0,(data.size()-1)/count));
            for(int i=page*count;i<Math.min(data.size(),(page+1)*count);i++){int index=i,y=38+(i-page*count)*24;var row=data.get(i);String label=(i+1)+". "+(row.isJsonPrimitive()?row.getAsString():row.toString());var edit=button(this,font.plainSubstrByWidth(label,width-132),8,y,width-132,()->edit(index));edit.setTooltip(Tooltip.create(Component.literal(label)));button(this,"↑",width-120,y,28,()->{if(index>0){var previous=data.get(index-1);data.set(index-1,data.get(index));data.set(index,previous);rebuildWidgets();}});button(this,"↓",width-90,y,28,()->{if(index+1<data.size()){var next=data.get(index+1);data.set(index+1,data.get(index));data.set(index,next);rebuildWidgets();}});button(this,"−",width-60,y,52,()->{data.remove(index);rebuildWidgets();});}
            button(this,"Add",8,height-86,80,()->edit(-1));button(this,"Previous",8,height-62,90,()->{page=Math.max(0,page-1);rebuildWidgets();});button(this,"Next",102,height-62,90,()->{if((page+1)*count<data.size())page++;rebuildWidgets();});button(this,"Keep Local Edits",8,height-28,width/2-12,()->{saved.accept(data.deepCopy());if(minecraft.screen==this)show(back);});button(this,"Cancel",width/2+4,height-28,width/2-12,this::onClose);
        }
        public void onClose(){if(JEDBuilderClient.busy)return;if(!data.equals(original)||!documents.equals(documentSnapshot))show(new ConfirmScreen(discard->{if(discard){documents=documentSnapshot.deepCopy();show(back);}else show(this);},text("Discard list edits?"),text("Your draft remains unchanged.")));else show(back);}
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);g.drawString(font,font.plainSubstrByWidth(error,width-16),8,height-99,0xffffaaaa);}
    }
    private void preview(Screen back){request("preview_defaults",new JsonObject(),r->show(new Preview(back,r.get("seed").getAsString())));}
    private final class Preview extends JEDWorldgenPage {
        final Screen back;final Map<String,String> values=new LinkedHashMap<>();JsonObject result;String problem="";
        Preview(Screen back,String seed){super(text("Biome map preview"));this.back=back;values.put("seed",seed);values.put("x","0");values.put("y","64");values.put("z","0");values.put("span","2048");}
        protected void init(){int i=0;for(var entry:values.entrySet()){String key=entry.getKey();int x=8+(i%3)*(width-16)/3,y=39+(i/3)*35;box(this,x,y,(width-16)/3-4,entry.getValue(),v->values.put(key,v));i++;}button(this,"Refresh preview",8,109,130,()->{try{var input=new JsonObject();for(var entry:values.entrySet())if(entry.getKey().equals("seed"))input.addProperty(entry.getKey(),entry.getValue());else input.addProperty(entry.getKey(),Integer.parseInt(entry.getValue()));request("preview",input,r->{result=r.getAsJsonObject("preview");problem="";});}catch(Exception e){problem=e.getMessage();}});button(this,"Back",width-98,height-28,90,this::onClose);}
        public void onClose(){if(!JEDBuilderClient.busy)show(back);}
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);int i=0;for(var key:values.keySet()){g.drawString(font,text(key),8+(i%3)*(width-16)/3,28+(i/3)*35,0xffbbbbbb);i++;}if(result!=null){int n=result.get("resolution").getAsInt(),size=Math.max(1,Math.min((width-16)/n,(height-175)/n)),left=(width-n*size)/2,top=131;var palette=result.getAsJsonArray("palette");var pixels=result.getAsJsonArray("pixels");for(int dz=0;dz<n;dz++)for(int dx=0;dx<n;dx++){int index=pixels.get(dz*n+dx).getAsInt(),hash=palette.get(index).getAsString().hashCode(),color=0xff000000|0x404040|(hash&0xbfbfbf);g.fill(left+dx*size,top+dz*size,left+(dx+1)*size,top+(dz+1)*size,color);}if(x>=left&&x<left+n*size&&y>=top&&y<top+n*size){int index=pixels.get(((y-top)/size)*n+(x-left)/size).getAsInt();g.renderTooltip(font,Component.literal(palette.get(index).getAsString()),x,y);}}g.drawString(font,font.plainSubstrByWidth(problem.isEmpty()?error:problem,width-16),8,height-43,0xffffaaaa);}
    }
}
