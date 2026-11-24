package justepicdimensions.builder;

import com.google.gson.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import justepicdimensions.JEDText;

/** Structural requirements only; native and addon codecs remain authoritative. */
public final class JEDBuilderRequirements {
    public record Missing(String path,Component reason){}
    public static List<Missing> terrain(JsonObject documents,boolean inherited){
        if(inherited)return List.of();var missing=new ArrayList<Missing>();var dimension=documents.get("dimension");
        var type=JEDBuilderDraft.find(dimension,"type");
        if(empty(type))missing.add(new Missing("type",JEDBuilderFieldInfo.tr("missing_type")));
        var generator=JEDBuilderDraft.find(dimension,"generator");
        if(empty(generator)||generator.isJsonObject()&&empty(generator.getAsJsonObject().get("type")))missing.add(new Missing("generator",JEDBuilderFieldInfo.tr("missing_generator")));
        return List.copyOf(missing);
    }
    private static boolean empty(JsonElement value){return value==null||value.isJsonNull()||value.isJsonObject()&&value.getAsJsonObject().isEmpty()||value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&value.getAsString().isBlank();}
    public static void require(JEDBuilderDraft draft){
        var missing=terrain(draft.content().getAsJsonObject("documents"),draft.mode().equals("inherit")&&draft.source()!=null);
        if(!missing.isEmpty()){var reasons=Component.empty();for(var issue:missing){if(!reasons.getSiblings().isEmpty())reasons.append("\n");reasons.append("dimension:"+issue.path()+": ").append(issue.reason());}throw JEDText.failure("justepicdimensions.builder.terrain_incomplete",reasons);}
    }
    private JEDBuilderRequirements(){}
}
