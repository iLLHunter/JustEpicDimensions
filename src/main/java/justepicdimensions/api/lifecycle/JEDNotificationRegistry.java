package justepicdimensions.api.lifecycle;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Register during mod construction/common setup, before JED validates all addons at load-complete. */
public final class JEDNotificationRegistry {
    public static final int PROTOCOL=1;
    public enum Contract { DURABLE_DUPLICATE_SAFE_ACKNOWLEDGEMENTS }
    public record ConsumerType(ResourceLocation id,int protocol,Contract contract,Set<JEDOperation.Kind> kinds,
                               Consumer<JEDNotificationDelivery> handler) {
        public ConsumerType {Objects.requireNonNull(id);Objects.requireNonNull(contract);Objects.requireNonNull(handler);kinds=Set.copyOf(kinds);if(kinds.isEmpty())throw new IllegalArgumentException("Notification consumer must select operation kinds");}
    }
    private static final Map<ResourceLocation,ConsumerType> TYPES=new TreeMap<>();
    private static boolean frozen;
    public static synchronized void register(ResourceLocation id,int protocol,Contract contract,Set<JEDOperation.Kind> kinds,Consumer<JEDNotificationDelivery> handler){
        if(frozen)throw new IllegalStateException("JED notification registration closed at mod load-complete: "+id);
        var type=new ConsumerType(id,protocol,contract,kinds,handler);
        if(TYPES.putIfAbsent(id,type)!=null)throw new IllegalStateException("Duplicate JED notification consumer: "+id);
    }
    public static synchronized Map<ResourceLocation,ConsumerType> consumers(){return Collections.unmodifiableMap(new TreeMap<>(TYPES));}
    public static synchronized void freeze(){frozen=true;}
    public static synchronized boolean frozen(){return frozen;}
    private JEDNotificationRegistry(){}
}
