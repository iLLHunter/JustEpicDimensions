package justepicdimensions.bundle;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.builder.JEDBuilderFiles;
import justepicdimensions.definition.*;

/** Binary-aware recoverable installation sharing the ordinary configuration-reader lock. */
public final class JEDBundleFiles {
    public static final String JOURNAL=".jed-bundle-transaction.json";
    private static Path staged(Path root,String token,String folder,String path){return JEDBuilderFiles.file(root,"bundle-backups/"+token+"/"+folder+"/"+path);}
    public static void recover(Path root){
        var journal=JEDBuilderFiles.file(root,JOURNAL);if(!Files.exists(journal))return;var record=JEDJson.object(JEDJson.read(journal),JOURNAL);JEDJson.keys(record,Set.of("format_version","token","files"),JOURNAL);if(JEDJson.integer(record.get("format_version"),JOURNAL)!=1)throw new JEDDefinitionException("Unsupported bundle transaction");String token=JEDJson.string(record.get("token"),"bundle transaction token");if(!UUID.fromString(token).toString().equals(token))throw new JEDDefinitionException("Invalid bundle transaction token");var files=JEDJson.object(record.get("files"),"bundle transaction files");if(files.size()>JEDDimensionBundle.MAX_FILES)throw new JEDDefinitionException("Bundle transaction too large");JEDDimensionBundle.paths(files.keySet());
        for(var entry:files.entrySet()){
            JEDDimensionBundle.path(entry.getKey());var item=JEDJson.object(entry.getValue(),entry.getKey());JEDJson.keys(item,Set.of("before","after"),entry.getKey());String before=JEDJson.string(item.get("before"),entry.getKey()),after=JEDJson.string(item.get("after"),entry.getKey());if(!after.matches("[a-f0-9]{64}")||!before.equals("<missing>")&&!before.matches("[a-f0-9]{64}"))throw new JEDDefinitionException("Invalid bundle transaction checksum");var target=JEDBuilderFiles.file(root,entry.getKey());String current=JEDBuilderFiles.stamp(target);if(!current.equals(before)&&!current.equals(after))throw new JEDDefinitionException("Bundle recovery found an external edit: "+target+"; transaction retained");
            if(!current.equals(after)&&!JEDBuilderFiles.stamp(staged(root,token,"payload",entry.getKey())).equals(after))throw new JEDDefinitionException("Missing or changed staged bundle file "+entry.getKey());
        }
        for(var entry:files.entrySet())try{
            String name=entry.getKey();var item=entry.getValue().getAsJsonObject();var target=JEDBuilderFiles.file(root,name);String current=JEDBuilderFiles.stamp(target);if(current.equals(item.get("after").getAsString()))continue;
            if(!current.equals("<missing>")){var backup=staged(root,token,"previous",name);if(Files.exists(backup)){if(!JEDBuilderFiles.stamp(backup).equals(current))throw new JEDDefinitionException("Bundle backup changed: "+backup);}else write(backup,Files.readAllBytes(target));}
            write(target,Files.readAllBytes(staged(root,token,"payload",name)));
        }catch(IOException error){throw new JEDDefinitionException("Bundle import interrupted; retained recovery transaction",error);}
        try{Files.delete(journal);}catch(IOException error){throw new JEDDefinitionException("Cannot finish bundle transaction",error);}
    }
    public static String commit(Path root,Map<String,byte[]> files,Map<String,String> expected){
        JEDDimensionBundle.paths(files.keySet());if(files.size()>JEDDimensionBundle.MAX_FILES)throw new JEDDefinitionException("Too many imported files");try(var guard=JEDBuilderFiles.guard(root)){
            JEDBuilderFiles.recover(root);recover(root);if(expected.size()>JEDDimensionBundle.MAX_FILES||!expected.keySet().containsAll(files.keySet()))throw new JEDDefinitionException("Incomplete bundle preview stamps");JEDDimensionBundle.paths(expected.keySet());for(var entry:expected.entrySet())if(!Objects.equals(entry.getValue(),JEDBuilderFiles.stamp(JEDBuilderFiles.file(root,entry.getKey()))))throw new JEDDefinitionException("Bundle import rejected: file changed since preview: "+entry.getKey());long total=0;for(var entry:files.entrySet()){JEDDimensionBundle.path(entry.getKey());if(entry.getValue().length>JEDDimensionBundle.MAX_FILE||(total+=entry.getValue().length)>JEDDimensionBundle.MAX_BYTES)throw new JEDDefinitionException("Import exceeds size limit");if(!Objects.equals(expected.get(entry.getKey()),JEDBuilderFiles.stamp(JEDBuilderFiles.file(root,entry.getKey()))))throw new JEDDefinitionException("Bundle import rejected: file changed since preview: "+entry.getKey());}
            String token=UUID.randomUUID().toString();var record=new JsonObject();record.addProperty("format_version",1);record.addProperty("token",token);var entries=new JsonObject();for(var entry:files.entrySet()){write(staged(root,token,"payload",entry.getKey()),entry.getValue());var item=new JsonObject();item.addProperty("before",expected.get(entry.getKey()));item.addProperty("after",JEDDimensionBundle.hash(entry.getValue()));entries.add(entry.getKey(),item);}record.add("files",entries);JEDJson.writeAtomic(JEDBuilderFiles.file(root,JOURNAL),record);recover(root);return token;
        }
    }
    static void write(Path target,byte[] bytes){Path temporary=null;try{Files.createDirectories(target.getParent());temporary=Files.createTempFile(target.getParent(),".jed-bundle-",".tmp");try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}temporary=null;}catch(IOException error){throw new JEDDefinitionException("Cannot install bundle file "+target,error);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}}
    private JEDBundleFiles(){}
}
