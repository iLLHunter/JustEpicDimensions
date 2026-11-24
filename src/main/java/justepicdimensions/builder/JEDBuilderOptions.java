package justepicdimensions.builder;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;

/** Paged, read-only choices. Uses the same server-thread permission gate as the editor. */
public final class JEDBuilderOptions {
    public static JsonObject page(MinecraftServer server,String name,String query,int offset){return page(server,name,query,offset,Set.of());}
    private static JsonObject page(MinecraftServer server,String name,String query,int offset,Set<String> draftDocuments){
        if(!server.isSameThread())throw new IllegalStateException("Builder choices require the server thread");
        if(offset<0||query.length()>256)throw new JEDDefinitionException("Invalid registry page");
        var ids=new TreeSet<String>();
        if(name.equals("justepicdimensions:providers"))JEDProviderFactoryRegistry.ids().forEach(id->ids.add(id.toString()));
        else if(name.equals("justepicdimensions:access_rule_document")){var id=JEDDimensionDefinition.id(query,"access rule");var key=ResourceLocation.fromNamespaceAndPath(id.getNamespace(),justepicdimensions.access.JEDAccessRule.DIRECTORY+"/"+id.getPath()+".json");var document=JEDDefinitionManager.state(server).prepared().resources().get(key);var reply=result(name,query,offset,ids,new JsonObject());reply.add("document",document==null?new JsonObject():document.value());return reply;}
        else if(name.equals("justepicdimensions:access_rules")||name.equals("justepicdimensions:mob_spawning")||name.equals("justepicdimensions:environment")||name.equals("justepicdimensions:lifecycle_schedules")||name.equals("justepicdimensions:monitoring")||name.equals("justepicdimensions:instance_policies")||name.equals("justepicdimensions:pregeneration")||name.equals("justepicdimensions:profiles")){String directory=name.endsWith(":monitoring")?justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY:name.endsWith(":instance_policies")?justepicdimensions.instance.JEDInstancePolicy.DIRECTORY:name.endsWith(":pregeneration")?justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY:name.endsWith(":profiles")?justepicdimensions.profile.JEDSettingProfiles.DIRECTORY:name.endsWith(":lifecycle_schedules")?justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY:name.endsWith(":environment")?justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY:name.endsWith(":mob_spawning")?justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY:justepicdimensions.access.JEDAccessRule.DIRECTORY;for(String document:draftDocuments){var key=ResourceLocation.tryParse(document);if(key!=null&&key.getPath().startsWith(directory+"/")&&key.getPath().endsWith(".json"))ids.add(JEDRegistryResources.entry(key,directory).toString());}JEDDefinitionManager.state(server).prepared().resources().keySet().stream().filter(id->id.getPath().startsWith(directory+"/")).map(id->JEDRegistryResources.entry(id,directory).toString()).forEach(ids::add);}
        else if(name.equals("justepicdimensions:loadouts")){
            var prepared=JEDDefinitionManager.state(server).prepared();String directory=justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY;
            prepared.resources().keySet().stream().filter(id->id.getPath().startsWith(directory+"/")).map(id->JEDRegistryResources.entry(id,directory).toString()).forEach(ids::add);
        }else if(name.startsWith("justepicdimensions:player_groups/")){
            String key=name.substring("justepicdimensions:player_groups/".length());
            var category=justepicdimensions.player.JEDPlayerCategory.parse(key,"builder category choices");String directory=category.directory();
            JEDDefinitionManager.state(server).prepared().resources().keySet().stream().filter(id->id.getPath().startsWith(directory+"/")).map(id->JEDRegistryResources.entry(id,directory).toString()).forEach(ids::add);
        }else if(disk(name)){
            ids.addAll(files(JEDDefinitionManager.state(server).prepared().selection().source(),name,packAssets(server,name)));
        }else if(name.startsWith("tags:")){
            var registry=server.registryAccess().registry(ResourceKey.createRegistryKey(ResourceLocation.parse(name.substring(5)))).orElseThrow(()->new JEDDefinitionException("Unknown server registry "+name));
            registry.getTagNames().forEach(tag->ids.add(tag.location().toString()));
        }else{
            var registry=server.registryAccess().registry(ResourceKey.createRegistryKey(ResourceLocation.parse(name))).orElseThrow(()->new JEDDefinitionException("Unknown server registry "+name));
            registry.keySet().forEach(id->ids.add(id.toString()));
        }
        if(!name.startsWith("tags:"))for(String document:draftDocuments){var key=ResourceLocation.tryParse(document);if(key!=null){String directory=JEDRegistryResources.directory(key);if(directory!=null&&!directory.equals("dimension")&&name.equals(JEDRegistryResources.types().get(directory).key().location().toString()))ids.add(JEDRegistryResources.entry(key,directory).toString());}}
        var labels=new JsonObject();if(name.equals("minecraft:dimension"))for(String id:ids){var definition=JEDDefinitionManager.state(server).definitions().get(ResourceLocation.parse(id));if(definition!=null&&definition.displayName()!=null)labels.addProperty(id,definition.displayName());}
        return result(name,query,offset,ids,labels);
    }
    public static java.util.concurrent.CompletableFuture<JsonObject> request(MinecraftServer server,String name,String query,int offset,JsonObject documents){if(name.equals("justepicdimensions:access_rules")||name.equals("justepicdimensions:mob_spawning")||name.equals("justepicdimensions:environment")||name.equals("justepicdimensions:lifecycle_schedules")||name.equals("justepicdimensions:monitoring")||name.equals("justepicdimensions:instance_policies")||name.equals("justepicdimensions:pregeneration")||name.equals("justepicdimensions:profiles"))return java.util.concurrent.CompletableFuture.completedFuture(page(server,name,query,offset,documents.keySet()));if(!disk(name))return java.util.concurrent.CompletableFuture.completedFuture(page(server,name,query,offset,documents.keySet()));return request(server,name,query,offset);}
    public static java.util.concurrent.CompletableFuture<JsonObject> request(MinecraftServer server,String name,String query,int offset){
        if(!disk(name))return java.util.concurrent.CompletableFuture.completedFuture(page(server,name,query,offset));
        if(!server.isSameThread()||offset<0||query.length()>256)throw new JEDDefinitionException("Invalid registry page");
        Path source=JEDDefinitionManager.state(server).prepared().selection().source();var assets=packAssets(server,name);
        return java.util.concurrent.CompletableFuture.supplyAsync(()->result(name,query,offset,files(source,name,assets),new JsonObject()));
    }
    private static boolean disk(String name){return name.equals("justepicdimensions:structures")||name.equals("justepicdimensions:templates");}
    private static Set<String> packAssets(MinecraftServer server,String name){
        var ids=new TreeSet<String>();if(name.equals("justepicdimensions:structures")){
            server.getResourceManager().listResources("structure",id->id.getPath().endsWith(".nbt")).keySet().forEach(id->ids.add(asset(id,"structure/")));
            server.getResourceManager().listResources("justepicdimensions/schematics",id->id.getPath().endsWith(".schem")).keySet().forEach(id->ids.add(asset(id,"justepicdimensions/schematics/")));
        }return ids;
    }
    private static Set<String> files(Path source,String name,Set<String> packed){
        var ids=new TreeSet<>(packed);
        try{
            if(name.equals("justepicdimensions:structures")){
                Path data=source.resolve("data");if(Files.isDirectory(data))try(var namespaces=Files.list(data)){
                    for(var ns:namespaces.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList())for(String prefix:List.of("structure","justepicdimensions/schematics")){
                        Path directory=ns.resolve(prefix);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))continue;
                        try(var paths=Files.walk(directory)){paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.toString().endsWith(prefix.equals("structure")?".nbt":".schem")).forEach(file->{JEDDimensionFiles.requireSafe(data,file,false);String id=ns.getFileName()+":"+directory.relativize(file).toString().replace('\\','/');if(ResourceLocation.tryParse(id)!=null)ids.add(id);});}
                    }
                }
            }else{
                Path root=source.resolve("templates");if(Files.isDirectory(root))Files.walkFileTree(root,new SimpleFileVisitor<>(){
                    public FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attrs){
                        JEDDimensionFiles.requireSafe(root,directory,true);
                        if(Files.isRegularFile(directory.resolve("level.dat"),LinkOption.NOFOLLOW_LINKS)){String relative=root.relativize(directory).toString().replace('\\','/');if(!relative.isEmpty())ids.add(relative);return FileVisitResult.SKIP_SUBTREE;}
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }catch(IOException error){throw new JEDDefinitionException("Cannot list dimension assets",error);}
        return ids;
    }
    private static JsonObject result(String name,String query,int offset,Set<String> ids,JsonObject labels){
        var filtered=ids.stream().filter(id->id.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))||labels.has(id)&&labels.get(id).getAsString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        var result=new JsonObject();result.addProperty("kind","registry");result.addProperty("registry",name);result.addProperty("query",query);result.addProperty("offset",offset);result.addProperty("total",filtered.size());var values=new JsonArray();var pageLabels=new JsonObject();filtered.stream().skip(offset).limit(80).forEach(id->{values.add(id);if(labels.has(id))pageLabels.add(id,labels.get(id));});result.add("values",values);result.add("labels",pageLabels);return result;
    }
    private static String asset(ResourceLocation id,String prefix){return id.getNamespace()+":"+id.getPath().substring(prefix.length());}
    private JEDBuilderOptions(){}
}
