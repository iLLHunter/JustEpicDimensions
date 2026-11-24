package justepicdimensions.config;

import com.google.gson.*;
import java.nio.file.*;
import justepicdimensions.definition.*;
import justepicdimensions.JEDText;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

/** Server-owned paths and snapshots. A remote client cannot select an arbitrary file or world. */
public final class JEDConfigService {
    public static Path global(){return JEDConfigPaths.main(FMLPaths.CONFIGDIR.get());}
    public static Path file(MinecraftServer server,String scope){
        return switch(scope){
            case "global" -> global();
            case "world" -> JEDConfigPaths.worldMain(JEDDefinitionManager.state(server).prepared().selection().world());
            default -> throw new JEDDefinitionException("Unknown configuration section: "+scope);
        };
    }
    public static JsonObject snapshot(MinecraftServer server){
        thread(server);var prepared=JEDDefinitionManager.state(server).prepared();var json=new JsonObject();
        json.addProperty("kind","snapshot");var files=new JsonObject();
        files.add("global",document(server,"global"));
        files.add("world",document(server,"world"));json.add("files",files);
        json.add("active",prepared.activeMainConfig());json.addProperty("active_path",prepared.mainConfigSource().toString());
        try{json.addProperty("selected_path",JEDConfigRepository.mainFile(prepared.selection()).toAbsolutePath().normalize().toString());}
        catch(Exception error){json.addProperty("selected_path","");json.addProperty("selection_error",error.getMessage());}
        json.addProperty("json_source",prepared.selection().activeSource().toString());
        var pending=new JsonArray();prepared.pendingRestart().stream().sorted().forEach(pending::add);json.add("pending",pending);
        var choices=new JsonObject();for(String key:java.util.List.of("initial_spawn_dimension","world_preset")){var ids=new JsonArray();ids.add("");var registry=key.equals("world_preset")?net.minecraft.core.registries.Registries.WORLD_PRESET:net.minecraft.core.registries.Registries.LEVEL_STEM;server.registryAccess().registry(registry).ifPresent(values->values.keySet().stream().map(Object::toString).sorted().forEach(ids::add));if(key.equals("world_preset")&&ids.size()==1)server.getResourceManager().listResources("worldgen/world_preset",id->id.getPath().endsWith(".json")).keySet().stream().map(id->id.getNamespace()+":"+id.getPath().substring("worldgen/world_preset/".length(),id.getPath().length()-5)).sorted().forEach(ids::add);if(ids.size()>1)choices.add(key,ids);}json.add("choices",choices);
        return json;
    }
    private static JsonObject document(MinecraftServer server,String scope){
        Path path=file(server,scope);
        try{return JEDConfigFiles.read(path,false).json();}
        catch(Exception error){var json=new JsonObject();json.addProperty("path",path.toString());json.addProperty("error",error.getMessage());JEDText.write(json,"error",JEDText.error(error),server.registryAccess());return json;}
    }
    public static JsonObject save(MinecraftServer server,String scope,String stamp,JsonObject values,boolean apply){
        thread(server);JEDDefinitionHooks.requireIdle(server);
        if(justepicdimensions.dimension.JEDDimensionManager.isBusy(server)||justepicdimensions.builder.JEDBuilderService.busy(server))
            throw new JEDDefinitionException("Wait for the active dimension or builder operation before saving configuration");
        var saved=JEDConfigFiles.save(file(server,scope),stamp,values,false);
        String failure="";net.minecraft.network.chat.Component failureDisplay=net.minecraft.network.chat.Component.empty();
        if(apply)try{JEDDefinitionManager.reloadMainConfig(server);}catch(Exception error){failure=error.getMessage()==null?error.toString():error.getMessage();failureDisplay=JEDText.error(error);}
        var json=snapshot(server);json.addProperty("saved",true);json.addProperty("applied",apply&&failure.isEmpty());
        json.addProperty("message_key",!failure.isEmpty()?"apply_failed":apply?"applied":"saved");json.addProperty("detail",failure);JEDText.write(json,"detail",failureDisplay,server.registryAccess());
        if(saved.backup()!=null)json.addProperty("backup",saved.backup().toString());
        return json;
    }
    private static void thread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Config operations require the server thread");}
    private JEDConfigService(){}
}
