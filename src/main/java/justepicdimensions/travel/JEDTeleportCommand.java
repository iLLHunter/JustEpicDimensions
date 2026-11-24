package justepicdimensions.travel;

import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.*;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.*;
import net.minecraft.commands.arguments.coordinates.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Literal forms avoid ambiguity between entity selectors, dimensions and destination modes. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDTeleportCommand {
    private static final SimpleCommandExceptionType PERMISSION=new SimpleCommandExceptionType(JEDText.tr("travel.permission"));
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        // Only the instance child admits policy-authorized self-service. Every legacy child
        // separately retains the original teleport permission, including its suggestions.
        var root=Commands.literal("tpj").requires(source->ordinary(source)||(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p&&justepicdimensions.instance.JEDInstanceController.commands(p)));
        root.then(justepicdimensions.instance.JEDInstanceCommands.tree());
        root.executes(context->{authorized(context.getSource(),false);context.getSource().sendSuccess(()->Component.literal("/tpj <dimension> [spawn|surface|x y z [yaw pitch]]; /tpj targets <entities> <dimension> ...; /tpj to <entity>; /tpj list"),false);return 1;});
        root.then(Commands.literal("list").requires(JEDTeleportCommand::ordinary).executes(JEDTeleportCommand::list));
        addForms(root,false,false);
        var targets=Commands.literal("targets").requires(JEDTeleportCommand::ordinary).then(addForms(Commands.argument("targets",JEDTravelEntityArgument.multiple()),true,false));root.then(targets);
        root.then(Commands.literal("to").requires(JEDTeleportCommand::ordinary).then(Commands.argument("destination_entity",JEDTravelEntityArgument.single()).executes(context->entity(context,false,false))));
        var bypass=Commands.literal("bypass").requires(source->ordinary(source)&&JEDTeleportService.options(source.getServer()).bypass(source));
        addForms(bypass,false,true);bypass.then(Commands.literal("targets").then(addForms(Commands.argument("targets",JEDTravelEntityArgument.multiple()),true,true)));
        bypass.then(Commands.literal("to").then(Commands.argument("destination_entity",JEDTravelEntityArgument.single()).executes(context->entity(context,false,true))));root.then(bypass);
        event.getDispatcher().register(root);
    }
    private static <T extends ArgumentBuilder<CommandSourceStack,T>> T addForms(T parent,boolean targets,boolean bypass){
        var dimension=Commands.argument("dimension",ResourceLocationArgument.id()).requires(JEDTeleportCommand::ordinary).suggests((context,builder)->SharedSuggestionProvider.suggestResource(JEDTeleportService.destinations(context.getSource().getLevel()).stream().map(JEDTeleportService.Destination::id),builder));
        dimension.executes(context->dimension(context,targets,bypass,true,false,false));
        dimension.then(Commands.literal("spawn").executes(context->dimension(context,targets,bypass,true,false,false)));
        dimension.then(Commands.literal("surface").executes(context->dimension(context,targets,bypass,false,true,false)));
        var position=Commands.argument("position",Vec3Argument.vec3()).executes(context->dimension(context,targets,bypass,false,false,false));
        position.then(Commands.argument("rotation",RotationArgument.rotation()).executes(context->dimension(context,targets,bypass,false,false,true)));
        position.then(Commands.literal("surface").executes(context->dimension(context,targets,bypass,false,true,false)));
        dimension.then(position);parent.then(dimension);
        if(targets)parent.then(Commands.literal("to").then(Commands.argument("destination_entity",JEDTravelEntityArgument.single()).executes(context->entity(context,true,bypass))));
        return parent;
    }
    private static boolean ordinary(CommandSourceStack source){return JEDTeleportService.options(source.getServer()).permits(source);}
    private static void authorized(CommandSourceStack source,boolean bypass)throws CommandSyntaxException{
        var options=JEDTeleportService.options(source.getServer());if(!options.permits(source)||(bypass&&!options.bypass(source)))throw PERMISSION.create();
    }
    private static int list(CommandContext<CommandSourceStack> context)throws CommandSyntaxException{
        var source=context.getSource();authorized(source,false);var dimensions=JEDTeleportService.destinations(source.getLevel()).stream().filter(d->!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p)||justepicdimensions.access.JEDAccessController.check(p,d.id(),false,justepicdimensions.access.JEDAccessController.Arrival.TRAVEL).allowed()||!justepicdimensions.access.JEDAccessController.policy(source.getServer(),d.id()).visibility().equals("hidden")).toList();
        source.sendSuccess(()->JEDText.tr("travel.destinations",dimensions.size()),false);
        for(var dimension:dimensions){var access=source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p?justepicdimensions.access.JEDAccessController.check(p,dimension.id(),false,justepicdimensions.access.JEDAccessController.Arrival.TRAVEL):justepicdimensions.access.JEDAccessController.Decision.ALLOW;var line=Component.literal(dimension.id()+" — ").append(dimension.name()).withStyle(style->style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/tpj "+dimension.id())).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,JEDText.tr("travel.click",dimension.id()))));if(!access.allowed())line.append(" ").append(justepicdimensions.access.JEDAccessRule.text("locked","[Locked]")).withStyle(net.minecraft.ChatFormatting.GRAY).setStyle(line.getStyle().withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,access.reason())));source.sendSuccess(()->line,false);}
        return dimensions.size();
    }
    // Only selector resolution receives vanilla's level-two capability. The original source is
    // authorized first, and retains its actual permission for command execution and bypass checks.
    private static CommandContext<CommandSourceStack> selectorContext(CommandContext<CommandSourceStack> context){return context.copyFor(context.getSource().withPermission(2));}
    private static Collection<? extends Entity> targets(CommandContext<CommandSourceStack> context,boolean selected)throws CommandSyntaxException{return selected?EntityArgument.getEntities(selectorContext(context),"targets"):List.of(context.getSource().getEntityOrException());}
    private static int entity(CommandContext<CommandSourceStack> context,boolean selected,boolean bypass)throws CommandSyntaxException{
        authorized(context.getSource(),bypass);var destination=EntityArgument.getEntity(selectorContext(context),"destination_entity");
        return start(context,targets(context,selected),new JEDTeleportService.Request(destination.level().dimension().location(),destination.position(),destination.getYRot(),destination.getXRot(),false,false,bypass,false,false,null));
    }
    private static int dimension(CommandContext<CommandSourceStack> context,boolean selected,boolean bypass,boolean spawn,boolean surface,boolean rotation)throws CommandSyntaxException{
        var source=context.getSource();authorized(source,bypass);Vec3 position=spawn?null:surface&&!context.getNodes().stream().anyMatch(node->node.getNode().getName().equals("position"))?source.getPosition():Vec3Argument.getVec3(context,"position");
        var angles=rotation?RotationArgument.getRotation(context,"rotation").getRotation(source):null;
        return start(context,targets(context,selected),new JEDTeleportService.Request(ResourceLocationArgument.getId(context,"dimension"),position,angles==null?null:angles.y,angles==null?null:angles.x,spawn,surface,bypass,JEDTeleportService.policy(source.getLevel()).passengers().equals("together"),false,null));
    }
    private static int start(CommandContext<CommandSourceStack> context,Collection<? extends Entity> entities,JEDTeleportService.Request request){
        var source=context.getSource();var roots=Collections.newSetFromMap(new IdentityHashMap<Entity,Boolean>());int count=0;
        for(var entity:entities){boolean together=JEDTeleportService.policy((net.minecraft.server.level.ServerLevel)entity.level()).passengers().equals("together");var root=together?entity.getRootVehicle():entity;if(!roots.add(root))continue;count++;
            var selected=new JEDTeleportService.Request(request.dimension(),request.position(),request.yaw(),request.pitch(),request.spawn(),request.surface(),request.bypass(),together,request.automatic(),request.rule());
            JEDTeleportService.travel(entity,selected,source).whenComplete((arrived,error)->{
                if(error!=null){justepicdimensions.admin.JEDCommandResults.failure(source,"travel of "+entity.getName().getString(),JEDText.tr("travel.failed_for",entity.getName().getString(),JEDText.error(error)),false);return;}
                justepicdimensions.admin.JEDCommandResults.success(source,"travel",JEDText.tr("travel.teleported",arrived.getName().getString(),arrived.level().dimension().location(),String.format(java.util.Locale.ROOT,"%.1f %.1f %.1f",arrived.getX(),arrived.getY(),arrived.getZ())),true);
            });
        }if(count>0)justepicdimensions.admin.JEDCommandResults.startedForConsole(source,"travel");return count;
    }
    private JEDTeleportCommand(){}
}
