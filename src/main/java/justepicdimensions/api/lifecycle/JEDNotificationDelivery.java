package justepicdimensions.api.lifecycle;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Acknowledge only AFTER the addon's effects and its duplicate-detection state are durably committed. */
public final class JEDNotificationDelivery {
    private final MinecraftServer server;
    private final JEDOperation operation;
    private final ResourceLocation consumer;
    private final boolean replay, verification;
    private final Supplier<CompletableFuture<Void>> acknowledgement;
    private CompletableFuture<Void> acknowledged;
    public JEDNotificationDelivery(MinecraftServer server,JEDOperation operation,ResourceLocation consumer,
            boolean replay,boolean verification,Supplier<CompletableFuture<Void>> acknowledgement) {
        this.server=Objects.requireNonNull(server);this.operation=Objects.requireNonNull(operation);this.consumer=Objects.requireNonNull(consumer);
        this.replay=replay;this.verification=verification;this.acknowledgement=Objects.requireNonNull(acknowledgement);
    }
    public MinecraftServer server(){return server;}
    public JEDOperation operation(){return operation;}
    public ResourceLocation consumer(){return consumer;}
    public boolean replay(){return replay;}
    public boolean verification(){return verification;}
    /** Thread-safe and idempotent for this delivery. Completion confirms JED persisted the receipt. */
    public synchronized CompletableFuture<Void> acknowledge(){
        if(acknowledged==null){
            acknowledged=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};
            acknowledgement.get().whenComplete((ignored,error)->{if(error==null)acknowledged.complete(null);else acknowledged.completeExceptionally(error);});
        }return acknowledged;
    }
}
