package justepicdimensions.builder;

import com.google.gson.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import justepicdimensions.JEDText;

/** Invalid text is retained separately from the last valid local JSON, with its exact field and reason. */
public final class JEDBuilderInputs {
    public record Issue(JEDBuilderFields.Field field,String raw,Component reason,boolean remote){
        public Component message(){return JEDBuilderFieldInfo.tr("field_error",field.label(),field.document()+":"+field.path(),reason);}
    }
    private final Map<String,Issue> issues=new LinkedHashMap<>();
    private static String key(JEDBuilderFields.Field field){return field.document()+":"+field.path();}
    public List<Issue> issues(){return List.copyOf(issues.values());}
    public boolean blocking(){return issues.values().stream().anyMatch(i->!i.remote());}
    public Issue issue(JEDBuilderFields.Field field){return issues.get(key(field));}
    public void failure(JEDBuilderEdit edit,JEDBuilderFields.Field field,Component reason){issues.put(key(field),new Issue(field,field.text(edit.value(field.document(),field.path())),reason,false));}
    public boolean enter(JEDBuilderEdit edit,JEDBuilderFields.Field field,String text){
        try{
            if(text.isEmpty()&&edit.value(field.document(),field.path())==null){clear(field);return true;}
            accept(edit,field,field.parse(text));return true;
        }catch(Exception error){issues.put(key(field),new Issue(field,text,JEDText.error(error),false));return false;}
    }
    public void accept(JEDBuilderEdit edit,JEDBuilderFields.Field field,JsonElement value){
        field.validate(value);edit.set(field.document(),field.path(),value);clear(field);issues.values().removeIf(Issue::remote);
    }
    public void document(JEDBuilderEdit edit,String document,String text){
        var parsed=JEDBuilderFields.json(text);var candidate=new JEDBuilderEdit(edit.documents());candidate.document(document,parsed.toString());
        for(var field:JEDBuilderFields.all())if(field.document().equals(document)){
            var value=candidate.value(document,field.path());if(value!=null){var provider=candidate.value("properties","provider.type");boolean custom=provider!=null&&provider.isJsonPrimitive()&&provider.getAsJsonPrimitive().isString()&&!Set.of("jed_surface","jed_hell","jed_end","justepicdimensions:surface","justepicdimensions:hell","justepicdimensions:end").contains(provider.getAsString());if(!custom||!field.path().startsWith("provider.settings."))try{field.validate(value);}catch(Exception error){throw new justepicdimensions.definition.JEDDefinitionException(error.getMessage(),error,JEDBuilderFieldInfo.tr("field_error",field.label(),field.document()+":"+field.path(),JEDText.error(error)));}}
        }
        edit.document(document,parsed.toString());issues.values().removeIf(i->i.field().document().equals(document)||i.remote());
    }
    public void server(JEDBuilderEdit edit,Component message){
        issues.values().removeIf(Issue::remote);String text=message.getString();
        JEDBuilderFields.all().stream().filter(f->text.contains(f.path())).max(Comparator.comparingInt(f->f.path().length())).ifPresent(f->issues.put(key(f),new Issue(f,f.text(edit.value(f.document(),f.path())),message,true)));
    }
    public void clear(JEDBuilderFields.Field field){issues.values().removeIf(i->i.field().document().equals(field.document())&&(i.field().path().equals(field.path())||i.field().path().startsWith(field.path()+".")||field.path().startsWith(i.field().path()+".")));}
    public void drop(JEDBuilderEdit edit,String document){edit.drop(document);issues.values().removeIf(i->i.field().document().equals(document));}
    public void clear(){issues.clear();}
    public void inspect(JEDBuilderEdit edit){
        var provider=edit.value("properties","provider.type");boolean custom=provider!=null&&provider.isJsonPrimitive()&&provider.getAsJsonPrimitive().isString()&&!Set.of("jed_surface","jed_hell","jed_end","justepicdimensions:surface","justepicdimensions:hell","justepicdimensions:end").contains(provider.getAsString());
        for(var field:JEDBuilderFields.all()){var value=edit.value(field.document(),field.path());if(value==null||custom&&field.path().startsWith("provider.settings."))continue;try{field.validate(value);}catch(Exception error){issues.putIfAbsent(key(field),new Issue(field,field.text(value),JEDText.error(error),true));}}
    }
    public boolean preflight(JEDBuilderEdit edit,boolean inherited){
        issues.values().removeIf(Issue::remote);inspect(edit);
        for(var missing:JEDBuilderRequirements.terrain(edit.documents(),inherited)){var field=JEDBuilderFields.all().stream().filter(f->f.document().equals("dimension")&&f.path().equals(missing.path())).findFirst().orElseThrow();issues.putIfAbsent(key(field),new Issue(field,field.text(edit.value(field.document(),field.path())),missing.reason(),true));}
        return issues.isEmpty();
    }
}
