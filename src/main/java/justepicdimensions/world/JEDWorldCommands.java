package justepicdimensions.world;

import java.util.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.*;
import justepicdimensions.JustEpicDimensions;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.timers.TimerQueue;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Native parsers and native feedback, with explicit dimension ownership at engine call sites. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDWorldCommands {
    private static final Set<CommandSourceStack> EXPLICIT=Collections.newSetFromMap(new WeakHashMap<>());
    public static boolean scoped(CommandSourceStack source){return EXPLICIT.contains(source)||(JEDWorldControl.options(source.getServer()).redirect() && source.getLevel().getLevelData() instanceof JEDLevelData);}
    public static Iterable<ServerLevel> levels(MinecraftServer server,CommandSourceStack source){return scoped(source)?List.of(source.getLevel()):server.getAllLevels();}
    public static ServerLevel level(MinecraftServer server,CommandSourceStack source){return scoped(source)?source.getLevel():server.overworld();}
    public static GameRules rules(MinecraftServer server,CommandSourceStack source){return scoped(source)?source.getLevel().getGameRules():server.getGameRules();}
    public static TimerQueue<MinecraftServer> timers(ServerLevelData nativeData,CommandSourceStack source){return (scoped(source)?(ServerLevelData)source.getLevel().getLevelData():nativeData).getScheduledEvents();}
    public static CommandSourceStack contextSource(CommandContext<CommandSourceStack> context){return context.getSource();}
    public static int difficulty(CommandSourceStack source,Difficulty value) throws CommandSyntaxException {
        if(!scoped(source))return Integer.MIN_VALUE;
        var data=(ServerLevelData)source.getLevel().getLevelData();
        if(data.getDifficulty()==value)throw new DynamicCommandExceptionType(v->Component.translatableEscape("commands.difficulty.failure",v)).create(value.getKey());
        if(data instanceof JEDLevelData independent)independent.setDifficulty(value);else ((net.minecraft.world.level.storage.PrimaryLevelData)data).setDifficulty(value);
        JEDWorldControl.spawnSettings(source.getLevel(),source.getServer().isSpawningMonsters(),source.getServer().isSpawningAnimals());JEDWorldSync.changed(source.getLevel());
        source.sendSuccess(()->Component.translatable("commands.difficulty.success",value.getDisplayName()),true);return 0;
    }
    public static int mode(CommandSourceStack source,GameType mode){
        if(!scoped(source))return Integer.MIN_VALUE;
        ((ServerLevelData)source.getLevel().getLevelData()).setGameType(mode);int changed=0;
        if(source.getServer().getForcedGameType()!=null)for(var player:source.getLevel().players())if(player.setGameMode(mode))changed++;
        JEDWorldSync.changed(source.getLevel());source.sendSuccess(()->Component.translatable("commands.defaultgamemode.success",mode.getLongDisplayName()),true);return changed;
    }
    public static int spawn(CommandSourceStack source,net.minecraft.core.BlockPos pos,float angle){
        if(!scoped(source)&&!(JEDWorldControl.options(source.getServer()).redirect()&&justepicdimensions.spawn.JEDSpawnController.controlsSpawn(source.getLevel())))return Integer.MIN_VALUE;justepicdimensions.spawn.JEDSpawnChunks.publishSpawn(source.getLevel(),pos,angle);
        source.sendSuccess(()->Component.translatable("commands.setworldspawn.success",pos.getX(),pos.getY(),pos.getZ(),angle),true);return 1;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void register(RegisterCommandsEvent event){
        for(String operation:List.of("time","weather","gamerule","difficulty","defaultgamemode","worldborder","setworldspawn","seed","schedule")){
            var nativeNode=event.getDispatcher().getRoot().getChild(operation);if(nativeNode==null)continue;
            var target=Commands.argument("dimension",ResourceLocationArgument.id()).redirect(nativeNode,context->target(context,operation));
            if(nativeNode.getCommand()!=null)target.executes(context->nativeNode.getCommand().run(context.copyFor(target(context,operation))));
            event.getDispatcher().register(Commands.literal("jed").requires(source->source.hasPermission(2)).then(Commands.literal(operation).then(target)));
        }
    }
    private static CommandSourceStack target(CommandContext<CommandSourceStack> context,String operation) throws CommandSyntaxException {
        var source=context.getSource();var id=ResourceLocationArgument.getId(context,"dimension");var level=source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,id));
        if(level==null)throw new SimpleCommandExceptionType(justepicdimensions.JEDText.tr("world.unavailable",id)).create();
        if(!(level.getLevelData() instanceof JEDLevelData)&&level!=source.getServer().overworld()&&!operation.equals("seed")&&!(operation.equals("worldborder")&&JEDWorldControl.options(source.getServer()).borders())&&!(operation.equals("setworldspawn")&&justepicdimensions.spawn.JEDSpawnController.controlsSpawn(level)))
            throw new SimpleCommandExceptionType(justepicdimensions.JEDText.tr("world.shared",id,operation)).create();
        var scoped=source.withLevel(level);EXPLICIT.add(scoped);return scoped;
    }
    private JEDWorldCommands(){}
}
