package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.JEDText;
import justepicdimensions.builder.*;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;

/** Variable values use ordinary inputs; this screen builds their arrays, never asks for JSON syntax. */
final class JEDStructuredFieldScreen extends Screen {
    final JEDBuilderScreen parent;final JEDBuilderFields.Field field;final JsonElement original;final List<String> values=new ArrayList<>();final List<String> initial;final List<EditBox> boxes=new ArrayList<>();String entry="",error="";int page,selected=-1;
    static boolean supports(JEDBuilderFields.Field f){return Set.of("brightness","vector","offset","commands","ids").contains(JEDBuilderFieldInfo.jsonType(f));}
    static boolean grouped(JEDBuilderFields.Field f,JEDBuilderEdit edit){if(f.path().endsWith(".gamerules"))return true;if(!f.path().equals("provider.settings"))return false;var type=edit.value("properties","provider.type");return type==null||Set.of("jed_surface","jed_hell","jed_end","justepicdimensions:surface","justepicdimensions:base","justepicdimensions:nether","justepicdimensions:end").contains(type.getAsString());}
    JEDStructuredFieldScreen(JEDBuilderScreen parent,JEDBuilderFields.Field field){
        super(Component.literal(field.label()));this.parent=parent;this.field=field;var current=parent.edit.value(field.document(),field.path());original=current==null?null:current.deepCopy();String shape=JEDBuilderFieldInfo.jsonType(field);
        if(current!=null&&current.isJsonArray())for(var value:current.getAsJsonArray())values.add(value.isJsonPrimitive()?value.getAsString():value.toString());
        if(shape.equals("brightness"))while(values.size()<16)values.add(Float.toString(values.size()/15f));
        if(shape.equals("vector")||shape.equals("offset")){if(current!=null&&current.isJsonPrimitive()){values.clear();for(int i=0;i<3;i++)values.add(current.getAsString());}while(values.size()<3)values.add(shape.equals("vector")&&field.path().endsWith("scale")?"1":"0");}
        initial=List.copyOf(values);
    }
    Button button(Component text,int x,int y,int width,Runnable action){return addRenderableWidget(Button.builder(text,b->{try{action.run();}catch(Exception e){error=JEDText.error(e).getString();}}).bounds(x,y,width,20).build());}
    protected void init(){
        boxes.clear();String shape=JEDBuilderFieldInfo.jsonType(field);boolean numeric=Set.of("brightness","vector","offset").contains(shape);
        if(numeric){int columns=shape.equals("brightness")?4:3;for(int i=0;i<values.size();i++){int index=i,x=8+(i%columns)*(width-16)/columns,y=66+(i/columns)*35;var box=new EditBox(font,x,y,(width-16)/columns-4,20,Component.literal(shape.equals("brightness")?Integer.toString(i):List.of("X","Y","Z").get(i)));box.setMaxLength(128);box.setValue(values.get(i));box.setResponder(s->{values.set(index,s);error="";});addRenderableWidget(box);boxes.add(box);}}
        else{
            int count=Math.max(1,(height-151)/22);page=Math.min(page,Math.max(0,(values.size()-1)/count));for(int i=page*count;i<Math.min(values.size(),(page+1)*count);i++){int index=i;button(Component.literal((selected==i?"> ":"")+values.get(i)),8,47+(i-page*count)*22,width-82,()->{selected=index;entry=values.get(index);rebuildWidgets();});button(Component.literal("−"),width-68,47+(i-page*count)*22,60,()->{values.remove(index);selected=-1;entry="";rebuildWidgets();});}
            if(shape.equals("ids"))addRenderableWidget(new JEDDropdown(8,height-99,width-16,field.path().equals("profiles")?Component.translatableWithFallback("justepicdimensions.builder.profiles","Setting profiles"):JEDBuilderFieldInfo.tr("selector_biome"),entry,JEDChoiceSources.server(parent,field.path().equals("profiles")?"justepicdimensions:profiles":"minecraft:worldgen/biome"),Component::literal,s->{entry=s;store();}));
            else{var box=new EditBox(font,8,height-99,width-100,20,title);box.setMaxLength(JEDBuilderWire.MAX_CHARS);box.setValue(entry);box.setResponder(s->{entry=s;error="";});addRenderableWidget(box);button(JEDBuilderFieldInfo.tr("add_value"),width-88,height-99,80,this::store);}
            button(Component.translatable("justepicdimensions.gui.previous"),8,height-74,90,()->{page=Math.max(0,page-1);rebuildWidgets();});button(Component.translatable("justepicdimensions.gui.next"),102,height-74,90,()->{if((page+1)*count<values.size())page++;rebuildWidgets();});
        }
        button(Component.translatable("justepicdimensions.gui.local"),8,height-28,width/2-12,this::save);button(Component.translatable("justepicdimensions.gui.cancel"),width/2+4,height-28,width/2-12,this::onClose);
    }
    private void store(){if(entry.isBlank())return;if(selected>=0)values.set(selected,entry);else values.add(entry);selected=-1;entry="";error="";rebuildWidgets();}
    private JsonArray json(){String shape=JEDBuilderFieldInfo.jsonType(field);var array=new JsonArray();for(String value:values){if(shape.equals("commands")||shape.equals("ids")||shape.equals("vector")&&value.startsWith("~"))array.add(value);else array.add(new java.math.BigDecimal(value));}field.validate(array);return array;}
    private void save(){if(!entry.isBlank())store();parent.accept(field,json());minecraft.setScreen(parent);}
    public void onClose(){if(!values.equals(initial)||!entry.isBlank())minecraft.setScreen(new ConfirmScreen(discard->{minecraft.setScreen(discard?parent:this);},Component.translatable("justepicdimensions.gui.discard_json"),Component.translatable("justepicdimensions.gui.local_help")));else minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
    public void mouseMoved(double x,double y){minecraft.setLastInputType(InputType.MOUSE);super.mouseMoved(x,y);}
    public void render(GuiGraphics g,int x,int y,float partial){super.render(g,x,y,partial);g.drawCenteredString(font,title,width/2,12,0xffffff);String shape=JEDBuilderFieldInfo.jsonType(field);if(Set.of("brightness","vector","offset").contains(shape))for(int i=0;i<boxes.size();i++)g.drawString(font,boxes.get(i).getMessage(),boxes.get(i).getX(),boxes.get(i).getY()-11,0xffbbbbbb);g.drawString(font,font.plainSubstrByWidth(error,width-16),8,height-45,0xffffaaaa);}
}
