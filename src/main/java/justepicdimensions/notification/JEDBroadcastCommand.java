package justepicdimensions.notification;

import com.mojang.brigadier.arguments.StringArgumentType;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.api.lifecycle.JEDOperation;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Upstream's command-event notification bridge. Manual use never creates an authoritative completion. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDBroadcastCommand {
    @SubscribeEvent public static void register(RegisterCommandsEvent event){event.getDispatcher().register(Commands.literal("jed").requires(source->source.hasPermission(2))
        .then(Commands.literal("broadcast").executes(context->1).then(Commands.argument("message",StringArgumentType.greedyString()).executes(context->1))));}
    static void emit(MinecraftServer server,JEDOperation operation){
        if(operation.kind()!=JEDOperation.Kind.RESET&&operation.kind()!=JEDOperation.Kind.DELETED)return;
        for(var dimension:operation.dimensions().keySet())server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"jed broadcast delete-dimension "+dimension+" "+operation.id());
    }
    private JEDBroadcastCommand(){}
}
