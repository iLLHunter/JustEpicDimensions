package justepicdimensions.bundle;

import com.google.gson.*;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import justepicdimensions.definition.*;

@EventBusSubscriber(modid="justepicdimensions")
public final class JEDBundleCommands {
    public static List<ResourceLocation> dimensions(String text){var result=new ArrayList<ResourceLocation>();for(String part:text.split(",",-1))result.add(JEDDimensionDefinition.id(part.trim(),"bundle dimensions"));return List.copyOf(result);}
    public static List<String> extras(String text){var value=JEDJson.read(new StringReader(text),"bundle dependencies");if(!value.isJsonArray())throw new JEDDefinitionException("Extra dependency files must be a JSON array");return value.getAsJsonArray().asList().stream().map(v->JEDJson.string(v,"dependency file")).toList();}
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var export=Commands.argument("arguments",StringArgumentType.greedyString()).executes(c->run(c.getSource(),"bundle export",()->{String[] parts=StringArgumentType.getString(c,"arguments").strip().split("[ \t]+",3);String mode=parts.length>1?parts[1]:"linked";if(!Set.of("linked","resolved").contains(mode))throw new JEDDefinitionException("Choose linked or resolved export mode");return JEDBundleService.export(c.getSource(),StringArgumentType.getString(c,"file"),dimensions(parts[0]),mode,parts.length>2?extras(parts[2]):List.of());}));
        var root=Commands.literal("bundle").executes(c->{c.getSource().sendSuccess(()->Component.literal("/jed bundle export <file> <dimension[,dimension...]> [linked|resolved] [extra-files JSON]\n/jed bundle preview <file>\n/jed bundle import <file> <preview-token> [replace]\nPut incoming ZIPs in the active configuration's bundles/imports/ directory. Exports are written to bundles/exports/."),false);return 1;})
            .then(Commands.literal("export").then(Commands.argument("file",StringArgumentType.word()).then(export)))
            .then(Commands.literal("preview").then(Commands.argument("file",StringArgumentType.word()).executes(c->run(c.getSource(),"bundle preview",()->JEDBundleService.preview(c.getSource(),StringArgumentType.getString(c,"file"))))))
            .then(Commands.literal("import").then(Commands.argument("file",StringArgumentType.word()).then(Commands.argument("token",StringArgumentType.word()).executes(c->run(c.getSource(),"bundle import",()->JEDBundleService.install(c.getSource(),StringArgumentType.getString(c,"file"),StringArgumentType.getString(c,"token"),false)))
                .then(Commands.literal("replace").executes(c->run(c.getSource(),"bundle import",()->JEDBundleService.install(c.getSource(),StringArgumentType.getString(c,"file"),StringArgumentType.getString(c,"token"),true)))))));
        event.getDispatcher().register(Commands.literal("jed").requires(s->s.hasPermission(2)).then(root));
    }
    private static int run(CommandSourceStack source,String operation,java.util.function.Supplier<CompletableFuture<JsonObject>> work){
        try{
            // An operation refused before any background work has nothing to announce; its result follows directly.
            var pending=work.get();if(!pending.isDone())justepicdimensions.admin.JEDCommandResults.started(source,justepicdimensions.JEDText.tr("command.started",operation));
            pending.whenComplete((reply,error)->{
                if(error!=null){justepicdimensions.admin.JEDCommandResults.failure(source,operation,justepicdimensions.JEDText.error(error),true);return;}
                com.mojang.logging.LogUtils.getLogger().info("JED bundle result: {}",new GsonBuilder().setPrettyPrinting().create().toJson(reply));
                justepicdimensions.admin.JEDCommandResults.success(source,operation,Component.literal(reply.get("message").getAsString()),false);
                if(reply.has("token"))justepicdimensions.admin.JEDCommandResults.success(source,operation,Component.literal("Preview token: "+reply.get("token").getAsString()+"; "+reply.getAsJsonArray("changes").size()+" files. Full preview written to the server log."),false);
            });return 1;
        }catch(Exception error){source.sendFailure(justepicdimensions.JEDText.error(error));return 0;}
    }
    private JEDBundleCommands(){}
}
