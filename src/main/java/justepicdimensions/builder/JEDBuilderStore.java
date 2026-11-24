package justepicdimensions.builder;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.definition.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Save-scoped private drafts. The console has a separate stable workspace. */
public final class JEDBuilderStore {
    public record Open(Path file,String stamp,JEDBuilderDraft draft) {
        public void save(){JEDBuilderFiles.safe(file.getParent(),file);if(!stamp.equals(JEDBuilderFiles.stamp(file)))throw new JEDDefinitionException("Draft changed while the operation was preparing; retry");JEDJson.writeAtomic(file,draft.json());}
    }
    public static String owner(CommandSourceStack source){return source.getEntity() instanceof ServerPlayer player?player.getUUID().toString():"console";}
    public static Path directory(CommandSourceStack source){return source.getServer().getWorldPath(LevelResource.ROOT).resolve("data/justepicdimensions/builder/"+owner(source)).toAbsolutePath().normalize();}
    private static Path file(CommandSourceStack source,String name){if(!name.matches("[a-z0-9_-]{1,64}")||Set.of("new","copy","list").contains(name))throw new JEDDefinitionException("Draft names use 1–64 lowercase letters, digits, underscores or hyphens; new, copy and list are reserved");return JEDBuilderFiles.file(directory(source),name+".json");}
    public static Open open(CommandSourceStack source,String name){Path file=file(source,name);if(!Files.isRegularFile(file))throw justepicdimensions.JEDText.failure("builder.no_draft",name);String before=JEDBuilderFiles.stamp(file);var draft=new JEDBuilderDraft(JEDJson.object(JEDJson.read(file),file.toString()));if(!before.equals(JEDBuilderFiles.stamp(file)))throw new JEDDefinitionException("Draft changed while reading; retry");return new Open(file,before,draft);}
    public static void create(CommandSourceStack source,String name){Path file=file(source,name);if(Files.exists(file))throw justepicdimensions.JEDText.failure("builder.draft_exists",name);JEDJson.writeAtomic(file,new JEDBuilderDraft().json());}
    public static void edit(CommandSourceStack source,String name,Consumer<JEDBuilderDraft> action){var open=open(source,name);action.accept(open.draft());open.save();}
    public static List<String> list(CommandSourceStack source){Path root=directory(source);if(!Files.isDirectory(root))return List.of();try(var files=Files.list(root)){return files.filter(path->path.getFileName().toString().matches("[a-z0-9_-]{1,64}\\.json")).map(path->path.getFileName().toString().replaceFirst("\\.json$","")).sorted().toList();}catch(IOException error){throw new JEDDefinitionException("Cannot list builder drafts",error);}}
    private JEDBuilderStore(){}
}
