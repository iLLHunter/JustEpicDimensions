package justepicdimensions.admin;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDAdminCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var list=Commands.literal("list").executes(c->list(c.getSource(),JEDAdminSnapshot.Filter.ALL,1)).then(page(JEDAdminSnapshot.Filter.ALL));
        for(var filter:JEDAdminSnapshot.Filter.values())list.then(Commands.literal(filter.name().toLowerCase(Locale.ROOT)).executes(c->list(c.getSource(),filter,1)).then(page(filter)));
        var bulk=Commands.literal("unload-empty").executes(c->help(c.getSource()));for(boolean run:new boolean[]{false,true})bulk.then(options(Commands.literal(run?"run":"preview"),run,false,false));
        event.getDispatcher().register(Commands.literal("jed").requires(s->s.hasPermission(2)).then(list)
            .then(Commands.literal("status").then(dimension().executes(c->status(c.getSource(),ResourceLocationArgument.getId(c,"dimension")))))
            .then(Commands.literal("debug").then(Commands.literal("dump").executes(c->dump(c.getSource(),null)).then(dimension().executes(c->dump(c.getSource(),ResourceLocationArgument.getId(c,"dimension"))))))
            .then(bulk).then(justepicdimensions.monitor.JEDMonitoringCommands.tree()).then(justepicdimensions.instance.JEDInstanceCommands.tree()));
    }
    private static RequiredArgumentBuilder<CommandSourceStack,Integer> page(JEDAdminSnapshot.Filter filter){return Commands.argument("page",IntegerArgumentType.integer(1)).executes(c->list(c.getSource(),filter,IntegerArgumentType.getInteger(c,"page")));}
    private static RequiredArgumentBuilder<CommandSourceStack,ResourceLocation> dimension(){return Commands.argument("dimension",ResourceLocationArgument.id()).suggests((c,b)->{JEDAdminSnapshot.ids(c.getSource().getServer()).forEach(id->b.suggest(id.toString()));return b.buildFuture();});}
    private static LiteralArgumentBuilder<CommandSourceStack> options(LiteralArgumentBuilder<CommandSourceStack> node,boolean run,boolean drain,boolean temporary){
        node.executes(c->bulk(c.getSource(),run,new JEDAdminUnloader.Options(drain,temporary)));
        if(!drain)node.then(options(Commands.literal("drain-idle"),run,true,temporary));if(!temporary)node.then(options(Commands.literal("include-temporary"),run,drain,true));return node;
    }
    private static int help(CommandSourceStack source){reply(source,"help");return 1;}
    private static void permission(CommandSourceStack source){if(!source.hasPermission(2))throw new justepicdimensions.definition.JEDDefinitionException("Administrative commands require operator level 2");}
    private interface Work {int run()throws Exception;}
    private static int attempt(CommandSourceStack source,Work work){try{permission(source);return work.run();}catch(Exception e){source.sendFailure(Component.translatable("justepicdimensions.admin.failure",JEDText.error(e)));return 0;}}
    private static void reply(CommandSourceStack source,String key,Object...values){var safe=Arrays.stream(values).map(v->v instanceof Component||v instanceof Number||v instanceof Boolean||v instanceof String?v:String.valueOf(v)).toArray();source.sendSuccess(()->Component.translatable("justepicdimensions.admin."+key,safe),false);}
    private static void line(CommandSourceStack source,String text){source.sendSuccess(()->Component.literal(text),false);}
    private static int list(CommandSourceStack source,JEDAdminSnapshot.Filter filter,int number){return attempt(source,()->{
        var page=JEDAdminSnapshot.page(source.getServer(),filter,number);
        var header=JEDText.tr("justepicdimensions.admin.list",filter.name().toLowerCase(Locale.ROOT),page.number(),page.pages(),page.total()).copy();
        if(page.number()<page.pages()){
            String next="/jed list "+(filter==JEDAdminSnapshot.Filter.ALL?"":filter.name().toLowerCase(Locale.ROOT)+" ")+(page.number()+1);
            header.append(" ").append(JEDText.tr("justepicdimensions.admin.next_page",next));
        }
        source.sendSuccess(()->header,false);
        for(var row:page.rows()){var d=row.data();var text=JEDText.tr("admin.row",row.id(),d.get("display_name").getAsString(),d.get("registered").toString(),d.get("loaded").toString(),d.get("retired").toString(),d.get("type").toString(),d.get("provider").toString()).copy();text.withStyle(style->style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/jed status "+row.id())).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal("/jed status "+row.id()))));source.sendSuccess(()->text,false);}return 1;
    });}
    private static int status(CommandSourceStack source,ResourceLocation id){return attempt(source,()->{
        var image=JEDAdminSnapshot.capture(source.getServer(),id);var d=image.dimensions().getFirst().data();reply(source,"status",id,d.get("display_name").getAsString());
        for(String field:List.of("registered","loaded","retired","configured_disabled","available","type","provider","temporary","keep_loaded","load_on_start","players","loaded_chunks","loaded_entities","forced_chunks","ticket_chunks","pending_chunk_tasks","unload_blocker","unload_blockers","spawn_chunks"))if(d.has(field))line(source,field+": "+d.get(field));
        if(d.has("world_state")){var w=d.getAsJsonObject("world_state");for(String field:List.of("independent","seed","difficulty","hardcore","game_mode","day_time","raining","thundering"))line(source,field+": "+w.get(field));}
        var origins=new TreeSet<String>();for(String section:List.of("native_origins","property_origins"))if(d.has(section))d.getAsJsonObject(section).entrySet().forEach(e->origins.add(e.getValue().getAsString()));origins.stream().limit(8).forEach(origin->line(source,"source: "+origin));if(origins.size()>8)reply(source,"more_sources",origins.size()-8);
        var s=image.server();line(source,"pending_restart: "+s.get("pending_restart"));line(source,"synchronization: "+s.get("synchronization"));line(source,"bulk_unload: "+s.get("bulk_unload"));var lifecycle=s.getAsJsonObject("lifecycle");for(String field:List.of("busy","recovery_required","target","operation"))if(lifecycle.has(field))line(source,"lifecycle."+field+": "+lifecycle.get(field));return 1;
    });}
    private static int dump(CommandSourceStack source,ResourceLocation id){return attempt(source,()->{var result=JEDAdminReports.export(source,id);JEDCommandResults.started(source,Component.translatable("justepicdimensions.admin.dump_preparing"));result.whenComplete((out,error)->{if(error!=null)JEDCommandResults.failure(source,"debug dump",Component.translatable("justepicdimensions.admin.failure",JEDText.error(error)),true);else JEDCommandResults.success(source,"debug dump",Component.translatable("justepicdimensions.admin.dump_complete",out.directory().toString(),out.dimensions()),false);});return 1;});}
    private static int bulk(CommandSourceStack source,boolean run,JEDAdminUnloader.Options options){return attempt(source,()->{if(!run){entries(source,JEDAdminUnloader.preview(source.getServer(),options));reply(source,"preview",options.drainIdle(),options.includeTemporary());return 1;}var pending=JEDAdminUnloader.run(source,options);reply(source,"bulk_preparing",options.drainIdle(),options.includeTemporary());pending.whenComplete((out,error)->source.getServer().execute(()->{if(error!=null)source.sendFailure(Component.translatable("justepicdimensions.admin.failure",JEDText.error(error)));else{entries(source,out.entries());reply(source,"bulk_complete",out.count("completed"),out.count("skipped"),out.count("failed"));if(!out.stopped().isEmpty())reply(source,"stopped",out.stopped());}}));return 1;});}
    private static void entries(CommandSourceStack source,List<JEDAdminUnloader.Entry> entries){for(var e:entries)reply(source,"entry",e.id(),e.action(),e.outcome(),e.reason());}
    private JEDAdminCommands(){}
}
