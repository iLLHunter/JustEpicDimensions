package justepicdimensions.definition;

import com.mojang.brigadier.arguments.StringArgumentType;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDDefinitionCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jed").requires(source -> source.hasPermission(2))
            .then(Commands.literal("inspect")
                .then(Commands.argument("dimension", ResourceLocationArgument.id())
                    .suggests((context, builder) -> {
                        JEDDefinitionManager.state(context.getSource().getServer()).definitions().keySet().forEach(id -> builder.suggest(id.toString()));
                        return builder.buildFuture();
                    })
                    .executes(context -> inspect(context.getSource(), ResourceLocationArgument.getId(context, "dimension"), "lighting.client_light_check"))
                    .then(Commands.argument("property", StringArgumentType.word()).executes(context ->
                        inspect(context.getSource(), ResourceLocationArgument.getId(context, "dimension"), StringArgumentType.getString(context, "property"))))))
            .then(Commands.literal("create").then(Commands.argument("dimension", ResourceLocationArgument.id()).executes(context -> lifecycle(context, "create"))))
            .then(Commands.literal("load").then(Commands.argument("dimension", ResourceLocationArgument.id()).executes(context -> lifecycle(context, "load"))))
            .then(Commands.literal("unload").then(Commands.argument("dimension", ResourceLocationArgument.id()).executes(context -> lifecycle(context, "unload"))))
            .then(Commands.literal("reload-main-config").executes(context -> {
                var source=context.getSource();
                try {var next=JEDDefinitionManager.reloadMainConfig(source.getServer());source.sendSuccess(()->JEDText.tr("command.reload_main",next.pendingRestart().isEmpty()?JEDText.tr("command.reload_applied"):JEDText.tr("command.reload_pending",next.pendingRestart())),true);return 1;}
                catch(Exception error){source.sendFailure(JEDText.tr("command.reload_main_failed",JEDText.error(error)));return 0;}
            }))
            .then(Commands.literal("reload").executes(context -> {
                var source = context.getSource(); var server = source.getServer();
                justepicdimensions.admin.JEDCommandResults.startedForConsole(source, "reload");
                server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((ignored, error) -> {
                    if (error == null) justepicdimensions.admin.JEDCommandResults.success(source, "reload", JEDText.tr("command.reloaded"), true);
                    else justepicdimensions.admin.JEDCommandResults.failure(source, "reload", JEDText.tr("command.reload_failed",JEDText.error(error)), true);
                });
                return 1;
            })));
        for (String operation : java.util.List.of("register", "unregister", "reset", "delete")) {
            var argument = Commands.argument("dimension", ResourceLocationArgument.id()).executes(context -> transaction(context,operation,false));
            if (!operation.equals("register")) argument.then(Commands.literal("relocate").executes(context -> transaction(context,operation,true)));
            event.getDispatcher().register(Commands.literal("jed").requires(source -> source.hasPermission(2)).then(Commands.literal(operation).then(argument)));
        }
        var removal=Commands.argument("dimension",ResourceLocationArgument.id())
            .then(Commands.literal("preview").executes(context->{
                var source=context.getSource();try{
                    var pending=justepicdimensions.dimension.JEDDefinitionRemoval.preview(source,ResourceLocationArgument.getId(context,"dimension"));
                    justepicdimensions.admin.JEDCommandResults.startedForConsole(source,"unregister-remove preview");
                    pending.whenComplete((review,error)->source.getServer().execute(()->{
                        if(error!=null){justepicdimensions.admin.JEDCommandResults.failure(source,"unregister-remove preview",JEDText.tr("command.removal_failed",JEDText.error(error)),true);return;}
                        justepicdimensions.admin.JEDCommandResults.success(source,"unregister-remove preview",JEDText.tr("command.removal_review",review.get("dimension").getAsString(),JEDText.tr(review.get("global").getAsBoolean()?"command.removal_global":"command.removal_world"),review.get("root").getAsString()),false);
                        review.getAsJsonObject("files").entrySet().forEach(file->justepicdimensions.admin.JEDCommandResults.success(source,"unregister-remove preview",JEDText.tr(file.getValue().getAsJsonObject().get("hash").getAsString().equals("<missing>")?"command.no_file":"command.backup_remove",file.getKey()),false));
                        justepicdimensions.admin.JEDCommandResults.success(source,"unregister-remove preview",JEDText.tr("command.confirm","/jed unregister-remove "+review.get("dimension").getAsString()+" confirm "+review.get("token").getAsString()+" [relocate]"),false);
                    }));return 1;
                }catch(Exception error){source.sendFailure(JEDText.error(error));return 0;}
            }))
            .then(Commands.literal("confirm").then(Commands.argument("review_token",StringArgumentType.word())
                .executes(context->remove(context,false)).then(Commands.literal("relocate").executes(context->remove(context,true)))));
        event.getDispatcher().register(Commands.literal("jed").requires(source->source.hasPermission(2)).then(Commands.literal("unregister-remove").then(removal)));
        event.getDispatcher().register(Commands.literal("jed").requires(source -> source.hasPermission(2))
            .then(Commands.literal("purge").then(Commands.argument("trash_token",StringArgumentType.word()).executes(context -> {
                var source = context.getSource();
                try {
                    var pending = justepicdimensions.dimension.JEDDimensionLifecycle.purge(source.getServer(),StringArgumentType.getString(context,"trash_token"));
                    justepicdimensions.admin.JEDCommandResults.startedForConsole(source,"purge"); completion(source,"purge",pending); return 1;
                } catch (Exception error) { source.sendFailure(JEDText.error(error)); return 0; }
            }))));
    }
    private static int remove(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,boolean relocate){
        var source=context.getSource();try{var pending=justepicdimensions.dimension.JEDDefinitionRemoval.confirm(source,ResourceLocationArgument.getId(context,"dimension"),StringArgumentType.getString(context,"review_token"),relocate);justepicdimensions.admin.JEDCommandResults.startedForConsole(source,"unregister-remove "+ResourceLocationArgument.getId(context,"dimension"));completion(source,"unregister-remove "+ResourceLocationArgument.getId(context,"dimension"),pending);return 1;}
        catch(Exception error){source.sendFailure(JEDText.error(error));return 0;}
    }
    private static int transaction(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,String operation,boolean relocate) {
        var source = context.getSource(); var id = ResourceLocationArgument.getId(context,"dimension");
        try {
            var pending = operation.equals("register") ? justepicdimensions.dimension.JEDDimensionLifecycle.reactivate(source.getServer(),id)
                : justepicdimensions.dimension.JEDDimensionLifecycle.operate(source.getServer(),id,justepicdimensions.dimension.JEDDimensionLifecycle.Action.valueOf(operation.toUpperCase(java.util.Locale.ROOT)),relocate);
            justepicdimensions.admin.JEDCommandResults.started(source, JEDText.tr("command.preparing",operation,id)); completion(source,operation + " " + id,pending); return 1;
        } catch (Exception error) { source.sendFailure(JEDText.tr("command.failed",operation,JEDText.error(error))); return 0; }
    }
    private static void completion(net.minecraft.commands.CommandSourceStack source,String operation,java.util.concurrent.CompletableFuture<Void> pending) {
        pending.whenComplete((ignored,error) -> {
            if (error == null) justepicdimensions.admin.JEDCommandResults.success(source, operation, JEDText.tr("command.complete",operation), true);
            else justepicdimensions.admin.JEDCommandResults.failure(source, operation, JEDText.tr("command.failed",operation,JEDText.error(error)), true);
        });
    }
    private static int lifecycle(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, String operation) {
        var source = context.getSource(); var id = ResourceLocationArgument.getId(context, "dimension");
        try {
            if (operation.equals("create")) {
                var pending = justepicdimensions.dimension.JEDDimensionManager.create(source.getServer(), id);
                justepicdimensions.admin.JEDCommandResults.started(source, JEDText.tr("command.dimension_preparing",id));
                pending.whenComplete((ignored, error) -> {
                    if (error == null) justepicdimensions.admin.JEDCommandResults.success(source, "create " + id, JEDText.tr("command.dimension_saved",id), true);
                    else justepicdimensions.admin.JEDCommandResults.failure(source, "create " + id, JEDText.tr("command.created_failed",JEDText.error(error)), true);
                });
                return 1;
            }
            switch (operation) {
                case "load" -> justepicdimensions.dimension.JEDDimensionManager.load(source.getServer(), id);
                case "unload" -> { justepicdimensions.admin.JEDCommandResults.startedForConsole(source,"unload " + id); completion(source,"unload " + id,justepicdimensions.dimension.JEDDimensionManager.unloadAsync(source.getServer(), id)); return 1; }
            }
            source.sendSuccess(() -> JEDText.tr("command.operation",operation,id), true); return 1;
        } catch (Exception error) { source.sendFailure(JEDText.tr("command.failed",operation,JEDText.error(error))); return 0; }
    }
    private static int inspect(net.minecraft.commands.CommandSourceStack source, net.minecraft.resources.ResourceLocation id, String property) {
        try { String result = JEDDefinitionManager.explain(source.getServer(), id, property); source.sendSuccess(() -> Component.literal(result), false); return 1; }
        catch (JEDDefinitionException error) { source.sendFailure(JEDText.error(error)); return 0; }
    }
    private JEDDefinitionCommands() {}
}
