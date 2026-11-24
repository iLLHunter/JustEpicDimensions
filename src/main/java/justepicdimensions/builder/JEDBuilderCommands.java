package justepicdimensions.builder;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import java.io.StringReader;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.definition.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Thin command adapter; editor model, validation and save services are also usable by a GUI. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDBuilderCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var builder=Commands.literal("builder").executes(c->help(c.getSource()))
            .then(Commands.literal("list").executes(c->sync(c,()->reply(c.getSource(),JEDText.tr("builder.drafts",JEDBuilderStore.list(c.getSource()))))))
            .then(Commands.literal("new").then(Commands.argument("name",StringArgumentType.word()).executes(c->sync(c,()->{editable(c);JEDBuilderStore.create(c.getSource(),name(c));reply(c.getSource(),JEDText.tr("builder.new",name(c)));}))))
            .then(Commands.literal("copy").then(Commands.argument("name",StringArgumentType.word()).then(dimension().executes(c->copy(c,"independent"))
                .then(Commands.literal("independent").executes(c->copy(c,"independent"))).then(Commands.literal("inherit").executes(c->copy(c,"inherit"))))));
        var draft=Commands.argument("name",StringArgumentType.word()).suggests((c,b)->{JEDBuilderStore.list(c.getSource()).forEach(b::suggest);return b.buildFuture();})
            .executes(c->show(c,null,null))
            .then(Commands.literal("clear").executes(c->edit(c,JEDBuilderDraft::clear)))
            .then(Commands.literal("undo").executes(c->edit(c,JEDBuilderDraft::undo)))
            .then(Commands.literal("show").executes(c->show(c,null,null)).then(document().executes(c->show(c,document(c),null)).then(path().executes(c->show(c,document(c),path(c))))))
            .then(Commands.literal("set").then(document().then(path().then(Commands.argument("json",StringArgumentType.greedyString()).executes(c->edit(c,d->d.set(document(c),path(c),json(c))))))))
            .then(Commands.literal("remove").then(document().then(path().executes(c->edit(c,d->d.remove(document(c),path(c)))))))
            .then(Commands.literal("reset").then(path().executes(c->edit(c,d->d.reset(path(c))))))
            .then(Commands.literal("document").then(document().then(Commands.argument("json",StringArgumentType.greedyString()).executes(c->edit(c,d->d.put(document(c),json(c)))))))
            .then(Commands.literal("drop-document").then(document().executes(c->edit(c,d->d.drop(document(c))))));
        for(var action:JEDBuilderService.Action.values())draft.then(Commands.literal(action.name().toLowerCase(Locale.ROOT)).then(dimension().executes(c->operation(c,action))));
        builder.then(draft);event.getDispatcher().register(Commands.literal("jed").requires(s->s.hasPermission(2)).then(builder));
    }
    private static RequiredArgumentBuilder<CommandSourceStack,net.minecraft.resources.ResourceLocation> dimension(){return Commands.argument("dimension",ResourceLocationArgument.id()).suggests((c,b)->{c.getSource().getServer().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM).keySet().forEach(id->b.suggest(id.toString()));return b.buildFuture();});}
    private static RequiredArgumentBuilder<CommandSourceStack,net.minecraft.resources.ResourceLocation> document(){return Commands.argument("document",ResourceLocationArgument.id()).suggests((c,b)->{try{JEDBuilderStore.open(c.getSource(),name(c)).draft().documents().forEach(b::suggest);}catch(Exception ignored){}return b.buildFuture();});}
    private static RequiredArgumentBuilder<CommandSourceStack,String> path(){return Commands.argument("path",StringArgumentType.word()).suggests((c,b)->{try{var doc=JEDBuilderStore.open(c.getSource(),name(c)).draft().document(document(c));var paths=new TreeSet<String>();paths(doc,"",paths);paths.addAll(List.of("type","generator","state.initial","state.on_load","lighting.brightness_table","weather.snow","weather.ice","time.day_length","player.inventory_group","render.sky","spawn_structure"));paths.stream().filter(p->p.startsWith(b.getRemaining())).forEach(b::suggest);}catch(Exception ignored){}return b.buildFuture();});}
    private static void paths(JsonElement value,String prefix,Set<String> out){if(!prefix.isEmpty())out.add(prefix);if(value.isJsonObject())value.getAsJsonObject().entrySet().forEach(e->paths(e.getValue(),prefix.isEmpty()?e.getKey():prefix+"."+e.getKey(),out));}
    private static String name(CommandContext<CommandSourceStack> c){return StringArgumentType.getString(c,"name");}
    private static String document(CommandContext<CommandSourceStack> c){var id=ResourceLocationArgument.getId(c,"document");return id.getNamespace().equals("minecraft")&&Set.of("dimension","properties").contains(id.getPath())?id.getPath():id.toString();}
    private static String path(CommandContext<CommandSourceStack> c){return StringArgumentType.getString(c,"path");}
    private static JsonElement json(CommandContext<CommandSourceStack> c){return JEDJson.read(new StringReader(StringArgumentType.getString(c,"json")),"builder command");}
    private static void editable(CommandContext<CommandSourceStack> c){if(JEDBuilderService.busy(c.getSource().getServer()))throw new JEDDefinitionException("Wait for the current builder operation before editing drafts");}
    private static int copy(CommandContext<CommandSourceStack> c,String mode){return sync(c,()->{editable(c);if(!JEDBuilderStore.list(c.getSource()).contains(name(c)))JEDBuilderStore.create(c.getSource(),name(c));JEDBuilderService.copy(c.getSource(),name(c),ResourceLocationArgument.getId(c,"dimension"),mode);reply(c.getSource(),JEDText.tr("builder.copied",name(c),mode));});}
    private static int edit(CommandContext<CommandSourceStack> c,java.util.function.Consumer<JEDBuilderDraft> action){return sync(c,()->{editable(c);JEDBuilderStore.edit(c.getSource(),name(c),action);reply(c.getSource(),JEDText.tr("builder.edited",name(c)));});}
    private static int show(CommandContext<CommandSourceStack> c,String document,String path){return sync(c,()->{var draft=JEDBuilderStore.open(c.getSource(),name(c)).draft();var value=document==null?draft.content():draft.document(document);if(path!=null)value=JEDBuilderDraft.find(value,path);if(value==null)throw JEDText.failure("builder.no_value",path);String full=new GsonBuilder().setPrettyPrinting().create().toJson(value);com.mojang.logging.LogUtils.getLogger().info("JED builder {} / {}\n{}",JEDBuilderStore.owner(c.getSource()),name(c),full);reply(c.getSource(),full.length()>6000?Component.literal(full.substring(0,6000)+"\n").append(JEDText.tr("builder.full")):Component.literal(full));});}
    private static int operation(CommandContext<CommandSourceStack> c,JEDBuilderService.Action action){return sync(c,()->{var source=c.getSource();var future=JEDBuilderService.run(source,name(c),ResourceLocationArgument.getId(c,"dimension"),action);String operation="builder "+action.name().toLowerCase(Locale.ROOT)+" "+name(c);justepicdimensions.admin.JEDCommandResults.started(source,JEDText.tr("builder.preparing",action.name().toLowerCase(Locale.ROOT)));future.whenComplete((value,error)->source.getServer().execute(()->{if(error!=null)justepicdimensions.admin.JEDCommandResults.failure(source,operation,Component.translatable("justepicdimensions.builder.failure",JEDText.error(error)),true);else{justepicdimensions.admin.JEDCommandResults.success(source,operation,value.display(),false);if(action==JEDBuilderService.Action.PREVIEW||action==JEDBuilderService.Action.VALIDATE){com.mojang.logging.LogUtils.getLogger().info("JED builder preview {}\n{}",name(c),new GsonBuilder().setPrettyPrinting().create().toJson(value.preview()));reply(source,JEDText.tr("builder.destination",value.preview().get("source_directory").getAsString()));reply(source,JEDText.tr("builder.mode",value.preview().get("copy_mode").getAsString()).copy().append(value.preview().has("copy_source")?JEDText.tr("builder.source",value.preview().get("copy_source").getAsString()):Component.empty()));var changes=value.preview().getAsJsonArray("changes");previewChanges(source,changes);for(var change:changes){var row=change.getAsJsonObject();reply(source,Component.literal(row.get("file").getAsString()+" — ").append(JEDText.fixed(row.get("activation").getAsString())));}for(var notice:value.preview().getAsJsonArray("notices"))reply(source,notice.getAsString());}}}));});}
    private static int help(CommandSourceStack source){reply(source,Component.literal("/jed builder new <draft> | copy <draft> <dimension> [independent|inherit] | list\n/jed builder <draft> show|clear|undo\n/jed builder <draft> set <dimension|properties|resource> <path> <JSON>\n/jed builder <draft> remove <document> <path> | reset <property>\n/jed builder <draft> document <resource> <JSON> | drop-document <resource>\n/jed builder <draft> preview|validate|save|apply|create|load <dimension>\n").append(JEDText.tr("builder.help")));return 1;}
    private static void previewChanges(CommandSourceStack source,JsonArray changes){var lines=new ArrayList<String>();for(var change:changes){var row=change.getAsJsonObject();diff(row.get("before"),row.get("after"),row.get("file").getAsString().replace('\\','/').replaceFirst(".*/data/",""),lines);}lines.stream().limit(24).forEach(line->reply(source,line));if(lines.size()>24)reply(source,JEDText.tr("builder.more",lines.size()-24));if(lines.isEmpty())reply(source,JEDText.tr("builder.no_changes"));}
    private static void diff(JsonElement before,JsonElement after,String path,List<String> lines){if(Objects.equals(before,after))return;if(before!=null&&after!=null&&before.isJsonObject()&&after.isJsonObject()){var keys=new TreeSet<>(before.getAsJsonObject().keySet());keys.addAll(after.getAsJsonObject().keySet());for(String key:keys)diff(before.getAsJsonObject().get(key),after.getAsJsonObject().get(key),path+"."+key,lines);}else lines.add(path+": "+brief(before)+" -> "+brief(after));}
    private static String brief(JsonElement value){String text=value==null||value.isJsonNull()?"<absent>":value.toString();return text.length()>160?text.substring(0,157)+"…":text;}
    private interface Action{void run()throws Exception;}
    private static int sync(CommandContext<CommandSourceStack> c,Action action){try{action.run();return 1;}catch(Exception error){c.getSource().sendFailure(Component.translatable("justepicdimensions.builder.failure",JEDText.error(error)));return 0;}}
    private static void reply(CommandSourceStack source,Component text){source.sendSuccess(()->text,false);}
    private static void reply(CommandSourceStack source,String text){source.sendSuccess(()->Component.literal(text),false);}
    private JEDBuilderCommands(){}
}
