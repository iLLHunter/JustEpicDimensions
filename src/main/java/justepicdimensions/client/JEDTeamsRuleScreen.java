package justepicdimensions.client;

import com.google.gson.*;
import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.teams.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Detached editor for the approved teams condition, using stable IDs for existing-group picks. */
final class JEDTeamsRuleScreen extends JEDTeamClient.Form {
    final Screen parent;final Consumer<JsonObject> saved;final JsonObject original;JsonObject value;String system="minecraft",futureName="";boolean names;
    JEDTeamsRuleScreen(Screen parent,JsonObject value,Consumer<JsonObject> saved){super(JEDTeamClient.text("allowlist","Allowed teams / parties"));this.parent=parent;this.saved=saved;this.value=value.deepCopy();if(!this.value.has("mode"))this.value.addProperty("mode","any");if(!this.value.has("allow"))this.value.add("allow",new JsonObject());original=this.value.deepCopy();}
    JsonArray entries(){var allow=value.getAsJsonObject("allow");return allow.has(system)?allow.getAsJsonArray(system):new JsonArray();}
    void add(JsonObject reference){var allow=value.getAsJsonObject("allow");var entries=entries();if(entries.asList().stream().noneMatch(reference::equals))entries.add(reference);allow.add(system,entries);rebuildWidgets();}
    protected void init(){fields.clear();maxY=0;
        dropdown("match","Match between systems",42,value.get("mode").getAsString(),List.of("any","each"),v->JEDTeamClient.text("match."+v,v.equals("any")?"Any allowed system":"Each configured system"),v->value.addProperty("mode",v));
        dropdown("system","Team system",74,system,JEDTeamSystems.SYSTEMS,JEDDropdown::label,v->{system=v;scroll=0;rebuildWidgets();});
        button(names?"reference_mode.name":"reference_mode.id",names?"Reference: exact name":"Reference: stable ID (default)",106,()->{names=!names;rebuildWidgets();});
        button("pick_group","Choose an existing group",138,()->JEDTeamClient.picker(system,group->{var ref=new JsonObject();ref.addProperty(names?"name":"id",group.get(names?"name":"id").getAsString());add(ref);}));
        field("future_name","Exact group name (including future groups)",182,futureName,v->futureName=v);
        button("add_name","Add exact name",214,()->{if(futureName.isBlank())throw new IllegalArgumentException("Enter an exact group name");var ref=new JsonObject();ref.addProperty("name",futureName);futureName="";add(ref);});
        var entries=entries();for(int i=0;i<entries.size();i++){int index=i;var ref=entries.get(i).getAsJsonObject();String label=ref.has("id")?"ID: "+ref.get("id").getAsString():"Name: "+ref.get("name").getAsString();var row=button("remove_reference",label+"  [remove]",246+i*28,()->{var values=entries();values.remove(index);if(values.isEmpty())value.getAsJsonObject("allow").remove(system);rebuildWidgets();});row.setMessage(Component.literal(label+"  [−]"));row.setTooltip(Tooltip.create(Component.literal(label)));}
        addRenderableWidget(Button.builder(JEDTeamClient.text("keep","Keep"),b->{try{if(!futureName.isEmpty())throw new IllegalArgumentException("Add the typed name or clear it before keeping this rule");JEDTeamRule.decode(value,"teams");saved.accept(value.deepCopy());minecraft.setScreen(parent);}catch(RuntimeException error){message=String.valueOf(error.getMessage());}}).bounds(8,height-28,(width-24)/2,20).build());
        addRenderableWidget(Button.builder(JEDTeamClient.text("cancel","Cancel"),b->onClose()).bounds(width/2+4,height-28,(width-24)/2,20).build());
    }
    public void onClose(){if(!value.equals(original)||!futureName.isEmpty())minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(discard->minecraft.setScreen(discard?parent:this),JEDTeamClient.text("discard","Discard team rule edits?"),JEDTeamClient.text("discard_help","Unkept changes will be discarded.")));else minecraft.setScreen(parent);}
}
