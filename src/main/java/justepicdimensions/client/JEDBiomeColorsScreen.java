package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.JEDText;
import justepicdimensions.builder.*;
import net.minecraft.client.InputType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

/** Edits the existing ordered biome/color rule array; saving the server draft remains explicit. */
public final class JEDBiomeColorsScreen extends Screen {
    final JEDBuilderScreen parent;final JEDBuilderFields.Field field;final JsonArray original;JsonArray rules;
    String selector="",color="#FFFFFF",kind="biome",error="";int selected=-1,page;boolean formDirty;EditBox selectorBox,colorBox;
    JEDBiomeColorsScreen(JEDBuilderScreen parent,JEDBuilderFields.Field field){
        super(JEDBuilderFieldInfo.tr("rules_title",JEDBuilderFieldInfo.tr("word."+field.path().substring(field.path().lastIndexOf('.')+1))));this.parent=parent;this.field=field;
        var current=parent.edit.value(field.document(),field.path());if(current!=null&&!current.isJsonArray())throw JEDText.failure("justepicdimensions.builder.expected",JEDBuilderFieldInfo.tr("type.rules"));rules=current!=null?current.getAsJsonArray().deepCopy():new JsonArray();original=rules.deepCopy();
        for(var rule:rules){if(!rule.isJsonObject())throw JEDText.failure("justepicdimensions.builder.rule_object",1);var row=rule.getAsJsonObject();if((row.has("biome")?1:0)+(row.has("tag")?1:0)+(row.has("regex")?1:0)!=1||!row.has("color"))throw JEDText.failure("justepicdimensions.builder.selector_error");for(var key:row.keySet())if(!Set.of("biome","tag","regex","color").contains(key)||!row.get(key).isJsonPrimitive()||!row.get(key).getAsJsonPrimitive().isString())throw JEDText.failure("justepicdimensions.builder.selector_error");}
        if(!rules.isEmpty()){selected=0;var row=rules.get(0).getAsJsonObject();kind=row.has("biome")?"biome":row.has("tag")?"tag":"regex";selector=row.get(kind).getAsString();color=row.get("color").getAsString();}
    }
    private Button button(Component label,int x,int y,int width,Runnable action){return addRenderableWidget(Button.builder(label,b->{try{action.run();}catch(Exception e){error=JEDText.error(e).getString();}}).bounds(x,y,width,20).build());}
    private Button button(String key,int x,int y,int width,Runnable action){return button(JEDBuilderFieldInfo.tr(key),x,y,width,action);}
    protected void init(){
        int left=width/2-12,right=width/2+4,rw=width-right-8,count=Math.max(1,(height-124)/22);page=Math.min(page,Math.max(0,(rules.size()-1)/count));
        for(int i=page*count;i<Math.min(rules.size(),(page+1)*count);i++){
            final int index=i;var row=rules.get(i).getAsJsonObject();String type=row.has("biome")?"biome":row.has("tag")?"tag":"regex";
            var label=Component.literal((selected==i?"> ":"")+(i+1)+". "+row.get(type).getAsString());
            button(label,8,45+(i-page*count)*22,left,()->select(index)).setTooltip(Tooltip.create(Component.literal(row.toString()).append("\n").append(JEDBuilderFieldInfo.tr("rules_priority"))));
        }
        button(Component.translatable("justepicdimensions.gui.previous"),8,height-77,left/2-2,()->{page=Math.max(0,page-1);rebuildWidgets();});
        button(Component.translatable("justepicdimensions.gui.next"),12+left/2,height-77,left/2-2,()->{if((page+1)*count<rules.size()){page++;rebuildWidgets();}}).setMessage(Component.translatable("justepicdimensions.gui.next"));
        addRenderableWidget(new JEDDropdown(right,44,rw,JEDBuilderFieldInfo.tr("selector"),kind,JEDDropdown.local(List.of("biome","tag","regex")),s->JEDBuilderFieldInfo.tr("selector_"+s),s->{kind=s;selector="";formDirty=true;rebuildWidgets();})).setTooltip(Tooltip.create(JEDBuilderFieldInfo.tr("selector_help")));
        if(kind.equals("regex")){selectorBox=new EditBox(font,right,78,rw,20,JEDBuilderFieldInfo.tr("selector"));selectorBox.setMaxLength(1024);selectorBox.setValue(selector);selectorBox.setHint(Component.literal("minecraft:.*"));selectorBox.setResponder(s->{selector=s;formDirty=true;});selectorBox.setTooltip(Tooltip.create(JEDBuilderFieldInfo.tr("selector_help")));addRenderableWidget(selectorBox);}
        else addRenderableWidget(new JEDDropdown(right,78,rw,JEDBuilderFieldInfo.tr("selector"),selector,JEDChoiceSources.server(parent,(kind.equals("tag")?"tags:":"")+Registries.BIOME.location()),Component::literal,s->{selector=s;formDirty=true;error="";}));
        colorBox=new EditBox(font,right,135,Math.max(30,rw-19),20,JEDBuilderFieldInfo.tr("color"));colorBox.setMaxLength(7);colorBox.setValue(color);colorBox.setHint(Component.literal("#RRGGBB"));colorBox.setResponder(s->{color=s;formDirty=true;});colorBox.setTooltip(Tooltip.create(JEDBuilderFieldInfo.tr("color_error")));addRenderableWidget(colorBox);
        button(selected<0?"add_rule":"update_rule",right,159,rw,()->{applyRule();rebuildWidgets();});
        int third=Math.max(24,(left-6)/3);
        button("up",8,height-52,third,()->move(-1)).active=selected>0;
        button("down",11+third,height-52,third,()->move(1)).active=selected>=0&&selected+1<rules.size();
        button(Component.translatable("justepicdimensions.gui.remove"),14+2*third,height-52,third,()->{rules.remove(selected);formDirty=false;fresh();}).active=selected>=0;
        button("new_rule",right,height-52,rw,this::fresh);
        button(Component.translatable("justepicdimensions.gui.local"),8,height-28,width/2-12,()->{if(formDirty)applyRule();parent.accept(field,rules);minecraft.setScreen(parent);});
        button(Component.translatable("justepicdimensions.gui.cancel"),width/2+4,height-28,width/2-12,this::onClose);
    }
    private void select(int index){if(formDirty){try{applyRule();}catch(Exception e){error=JEDText.error(e).getString();return;}}selected=index;var row=rules.get(index).getAsJsonObject();kind=row.has("biome")?"biome":row.has("tag")?"tag":"regex";selector=row.get(kind).getAsString();color=row.get("color").getAsString();formDirty=false;error="";rebuildWidgets();}
    private void fresh(){if(formDirty){applyRule();}selected=-1;selector="";color="#FFFFFF";kind="biome";formDirty=false;error="";rebuildWidgets();}
    private void applyRule(){
        var row=new JsonObject();row.addProperty(kind,selector);row.addProperty("color",color);var single=new JsonArray();single.add(row);JEDBuilderFields.biomeRules(single);
        if(selected<0){rules.add(row);selected=rules.size()-1;}else rules.set(selected,row);formDirty=false;error="";
    }
    private void move(int direction){if(formDirty)applyRule();int other=selected+direction;var row=rules.get(selected);rules.set(selected,rules.get(other));rules.set(other,row);selected=other;rebuildWidgets();}
    public void onClose(){if(formDirty||!rules.equals(original))minecraft.setScreen(new ConfirmScreen(discard->{if(discard)minecraft.setScreen(parent);else minecraft.setScreen(this);},Component.translatable("justepicdimensions.gui.discard_json"),JEDBuilderFieldInfo.tr("rules_unsaved")));else minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
    @Override public void mouseMoved(double x,double y){minecraft.setLastInputType(InputType.MOUSE);super.mouseMoved(x,y);}
    public void render(GuiGraphics g,int x,int y,float partial){
        super.render(g,x,y,partial);g.drawCenteredString(font,title,width/2,12,0xffffff);g.drawString(font,font.plainSubstrByWidth(error.isEmpty()?JEDBuilderFieldInfo.tr("rules_priority").getString():error,width-16),8,30,error.isEmpty()?0xb8d6ed:0xff9999);
        int right=width/2+4;g.drawString(font,JEDBuilderFieldInfo.tr("selector"),right,68,0xdddddd);g.drawString(font,JEDBuilderFieldInfo.tr("color"),right,125,0xdddddd);
        if(color.matches("#[0-9a-fA-F]{6}"))g.fill(width-23,137,width-10,153,0xff000000|Integer.parseInt(color.substring(1),16));
        if(!error.isEmpty()&&y>=28&&y<42)g.renderTooltip(font,font.split(Component.literal(error),width-20),x,y);
    }
    private final class Tags extends Screen {
        String query="";int page;Tags(){super(JEDBuilderFieldInfo.tr("selector_tag"));}
        protected void init(){var search=new EditBox(font,8,35,width-16,20,JEDBuilderFieldInfo.tr("selector"));search.setValue(query);search.setResponder(s->{query=s;page=0;int cursor=search.getCursorPosition();rebuildWidgets();var next=children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();setFocused(next);next.setCursorPosition(cursor);});addRenderableWidget(search);
            var ids=minecraft.level.registryAccess().registryOrThrow(Registries.BIOME).getTagNames().map(k->k.location().toString()).filter(s->s.contains(query)).sorted().toList();int count=Math.max(1,(height-100)/22);
            for(int i=page*count;i<Math.min(ids.size(),(page+1)*count);i++){String id=ids.get(i);addRenderableWidget(Button.builder(Component.literal(id),b->{selector=id;formDirty=true;minecraft.setScreen(JEDBiomeColorsScreen.this);}).bounds(8,62+(i-page*count)*22,width-16,20).build());}
            addRenderableWidget(Button.builder(Component.translatable("justepicdimensions.gui.previous"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(8,height-30,80,20).build());
            addRenderableWidget(Button.builder(Component.translatable("justepicdimensions.gui.next"),b->{if((page+1)*count<ids.size()){page++;rebuildWidgets();}}).bounds(92,height-30,80,20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(width-88,height-30,80,20).build());
        }
        public void render(GuiGraphics g,int x,int y,float p){super.render(g,x,y,p);g.drawCenteredString(font,title,width/2,12,0xffffff);}
        public void onClose(){minecraft.setScreen(JEDBiomeColorsScreen.this);}public boolean isPauseScreen(){return false;}public void mouseMoved(double x,double y){minecraft.setLastInputType(InputType.MOUSE);super.mouseMoved(x,y);}
    }
}
