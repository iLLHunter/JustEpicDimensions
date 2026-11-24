package justepicdimensions.instance;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import justepicdimensions.definition.JEDDefinitionException;

/** Every action rechecks the current policy and actor on the server. */
public final class JEDInstanceCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> tree(){
        var root=Commands.literal("instance").executes(c->help(c.getSource()));
        root.then(Commands.literal("list").executes(c->list(c.getSource())));
        root.then(Commands.literal("exit").executes(c->future(c.getSource(),JEDInstanceController.exit(player(c.getSource())),"Instance exit completed")));
        root.then(Commands.literal("create").then(source().executes(c->create(c.getSource(),ResourceLocationArgument.getId(c,"source"),"default",null))
            .then(Commands.argument("run",StringArgumentType.word()).executes(c->create(c.getSource(),ResourceLocationArgument.getId(c,"source"),StringArgumentType.getString(c,"run"),null))
                .then(justepicdimensions.teams.JEDTeamCommands.directCreate()).then(Commands.argument("group",StringArgumentType.word()).executes(c->create(c.getSource(),ResourceLocationArgument.getId(c,"source"),StringArgumentType.getString(c,"run"),UUID.fromString(StringArgumentType.getString(c,"group"))))))));
        for(String action:List.of("enter","select","reset","retire"))root.then(Commands.literal(action).then(instance().executes(c->{var p=player(c.getSource());var id=ResourceLocationArgument.getId(c,"instance");if(action.equals("select")){JEDInstanceController.select(p,id);c.getSource().sendSuccess(()->Component.literal("Selected "+id),false);return 1;}return future(c.getSource(),action.equals("enter")?JEDInstanceController.enter(p,id):JEDInstanceController.manage(p,id,action),"Instance "+action+" completed: "+id);})));
        var group=Commands.literal("group");group.then(Commands.literal("create").then(source().then(Commands.argument("name",StringArgumentType.greedyString()).executes(c->{var id=JEDInstanceController.group(player(c.getSource()),ResourceLocationArgument.getId(c,"source"),StringArgumentType.getString(c,"name"));c.getSource().sendSuccess(()->Component.literal("Created instance group "+id),false);return 1;}))));
        for(String kind:List.of("members","managers")){var kindNode=Commands.literal(kind);for(boolean add:new boolean[]{true,false})kindNode.then(Commands.literal(add?"add":"remove").then(instance().then(Commands.argument("player_uuid",StringArgumentType.word()).executes(c->{JEDInstanceController.groupMember(player(c.getSource()),ResourceLocationArgument.getId(c,"instance"),UUID.fromString(StringArgumentType.getString(c,"player_uuid")),add,kind.equals("managers"));c.getSource().sendSuccess(()->Component.literal("Instance group updated"),false);return 1;}))));group.then(kindNode);}root.then(group);justepicdimensions.teams.JEDTeamCommands.extend(root);return root;
    }
    private static ServerPlayer player(CommandSourceStack source){var p=source.getPlayer();if(p==null)throw new JEDDefinitionException("This ownership action requires an in-game player");return p;}
    private static RequiredArgumentBuilder<CommandSourceStack,ResourceLocation> source(){return Commands.argument("source",ResourceLocationArgument.id()).suggests((c,b)->{justepicdimensions.definition.JEDDefinitionManager.state(c.getSource().getServer()).definitions().forEach((id,d)->{if(d.instances()!=null&&(c.getSource().getPlayer()==null||justepicdimensions.access.JEDAccessController.check(c.getSource().getPlayer(),id,false,justepicdimensions.access.JEDAccessController.Arrival.TRAVEL).allowed()||!justepicdimensions.access.JEDAccessController.policy(c.getSource().getServer(),id).visibility().equals("hidden")))b.suggest(id.toString());});return b.buildFuture();});}
    private static RequiredArgumentBuilder<CommandSourceStack,ResourceLocation> instance(){return Commands.argument("instance",ResourceLocationArgument.id()).suggests((c,b)->{var p=c.getSource().getPlayer();for(var r:JEDInstanceController.instances(c.getSource().getServer()))if(r.active()&&(c.getSource().hasPermission(2)||p!=null&&JEDInstanceController.eligible(p,r)))b.suggest(r.dimension().toString());return b.buildFuture();});}
    private static int create(CommandSourceStack source,ResourceLocation dimension,String run,UUID group){var p=player(source);return future(source,JEDInstanceController.create(p,dimension,run,group).thenApplyAsync(id->{JEDInstanceController.select(p,id);return id;},p.server),"Instance ready");}
    private static int future(CommandSourceStack source,CompletableFuture<?> result,String message){justepicdimensions.admin.JEDCommandResults.startedForConsole(source,"instance command");result.whenCompleteAsync((value,error)->{if(error!=null)justepicdimensions.admin.JEDCommandResults.failure(source,"instance command",justepicdimensions.JEDText.error(error),false);else justepicdimensions.admin.JEDCommandResults.success(source,"instance command",Component.literal(message+(value instanceof ResourceLocation?": "+value:"")),false);},source.getServer());return 1;}
    private static int list(CommandSourceStack source){var p=source.getPlayer();int count=0;for(var r:JEDInstanceController.instances(source.getServer()))if(source.hasPermission(2)||p!=null&&JEDInstanceController.eligible(p,r)){source.sendSuccess(()->Component.literal(r.dimension()+" — "+r.source()+" / "+r.run()+" / "+r.status()),false);count++;}
        if(count==0){var sources=p==null?List.<net.minecraft.resources.ResourceLocation>of():JEDInstanceController.creatableSources(p);
            if(sources.isEmpty())source.sendSuccess(()->justepicdimensions.JEDText.tr("instance.list_empty"),false);
            else{
                // One clickable row per source this server set up, with its display name, so the IDs read as this server's own.
                source.sendSuccess(()->justepicdimensions.JEDText.tr("instance.list_empty_create"),false);
                for(var id:sources){String command="instance create "+id;var line=Component.literal("/tpj "+command+" — ").append(justepicdimensions.travel.JEDTeleportService.name(source.getServer(),id)).withStyle(style->style.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,"/tpj "+command)).withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,justepicdimensions.JEDText.tr("travel.click",command))));source.sendSuccess(()->line,false);}
            }}
        return count;}
    private static int help(CommandSourceStack source){source.sendSuccess(()->Component.literal("/tpj instance <list|create source [run [group UUID]]|select instance|enter instance|exit|reset instance|retire instance|group ...>"),false);return 1;}
}
