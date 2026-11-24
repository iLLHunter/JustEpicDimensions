package justepicdimensions.pregen;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDPregenerationCommands {
 @SubscribeEvent public static void register(RegisterCommandsEvent event){var root=Commands.literal("pregen").executes(c->{c.getSource().sendSuccess(()->Component.literal("/jed pregen <status|start|pause|resume|cancel> <dimension>"),false);return 1;});for(String action:java.util.List.of("status","start","pause","resume","cancel"))root.then(Commands.literal(action).then(Commands.argument("dimension",ResourceLocationArgument.id()).suggests((c,b)->SharedSuggestionProvider.suggestResource(JEDPregenerationController.dimensions(c.getSource().getServer()),b)).executes(c->{try{var result=JEDPregenerationController.control(c.getSource().getServer(),ResourceLocationArgument.getId(c,"dimension"),action);c.getSource().sendSuccess(()->JEDPregenerationController.feedback(result),false);return 1;}catch(Exception error){c.getSource().sendFailure(justepicdimensions.JEDText.error(error));return 0;}})));event.getDispatcher().register(Commands.literal("jed").requires(s->s.hasPermission(2)).then(root));}
 private JEDPregenerationCommands(){}
}
