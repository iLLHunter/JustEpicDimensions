package justepicdimensions.definition;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.*;

/** A resolved resource view, installed before RegistryDataLoader. No runtime registry unfreezing. */
public final class JEDDefinitionResources implements CloseableResourceManager {
    private final CloseableResourceManager delegate;
    private final Map<ResourceLocation, Resource> overrides;
    private final PackResources synthetic;
    private final JEDPreparedDefinitions prepared;
    private int leases;
    private boolean closeRequested, closed;
    /** Reload/shutdown may retire this view while a background read is still finishing. */
    public synchronized AutoCloseable lease() {
        if (closeRequested) throw new JEDDefinitionException("Dimension resource view is already retired");
        leases++;
        return new AutoCloseable() {
            private boolean released;
            public void close() { synchronized (JEDDefinitionResources.this) {
                if (released) return; released = true; leases--; closeIfRetired();
            } }
        };
    }
    private void closeIfRetired() { if (closeRequested && leases == 0 && !closed) { closed = true; delegate.close(); } }
    public JEDDefinitionResources(CloseableResourceManager delegate, JEDPreparedDefinitions prepared) {
        this.delegate = delegate; this.prepared = prepared;
        var bytes = new TreeMap<ResourceLocation, byte[]>();
        prepared.resources().forEach((key, document) -> {
            // Unchanged datapack resources retain their native known-pack identity and metadata.
            if (!document.origin("").startsWith("datapack ") || document.origins().values().stream().anyMatch(source -> source.startsWith("JSON ")))
                bytes.put(key, document.value().toString().getBytes(StandardCharsets.UTF_8));
        });
        synthetic = new VirtualPack(bytes);
        var overrides = new TreeMap<ResourceLocation, Resource>();
        bytes.forEach((key, value) -> overrides.put(key, new Resource(synthetic, () -> new ByteArrayInputStream(value))));
        this.overrides = Collections.unmodifiableMap(overrides);
    }
    public JEDPreparedDefinitions prepared() { return prepared; }
    public CloseableResourceManager rawDatapacks() { return delegate; }
    private boolean suppressed(ResourceLocation resource) {
        return resource.getPath().startsWith("dimension/") && resource.getPath().endsWith(".json")
            && prepared.disabled().contains(JEDRegistryResources.entry(resource, "dimension"));
    }
    @Override public Set<String> getNamespaces() {
        var result = new HashSet<>(delegate.getNamespaces()); overrides.keySet().forEach(key -> result.add(key.getNamespace())); return Set.copyOf(result);
    }
    @Override public Optional<Resource> getResource(ResourceLocation location) {
        if (suppressed(location)) return Optional.empty();
        var resource = overrides.get(location); return resource == null ? delegate.getResource(location) : Optional.of(resource);
    }
    @Override public List<Resource> getResourceStack(ResourceLocation location) {
        if (suppressed(location)) return List.of();
        var stack = new ArrayList<>(delegate.getResourceStack(location));
        if (overrides.containsKey(location)) stack.add(overrides.get(location));
        return List.copyOf(stack);
    }
    private static boolean within(ResourceLocation key, String directory, Predicate<ResourceLocation> filter) {
        return (directory.isEmpty() || key.getPath().startsWith(directory + "/")) && filter.test(key);
    }
    @Override public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
        var result = new TreeMap<>(delegate.listResources(path, filter));
        overrides.forEach((key, value) -> { if (within(key, path, filter)) result.put(key, value); }); result.keySet().removeIf(this::suppressed); return result;
    }
    @Override public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
        var result = new TreeMap<>(delegate.listResourceStacks(path, filter));
        overrides.forEach((key, value) -> { if (within(key, path, filter)) result.put(key, getResourceStack(key)); }); result.keySet().removeIf(this::suppressed); return result;
    }
    @Override public Stream<PackResources> listPacks() { return Stream.concat(delegate.listPacks(), Stream.of(synthetic)); }
    @Override public synchronized void close() { closeRequested = true; closeIfRetired(); }

    private static final class VirtualPack implements PackResources {
        private final Map<ResourceLocation, byte[]> bytes;
        private final PackLocationInfo location = new PackLocationInfo("justepicdimensions:resolved_json", Component.literal("JED resolved JSON"), PackSource.BUILT_IN, Optional.empty());
        VirtualPack(Map<ResourceLocation, byte[]> bytes) { this.bytes = Map.copyOf(bytes); }
        @Override public IoSupplier<InputStream> getRootResource(String... elements) { return null; }
        @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation key) {
            byte[] data = type == PackType.SERVER_DATA ? bytes.get(key) : null;
            return data == null ? null : () -> new ByteArrayInputStream(data);
        }
        @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type == PackType.SERVER_DATA) bytes.forEach((key, value) -> {
                if (key.getNamespace().equals(namespace) && within(key, path, ignored -> true)) output.accept(key, () -> new ByteArrayInputStream(value));
            });
        }
        @Override public Set<String> getNamespaces(PackType type) {
            if (type != PackType.SERVER_DATA) return Set.of();
            var result = new HashSet<String>(); bytes.keySet().forEach(key -> result.add(key.getNamespace())); return Set.copyOf(result);
        }
        @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) { return null; }
        @Override public PackLocationInfo location() { return location; }
        @Override public void close() {}
    }
}
