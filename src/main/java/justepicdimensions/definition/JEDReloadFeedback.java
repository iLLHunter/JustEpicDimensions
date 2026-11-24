package justepicdimensions.definition;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import justepicdimensions.JEDText;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Invoker-only diagnostics; the original failure still reaches vanilla's reload handler. */
public final class JEDReloadFeedback {
    public static CompletableFuture<Void> reload(MinecraftServer server,Collection<String> packs,CommandSourceStack source){
        CompletableFuture<Void> pending;
        try{pending=server.reloadResources(packs);}
        catch(JEDDefinitionException error){pending=CompletableFuture.failedFuture(error);}
        return pending.whenComplete((ignored,error)->{
            for(var cause=error;cause!=null;cause=cause.getCause())if(cause instanceof JEDDefinitionException){
                source.sendFailure(JEDText.tr("command.reload_failed",JEDText.error(cause)));break;
            }
        });
    }
    private JEDReloadFeedback(){}
}
