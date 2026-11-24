package justepicdimensions.schedule;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import net.minecraft.resources.ResourceLocation;

/** Only positively identified, completed scheduled resets qualify for automatic history pruning. */
public final class JEDScheduleHistory {
    public record Entry(String token,ResourceLocation dimension,long completedAt,List<JEDLifecycleSchedulePolicy.Retention> retention){}
    public static JsonObject finish(Path world,JsonObject operation,long wallMillis,long serverTicks){
        String token=JEDScheduleStore.token(operation.get("token").getAsString());var path=JEDDimensionFiles.trash(world,token).resolve("entry.json");JEDDimensionFiles.requireSafe(world,path,false);
        var entry=JEDJson.object(JEDJson.read(path),path.toString());
        if(!entry.get("token").equals(operation.get("token"))||!entry.get("dimension").equals(operation.get("dimension"))||!entry.has("schedule")||!entry.get("schedule").equals(operation.get("schedule")))throw new JEDDefinitionException("Scheduled reset history identity mismatch: "+token);
        JEDScheduleStore.validateMetadata(entry.getAsJsonObject("schedule"),ResourceLocation.parse(entry.get("dimension").getAsString()));
        if(!entry.has("completed_at")){entry.addProperty("completed_at",wallMillis);entry.addProperty("completed_ticks",serverTicks);JEDDimensionFiles.writeDurable(path,entry);}
        JEDLifecycleSchedulePolicy.nonnegative(entry.get("completed_at"),"scheduled completion time");JEDLifecycleSchedulePolicy.nonnegative(entry.get("completed_ticks"),"scheduled completion ticks");return entry;
    }
    public static List<Entry> completed(Path world){
        var root=world.resolve(".jed-trash");if(!Files.exists(root))return List.of();JEDDimensionFiles.requireSafe(world,root,false);var result=new ArrayList<Entry>();
        try(var paths=Files.list(root)){
            for(var bin:paths.toList()){
                String token=bin.getFileName().toString();if(!token.matches("[a-f0-9-]{36}"))continue;bin=JEDDimensionFiles.trash(world,token);var path=bin.resolve("entry.json");if(!Files.exists(path))continue;JEDDimensionFiles.requireSafe(world,path,false);
                var entry=JEDJson.object(JEDJson.read(path),path.toString());if(!entry.has("schedule"))continue;
                if(!entry.has("token")||!token.equals(entry.get("token").getAsString())||!entry.has("action")||!entry.get("action").getAsString().equals("reset"))throw new JEDDefinitionException("Invalid scheduled reset history: "+path);
                var dimension=JEDDimensionDefinition.id(JEDJson.string(entry.get("dimension"),"scheduled history dimension"),path.toString());var metadata=JEDJson.object(entry.get("schedule"),path.toString());JEDScheduleStore.validateMetadata(metadata,dimension);
                if(!entry.has("completed_at"))continue;long completed=JEDLifecycleSchedulePolicy.nonnegative(entry.get("completed_at"),path.toString());var retention=new ArrayList<JEDLifecycleSchedulePolicy.Retention>();
                for(var raw:metadata.getAsJsonArray("jobs"))retention.add(JEDScheduleStore.retention(raw.getAsJsonObject().getAsJsonObject("retention")));result.add(new Entry(token,dimension,completed,List.copyOf(retention)));
            }
        }catch(java.io.IOException error){throw new JEDDefinitionException("Cannot inspect scheduled reset history; retained all entries",error);}return List.copyOf(result);
    }
    public static List<String> eligible(Path world,long now){
        if(now<0)throw new IllegalArgumentException("Negative wall clock");var grouped=new TreeMap<ResourceLocation,List<Entry>>();for(var entry:completed(world))grouped.computeIfAbsent(entry.dimension(),id->new ArrayList<>()).add(entry);var result=new ArrayList<String>();
        for(var entries:grouped.values()){
            entries.sort(Comparator.comparingLong(Entry::completedAt).reversed().thenComparing(Entry::token));for(int index=0;index<entries.size();index++){
                var entry=entries.get(index);boolean purge=true;for(var policy:entry.retention()){
                    boolean eligible=switch(policy.mode()){case "unlimited"->false;case "count"->index>=policy.value();case "age"->now>=entry.completedAt()&&now-entry.completedAt()>=Math.multiplyExact(policy.value(),86400000);default->false;};purge&=eligible;
                }if(purge)result.add(entry.token());
            }
        }return List.copyOf(result);
    }
    private JEDScheduleHistory(){}
}
