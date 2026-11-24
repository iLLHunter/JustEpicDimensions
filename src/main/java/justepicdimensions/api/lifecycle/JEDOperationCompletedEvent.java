package justepicdimensions.api.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/** Non-cancellable observational game-bus event. Reliable addons register an acknowledged consumer. */
public final class JEDOperationCompletedEvent extends Event {
    private final MinecraftServer server;
    private final JEDOperation operation;
    private final boolean replay;
    public JEDOperationCompletedEvent(MinecraftServer server,JEDOperation operation,boolean replay){this.server=server;this.operation=operation;this.replay=replay;}
    public MinecraftServer server(){return server;}
    public JEDOperation operation(){return operation;}
    public boolean replay(){return replay;}
}
