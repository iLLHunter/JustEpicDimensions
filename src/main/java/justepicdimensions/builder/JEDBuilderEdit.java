package justepicdimensions.builder;

import com.google.gson.*;
import java.io.StringReader;
import justepicdimensions.definition.*;

/** Local documents only. Neither keystrokes nor category changes persist or activate anything. */
public final class JEDBuilderEdit {
    private JsonObject base,documents;private Boolean dirty=false;
    public JEDBuilderEdit(JsonObject value){base=value.deepCopy();documents=value.deepCopy();}
    public JsonObject documents(){return documents.deepCopy();}
    public boolean dirty(){if(dirty==null)dirty=!base.equals(documents);return dirty;}
    public void document(String name,String text){JEDBuilderDraft.checkDocument(name);var value=JEDJson.object(JEDBuilderWire.value(text),name);if(value.has(JEDBuilderSources.HEADER))throw new JEDDefinitionException("Builder metadata is managed by the editor");documents.add(name,value);dirty=null;}
    public void drop(String name){documents.remove(name);dirty=null;}
    public boolean hasValue(String name,String path){return JEDBuilderDraft.find(documents.get(name),path)!=null;}
    public JsonElement value(String name,String path){var doc=documents.get(name);var value=JEDBuilderDraft.find(doc,path);return value==null?null:value.deepCopy();}
    public void set(String name,String path,JsonElement value){JEDBuilderDraft.path(path);if(!documents.has(name))documents.add(name,new JsonObject());JEDBuilderDraft.set(documents.getAsJsonObject(name),path,value);dirty=null;}
    public void inherit(String path){var draft=temporary();try{draft.remove("properties",path);}catch(JEDDefinitionException ignored){}var docs=draft.content().getAsJsonObject("documents");var prop=docs.getAsJsonObject("properties");if(prop.has("reset")){var resets=prop.getAsJsonArray("reset");for(int i=resets.size()-1;i>=0;i--){String p=resets.get(i).getAsString();if(p.equals(path)||p.startsWith(path+".")||path.startsWith(p+"."))resets.remove(i);}if(resets.isEmpty())prop.remove("reset");}documents=docs.deepCopy();dirty=null;}
    public void reset(String path){var draft=temporary();draft.reset(path);documents=draft.content().getAsJsonObject("documents").deepCopy();dirty=null;}
    public boolean resetAt(String path){var p=documents.getAsJsonObject("properties");if(p==null||!p.has("reset"))return false;for(var v:p.getAsJsonArray("reset")){String r=v.getAsString();if(path.equals(r)||path.startsWith(r+"."))return true;}return false;}
    private JEDBuilderDraft temporary(){var draft=new JEDBuilderDraft();draft.updateDocuments(documents);return draft;}
}
