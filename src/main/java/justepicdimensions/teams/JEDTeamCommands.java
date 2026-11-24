package justepicdimensions.teams;

import java.util.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import justepicdimensions.definition.JEDDefinitionException;
import justepicdimensions.instance.*;

public final class JEDTeamCommands {
    public static void extend(LiteralArgumentBuilder<CommandSourceStack> root){
        root.then(Commands.literal("native-owner").requires(s->s.hasPermission(2)).then(word("team-id").then(word("player-id").executes(c->{JEDTeamPermissions.assignNative(c.getSource().getServer(),uuid(c,"team-id"),uuid(c,"player-id"));return done(c,"Native-team JED owner assigned");}))));
        root.then(Commands.literal("repair-owner").requires(s->s.hasPermission(2)).then(Commands.argument("instance",ResourceLocationArgument.id()).then(system().then(word("group-id").executes(c->{JEDInstanceController.repairOwner(c.getSource(),ResourceLocationArgument.getId(c,"instance"),key(c));return done(c,"Instance ownership repaired");})))));
        root.then(Commands.literal("ownership").requires(s->s.hasPermission(2)).executes(c->{var player=player(c);JEDTeamGateway.open(player,"admin",null);return 1;}));
        root.then(Commands.literal("teams").executes(c->list(c,null)).then(system().executes(c->list(c,StringArgumentType.getString(c,"system")))));
        var permissions=Commands.literal("permissions");
        var source=Commands.argument("source",ResourceLocationArgument.id()).executes(c->{JEDTeamGateway.open(player(c),"permissions",ResourceLocationArgument.getId(c,"source"));return 1;});
        var identity=word("group-id");identity.then(edits(false));identity.then(Commands.literal("instance").then(Commands.argument("instance",ResourceLocationArgument.id()).then(edits(true))));
        var managers=Commands.literal("native-manager");for(boolean add:new boolean[]{true,false})managers.then(Commands.literal(add?"add":"remove").then(word("player-id").executes(c->{var actor=player(c);var key=key(c);if(!key.system().equals("minecraft"))throw new JEDDefinitionException("Select a native team");JEDInstanceController.requireDelegation(actor,ResourceLocationArgument.getId(c,"source"),key,null);JEDTeamPermissions.nativeManager(actor.server,key.id(),uuid(c,"player-id"),add);return done(c,"Native-team JED managers updated");})));identity.then(managers);
        source.then(system().then(identity));permissions.then(source);root.then(permissions);
    }
    public static LiteralArgumentBuilder<CommandSourceStack> directCreate(){
        return Commands.literal("owner").then(system().then(word("group-id").executes(c->{var player=player(c);var work=JEDInstanceController.create(player,ResourceLocationArgument.getId(c,"source"),StringArgumentType.getString(c,"run"),null,key(c));work.whenCompleteAsync((id,error)->{if(error!=null)c.getSource().sendFailure(justepicdimensions.JEDText.error(error));else{JEDInstanceController.select(player,id);c.getSource().sendSuccess(()->Component.literal("Instance ready: "+id),false);}},player.server);return 1;})));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> edits(boolean instance){
        var scope=Commands.literal(instance?"actions":"team");
        scope.then(Commands.literal("set").then(action().then(word("level").suggests((c,b)->{for(String v:List.of("owner","managers","members","inherit"))b.suggest(v);return b.buildFuture();}).executes(c->{var actor=player(c);var key=key(c);var target=instance?ResourceLocationArgument.getId(c,"instance"):null;JEDInstanceController.requireDelegation(actor,ResourceLocationArgument.getId(c,"source"),key,target);JEDTeamPermissions.set(actor.server,key,target,StringArgumentType.getString(c,"permission"),StringArgumentType.getString(c,"level"));return done(c,"Delegation level updated");}))));
        for(boolean add:new boolean[]{true,false})scope.then(Commands.literal(add?"grant":"revoke").then(action().then(word("player-id").executes(c->{var actor=player(c);var key=key(c);var source=ResourceLocationArgument.getId(c,"source");var target=instance?ResourceLocationArgument.getId(c,"instance"):null;JEDInstanceController.requireDelegation(actor,source,key,target);var policy=JEDInstanceController.sourcePolicy(actor.server,source);String action=StringArgumentType.getString(c,"permission");if(!JEDTeamPermissions.ACTIONS.contains(action))throw new JEDDefinitionException("Unknown instance action");JEDTeamPermissions.grant(actor.server,key,target,action,uuid(c,"player-id"),add,policy.permissions().get(action).mode());return done(c,"Individual delegation updated");}))));
        return scope;
    }
    private static int list(CommandContext<CommandSourceStack> c,String filter){int count=0;var source=c.getSource();var player=source.getPlayer();for(String system:JEDTeamSystems.SYSTEMS)if(filter==null||system.equals(filter))for(var group:JEDTeamSystems.groups(source.getServer(),system))if(source.hasPermission(2)||player!=null&&JEDTeamSystems.member(source.getServer(),group,player.getUUID())){source.sendSuccess(()->Component.literal(group.key()+" | "+group.name()+" | "+group.kind()),false);count++;}return count;}
    private static RequiredArgumentBuilder<CommandSourceStack,String> word(String name){return Commands.argument(name,StringArgumentType.word());}
    private static RequiredArgumentBuilder<CommandSourceStack,String> system(){return word("system").suggests((c,b)->{JEDTeamSystems.SYSTEMS.forEach(b::suggest);return b.buildFuture();});}
    private static RequiredArgumentBuilder<CommandSourceStack,String> action(){return word("permission").suggests((c,b)->{JEDTeamPermissions.ACTIONS.forEach(b::suggest);return b.buildFuture();});}
    private static UUID uuid(CommandContext<CommandSourceStack> c,String key){return UUID.fromString(StringArgumentType.getString(c,key));}
    private static JEDTeamSystems.Key key(CommandContext<CommandSourceStack> c){return new JEDTeamSystems.Key(StringArgumentType.getString(c,"system"),uuid(c,"group-id"));}
    private static ServerPlayer player(CommandContext<CommandSourceStack> c){var player=c.getSource().getPlayer();if(player==null)throw new JEDDefinitionException("Use explicit ownership commands from the console; this control requires a player");return player;}
    private static int done(CommandContext<CommandSourceStack> c,String text){c.getSource().sendSuccess(()->Component.literal(text),false);return 1;}
    private JEDTeamCommands(){}
}
