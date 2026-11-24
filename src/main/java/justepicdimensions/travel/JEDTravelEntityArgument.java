package justepicdimensions.travel;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.*;
import net.minecraft.commands.synchronization.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native selector grammar with JED's command permission, without changing other commands' selector access. */
public final class JEDTravelEntityArgument extends EntityArgument {
    private static final DeferredRegister<ArgumentTypeInfo<?,?>> TYPES=DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE,"justepicdimensions");
    private static final net.neoforged.neoforge.registries.DeferredHolder<ArgumentTypeInfo<?,?>,Info> INFO=TYPES.register("travel_entities",Info::new);
    private final boolean one;
    private JEDTravelEntityArgument(boolean one){super(one,false);this.one=one;}
    public static JEDTravelEntityArgument single(){return new JEDTravelEntityArgument(true);}
    public static JEDTravelEntityArgument multiple(){return new JEDTravelEntityArgument(false);}
    public static void register(IEventBus bus){TYPES.register(bus);bus.addListener((FMLCommonSetupEvent event)->event.enqueueWork(()->ArgumentTypeInfos.registerByClass(JEDTravelEntityArgument.class,INFO.get())));}
    @Override public <S> EntitySelector parse(StringReader reader,S source)throws CommandSyntaxException{return super.parse(reader);}
    @Override public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context,SuggestionsBuilder builder){
        if(!(context.getSource() instanceof SharedSuggestionProvider source))return Suggestions.empty();
        var reader=new StringReader(builder.getInput());reader.setCursor(builder.getStart());var parser=new EntitySelectorParser(reader,true);
        try{parser.parse();}catch(CommandSyntaxException ignored){}
        return parser.fillSuggestions(builder,value->SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(source.getOnlinePlayerNames().stream(),source.getSelectedEntities().stream()).toList(),value));
    }
    public static final class Info implements ArgumentTypeInfo<JEDTravelEntityArgument,Info.Template> {
        public void serializeToNetwork(Template template,FriendlyByteBuf buffer){buffer.writeBoolean(template.one);}
        public Template deserializeFromNetwork(FriendlyByteBuf buffer){return new Template(buffer.readBoolean());}
        public void serializeToJson(Template template,JsonObject object){object.addProperty("single",template.one);}
        public Template unpack(JEDTravelEntityArgument argument){return new Template(argument.one);}
        public final class Template implements ArgumentTypeInfo.Template<JEDTravelEntityArgument> {
            private final boolean one;Template(boolean one){this.one=one;}
            public JEDTravelEntityArgument instantiate(CommandBuildContext context){return new JEDTravelEntityArgument(one);}
            public ArgumentTypeInfo<JEDTravelEntityArgument,?> type(){return Info.this;}
        }
    }
}
