package justepicdimensions.builder;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionSync;

/** A recoverable multi-file save. Readers never consume a half-installed builder operation. */
public final class JEDBuilderFiles {
    private static final String JOURNAL=".jed-builder-transaction.json";
    public static final class Guard implements AutoCloseable {
        private final FileChannel channel;private final FileLock lock;
        private Guard(Path root)throws IOException {
            safe(root,root.resolve(".jed-builder.lock"));Files.createDirectories(root);
            channel=FileChannel.open(root.resolve(".jed-builder.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try {lock=channel.tryLock();if(lock==null)throw new JEDDefinitionException("A builder save is active in "+root+"; retry shortly");}
            catch(RuntimeException|IOException error){channel.close();throw error;}
        }
        public void close(){try{lock.release();channel.close();}catch(IOException error){throw new JEDDefinitionException("Cannot release builder configuration lock",error);}}
    }
    public static Guard guard(Path root) {
        try{return new Guard(root.toAbsolutePath().normalize());}
        catch(OverlappingFileLockException error){throw new JEDDefinitionException("A builder save is active; retry shortly",error);}
        catch(IOException error){throw new JEDDefinitionException("Cannot lock builder configuration "+root,error);}
    }
    public static Path file(Path root,String relative) {
        Path target=root.toAbsolutePath().normalize().resolve(relative).normalize();safe(root,target);return target;
    }
    public static void safe(Path root,Path target) {
        root=root.toAbsolutePath().normalize();target=target.toAbsolutePath().normalize();
        if(!target.startsWith(root)||target.equals(root))throw new JEDDefinitionException("Builder path escapes its configuration directory: "+target);
        // Only components inside the configuration directory are checked; the directory and its ancestors may be links.
        for(Path check=target;check!=null&&!check.equals(root);check=check.getParent())if(Files.isSymbolicLink(check))throw new JEDDefinitionException("Builder symlinks are not supported: "+check);
    }
    public static String stamp(Path file) {
        try {return Files.exists(file)?JEDDimensionSync.digest(Files.readAllBytes(file)):"<missing>";}
        catch(IOException error){throw new JEDDefinitionException("Cannot fingerprint "+file,error);}
    }
    /** Must hold guard(root). Both the ordinary loader and the builder enter here before reading JSON. */
    public static void recover(Path root) {
        Path journal=file(root,JOURNAL);if(!Files.exists(journal))return;
        var record=JEDJson.object(JEDJson.read(journal),journal.toString());
        JEDJson.keys(record,Set.of("format_version","token","files"),journal.toString());
        if(JEDJson.integer(record.get("format_version"),journal.toString())!=1)throw new JEDDefinitionException("Unsupported builder transaction "+journal);
        String token=JEDJson.string(record.get("token"),journal.toString());
        if(!token.matches("[a-f0-9-]{36}"))throw new JEDDefinitionException("Invalid builder backup token");
        var files=JEDJson.object(record.get("files"),journal.toString());
        // Verify every boundary before finishing any interrupted write. External changes are never overwritten.
        for(var entry:files.entrySet()) {
            if(!entry.getKey().startsWith("data/")||!entry.getKey().endsWith(".json"))throw new JEDDefinitionException("Invalid builder transaction path "+entry.getKey());
            var item=JEDJson.object(entry.getValue(),entry.getKey());JEDJson.keys(item,Set.of("before","after","hash"),entry.getKey());
            Path target=file(root,entry.getKey());if(!target.startsWith(root.toAbsolutePath().normalize().resolve("data")))throw new JEDDefinitionException("Builder transaction escapes data directory");String current=stamp(target);
            String old=JEDJson.string(item.get("hash"),entry.getKey());
            if(!current.equals(old)&&!(Files.isRegularFile(target)&&JEDJson.read(target).equals(item.get("after"))))
                throw new JEDDefinitionException("Builder save recovery found an external edit in "+target+"; retained transaction "+journal);
        }
        for(var entry:files.entrySet()) {
            var item=entry.getValue().getAsJsonObject();Path target=file(root,entry.getKey());
            if(item.has("before")&&!item.get("before").isJsonNull()) {
                Path backup=file(root,"builder-backups/"+token+"/"+entry.getKey());
                if(!Files.exists(backup))JEDJson.writeAtomic(backup,item.get("before"));
            }
            if(!Files.isRegularFile(target)||!JEDJson.read(target).equals(item.get("after")))JEDJson.writeAtomic(target,item.get("after"));
        }
        try{Files.delete(journal);}catch(IOException error){throw new JEDDefinitionException("Cannot finish builder transaction "+journal,error);}
    }
    public static String commit(Path root,Map<String,JsonElement> documents,Map<String,String> expected) {
        try(var ignored=guard(root)) {
            recover(root);
            for(String path:documents.keySet())if(!Objects.equals(expected.get(path),stamp(file(root,path))))
                throw new JEDDefinitionException("Builder save rejected: "+file(root,path)+" changed since preview. Preview again before saving.");
            String token=UUID.randomUUID().toString();var record=new JsonObject();record.addProperty("format_version",1);record.addProperty("token",token);
            var files=new JsonObject();
            for(var entry:documents.entrySet()) {
                Path target=file(root,entry.getKey());var item=new JsonObject();item.addProperty("hash",stamp(target));
                item.add("before",Files.exists(target)?JEDJson.read(target):JsonNull.INSTANCE);item.add("after",entry.getValue().deepCopy());files.add(entry.getKey(),item);
            }
            record.add("files",files);JEDJson.writeAtomic(file(root,JOURNAL),record);recover(root);return token;
        }
    }
    private JEDBuilderFiles(){}
}
