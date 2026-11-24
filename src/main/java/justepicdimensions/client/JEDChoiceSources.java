package justepicdimensions.client;

import com.google.gson.JsonObject;
import java.util.*;
import justepicdimensions.builder.*;
import net.minecraft.client.Minecraft;

/** Fetches server-owned options through the existing authenticated, fragmented GUI payload. */
final class JEDChoiceSources {
    static JEDDropdown.Source server(JEDBuilderScreen parent,String registry){return server(parent,registry,()->parent.edit==null?new JsonObject():parent.edit.documents());}
    static JEDDropdown.Source server(JEDBuilderScreen parent,String registry,java.util.function.Supplier<JsonObject> draft){return new JEDDropdown.Source(){
        final Map<String,String> names=new HashMap<>();
        public boolean load(String query,int offset,java.util.function.Consumer<JEDDropdown.Page> reply){
            if(JEDBuilderClient.busy)return false;
            JEDBuilderClient.registryError=JEDDropdown.failureHandler();
            JEDBuilderClient.registryReply=json->{if(!json.get("registry").getAsString().equals(registry)||!json.get("query").getAsString().equals(query)||json.get("offset").getAsInt()!=offset)return;var values=new ArrayList<String>();for(var value:json.getAsJsonArray("values"))values.add(value.getAsString());if(json.has("labels"))json.getAsJsonObject("labels").entrySet().forEach(e->names.put(e.getKey(),e.getValue().getAsString()));reply.accept(new JEDDropdown.Page(values,offset,json.get("total").getAsInt()));};
            var request=new JsonObject();request.addProperty("op","registry");request.addProperty("registry",registry);request.addProperty("query",query);request.addProperty("offset",offset);var choices=new JsonObject();draft.get().keySet().forEach(name->choices.add(name,new JsonObject()));request.add("documents",choices);JEDBuilderClient.request(request);return true;
        }
        public net.minecraft.network.chat.Component label(String value){
            if(registry.equals("minecraft:dimension")){String key=switch(value){case "minecraft:overworld"->"terrain_overworld";case "minecraft:the_nether"->"terrain_nether";case "minecraft:the_end"->"terrain_end";default->"";};if(!key.isEmpty())return JEDBuilderFieldInfo.tr(key).copy().append(" ("+value+")");if(names.containsKey(value))return net.minecraft.network.chat.Component.literal(names.get(value)+" ("+value+")");}
            if(registry.equals("minecraft:worldgen/biome")){var id=net.minecraft.resources.ResourceLocation.tryParse(value);if(id!=null)return net.minecraft.network.chat.Component.translatableWithFallback("biome."+id.getNamespace()+"."+id.getPath().replace('/','.'),value).copy().append(" ("+value+")");}
            return null;
        }
    };}
    static JEDDropdown.Source registry(JEDBuilderScreen parent,String registry){
        if(registry.equals("client:textures")){
            final List<String>[] cached=new List[]{null};return JEDDropdown.local(()->{if(cached[0]==null)cached[0]=Minecraft.getInstance().getResourceManager().listResources("textures",id->id.getPath().endsWith(".png")).keySet().stream().map(Object::toString).sorted().toList();return cached[0];});
        }
        if(registry.startsWith("client:renderers/")){var channel=JEDRendererFactoryRegistry.Channel.valueOf(registry.substring("client:renderers/".length()).toUpperCase(Locale.ROOT));return JEDDropdown.local(()->JEDRendererFactoryRegistry.ids(channel).stream().map(Object::toString).toList());}
        return server(parent,registry);
    }
    static JEDDropdown.Source field(JEDBuilderScreen parent,JEDBuilderFields.Field field){return JEDBuilderControls.control(field)==JEDBuilderControls.Control.FIXED?JEDDropdown.local(JEDBuilderControls.fixed(field)):registry(parent,JEDBuilderControls.registry(field));}
    private JEDChoiceSources(){}
}
