package justepicdimensions;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;

/** Player presentation only. Stored messages, exception messages and logs retain their English text. */
public final class JEDText {
    private static final Map<String,String> ENGLISH=english();
    private static final Map<String,String> FIXED=fixedKeys();
    private static Map<String,String> english(){
        try(var stream=Objects.requireNonNull(JEDText.class.getResourceAsStream("/assets/justepicdimensions/lang/en_us.json"));var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){
            var result=new LinkedHashMap<String,String>();JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(e->result.put(e.getKey(),e.getValue().getAsString()));return Collections.unmodifiableMap(result);
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    private static Map<String,String> fixedKeys(){var result=new HashMap<String,String>();ENGLISH.forEach((key,value)->{if(!value.contains("%")&&!value.isEmpty())result.putIfAbsent(value,key);});return Map.copyOf(result);}
    private static String key(String key){return key.startsWith("justepicdimensions.")?key:"justepicdimensions.text."+key;}
    public static Component tr(String key,Object...args){String id=key(key);Object[] safe=Arrays.stream(args).map(v->v instanceof Component||v instanceof Number||v instanceof Boolean||v instanceof String?v:String.valueOf(v)).toArray();return Component.translatableWithFallback(id,Objects.requireNonNull(ENGLISH.get(id),id),safe);}
    /** Only registered, exact UI messages are translated; unknown diagnostic text is kept verbatim. */
    public static Component fixed(String english){if(english==null)return Component.empty();String key=FIXED.get(english);return key==null?Component.literal(english):tr(key);}
    public static Component error(Throwable error){
        while((error instanceof java.util.concurrent.CompletionException||error instanceof java.util.concurrent.ExecutionException)&&error.getCause()!=null)error=error.getCause();
        if(error instanceof JEDDefinitionException definition)return definition.display();
        if(error instanceof StateFailure failure)return failure.display;
        return fixed(error.getMessage());
    }
    public static JEDDefinitionException failure(String key,Object...args){return new JEDDefinitionException(format(key,args),null,tr(key,args));}
    public static IllegalStateException stateFailure(String key,Object...args){return new StateFailure(format(key,args),tr(key,args));}
    private static String format(String key,Object...args){return String.format(Locale.ROOT,Objects.requireNonNull(ENGLISH.get(key(key))),args);}
    private static final class StateFailure extends IllegalStateException {final Component display;StateFailure(String english,Component display){super(english);this.display=display;}}
    public static void write(JsonObject json,String field,Component text,HolderLookup.Provider registries){json.add(field+"_i18n",JsonParser.parseString(Component.Serializer.toJson(text,registries)));}
    public static Component read(JsonObject json,String field,HolderLookup.Provider registries){return json.has(field+"_i18n")?Objects.requireNonNull(Component.Serializer.fromJson(json.get(field+"_i18n"),registries)):fixed(json.has(field)?json.get(field).getAsString():"");}
    private JEDText(){}
}
