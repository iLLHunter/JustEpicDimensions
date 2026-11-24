package justepicdimensions.dimension;

import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.objects.*;
import java.lang.invoke.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.neoforged.neoforge.registries.callback.*;

/** Only this owner publishes native storage. Old values, holders and IDs are never replaced. */
public final class JEDNativeRegistry {
    private static final MethodHandle SNAPSHOT, PUBLISH;
    static {
        try {
            var lookup = MethodHandles.publicLookup();
            SNAPSHOT = lookup.findVirtual(MappedRegistry.class, "jed$snapshot", MethodType.methodType(Object[].class));
            PUBLISH = lookup.findVirtual(MappedRegistry.class, "jed$publish", MethodType.methodType(void.class, Object[].class));
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    public static final class StagedHolder<T> extends Holder.Reference<T> {
        StagedHolder(Registry<T> registry, ResourceKey<T> key) { super(Type.STAND_ALONE, registry.holderOwner(), key, null); }
        void bind(T value) { bindValue(value); }
    }
    public record Append<T>(Registry<T> registry, Object[] before, Object[] after, List<Holder.Reference<T>> added) {
        public Append { added = List.copyOf(added); }
        public void verify() {
            var now = snapshot(registry);
            for (int i = 0; i < 7; i++) if (now[i] != before[i]) throw new IllegalStateException("Registry changed while preparing " + registry.key());
        }
        public void publish() { verify(); install(registry, after); }
        public void callbacks() {
            for (var holder : added) for (var callback : (List<AddCallback<T>>) before[9])
                callback.onAdd(registry, registry.getId(holder.value()), holder.key(), holder.value());
            for (var callback : (List<BakeCallback<T>>) before[10]) callback.onBake(registry);
        }
    }
    public static Object[] snapshot(Registry<?> registry) {
        if (!(registry instanceof MappedRegistry<?>)) throw new IllegalArgumentException("Not a native mapped registry: " + registry.key());
        try { return (Object[]) SNAPSHOT.invoke(registry); } catch (Throwable error) { throw new IllegalStateException("Native registry snapshot failed", error); }
    }
    private static void install(Registry<?> registry, Object[] state) {
        try { PUBLISH.invoke(registry, state); } catch (Throwable error) { throw new IllegalStateException("Native registry publication failed", error); }
    }
    @SuppressWarnings("unchecked")
    public static <T> Append<T> prepare(Registry<T> registry, Collection<? extends Holder.Reference<T>> additions) {
        var old = snapshot(registry);
        if (!Boolean.TRUE.equals(old[7]) || old[8] != null) throw new IllegalArgumentException("Runtime append requires a frozen, nonintrusive data registry: " + registry.key());
        var byId = new ObjectArrayList<>((ObjectList<Holder.Reference<T>>) old[0]);
        var toId = new Reference2IntOpenHashMap<>((Reference2IntMap<T>) old[1]); toId.defaultReturnValue(-1);
        var locations = new HashMap<>((Map<ResourceLocation, Holder.Reference<T>>) old[2]);
        var keys = new HashMap<>((Map<ResourceKey<T>, Holder.Reference<T>>) old[3]);
        var values = new IdentityHashMap<>((Map<T, Holder.Reference<T>>) old[4]);
        var infos = new IdentityHashMap<>((Map<ResourceKey<T>, RegistrationInfo>) old[5]);
        var lifecycle = (Lifecycle) old[6]; var added = new ArrayList<Holder.Reference<T>>();
        for (var holder : additions) {
            if (!holder.isBound() || !holder.canSerializeIn(registry.holderOwner())) throw new IllegalArgumentException("Unbound or foreign holder " + holder);
            var key = holder.key(); var value = holder.value();
            if (locations.containsKey(key.location()) || values.containsKey(value)) throw new IllegalArgumentException("Existing registry entry cannot be replaced: " + key);
            if (byId.size() > ((MappedRegistry<T>) registry).getMaxId()) throw new IllegalArgumentException("Native registry ID limit exceeded: " + key);
            toId.put(value, byId.size()); byId.add(holder); locations.put(key.location(), holder); keys.put(key, holder); values.put(value, holder);
            infos.put(key, new RegistrationInfo(Optional.empty(), Lifecycle.stable())); added.add(holder);
        }
        return new Append<>(registry, old, new Object[]{byId, toId, locations, keys, values, infos, lifecycle}, added);
    }
    private JEDNativeRegistry() {}
}
