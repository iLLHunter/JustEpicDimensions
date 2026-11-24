package justepicdimensions.dimension;

import com.google.gson.JsonElement;
import com.mojang.serialization.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.conditions.*;
import net.neoforged.neoforge.common.util.NeoForgeExtraCodecs;

/** Detached decoding with forward references owned by the real registry, never by a disposable clone. */
public final class JEDRegistryTransaction implements RegistryOps.RegistryInfoLookup {
    private final RegistryAccess access;
    private final Map<ResourceKey<?>, Stage<?>> stages = new LinkedHashMap<>();
    private final List<JEDNativeRegistry.Append<?>> appends = new ArrayList<>();
    private final RegistryOps<JsonElement> ops;
    private final ArrayDeque<Runnable> preparation = new ArrayDeque<>();
    private boolean published;
    private boolean verificationOnly;
    /** New and authored definitions are checked for unknown flat biomes; existing worlds and client mirrors are not. */
    private boolean strictFlatBiomes;
    /** Live native contents by file; an unchanged definition already accepted by this world is not re-checked. */
    private Map<ResourceLocation, String> accepted = Map.of();
    private static final class Stage<T> implements HolderGetter<T> {
        final Registry<T> registry;
        final Map<ResourceKey<T>, JEDNativeRegistry.StagedHolder<T>> holders = new LinkedHashMap<>();
        final Map<ResourceKey<T>, JsonElement> inputs = new LinkedHashMap<>();
        Stage(Registry<T> registry) { this.registry = registry; }
        public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
            var staged = holders.get(key); return staged != null ? Optional.of(staged) : inputs.containsKey(key) ? Optional.empty() : registry.getHolder(key);
        }
        public Optional<HolderSet.Named<T>> get(TagKey<T> key) { return registry.getTag(key); }
    }
    public JEDRegistryTransaction(RegistryAccess access, Map<ResourceLocation, JEDSourceDocument> additions) {
        this(access, additions, JEDRegistryResources.types().values());
    }
    public JEDRegistryTransaction(RegistryAccess access, Map<ResourceLocation, JEDSourceDocument> additions, Collection<RegistryDataLoader.RegistryData<?>> types) {
        this(access); plan(additions, types);
        while (advance()) {}
    }
    private JEDRegistryTransaction(RegistryAccess access) { this.access = access; this.ops = RegistryOps.create(JsonOps.INSTANCE, this); }
    public static JEDRegistryTransaction incremental(RegistryAccess access, Map<ResourceLocation, JEDSourceDocument> additions) {
        var transaction = new JEDRegistryTransaction(access); transaction.strictFlatBiomes = true; transaction.plan(additions, JEDRegistryResources.types().values()); return transaction;
    }
    /** Detached authoring validation can shadow existing IDs; it can never prepare appends or publish. */
    /** As {@link #verification(RegistryAccess, Map)}, but unchanged live definitions keep their accepted state. */
    public static JEDRegistryTransaction verification(RegistryAccess access, Map<ResourceLocation,JEDSourceDocument> documents, Map<ResourceLocation,String> accepted) {
        var transaction=new JEDRegistryTransaction(access);transaction.verificationOnly=true;transaction.strictFlatBiomes=true;transaction.accepted=Map.copyOf(accepted);transaction.plan(documents,JEDRegistryResources.types().values());return transaction;
    }
    public static JEDRegistryTransaction verification(RegistryAccess access, Map<ResourceLocation,JEDSourceDocument> documents) {
        var transaction=new JEDRegistryTransaction(access);transaction.verificationOnly=true;transaction.strictFlatBiomes=true;transaction.plan(documents,JEDRegistryResources.types().values());return transaction;
    }
    private void plan(Map<ResourceLocation, JEDSourceDocument> additions, Collection<RegistryDataLoader.RegistryData<?>> types) {
        var sorted = new TreeMap<>(additions);
        for (var type : types) planType(type, sorted);
        // All forward holders must exist before any decoder runs.
        preparation.add(() -> {
            for (var type : types) planDecoding(type);
            if(!verificationOnly)for (var stage : stages.values()) preparation.add(() -> prepare(stage));
        });
    }
    private <T> void planType(RegistryDataLoader.RegistryData<T> type, Map<ResourceLocation, JEDSourceDocument> additions) {
        String directory = Registries.elementsDirPath(type.key());
        for (var entry : additions.entrySet()) if (directory.equals(JEDRegistryResources.directory(entry.getKey()))) preparation.add(() -> {
            var stage = (Stage<T>) stages.computeIfAbsent(type.key(), ignored -> new Stage<>(access.registryOrThrow(type.key())));
            var id = JEDRegistryResources.entry(entry.getKey(), directory); var key = ResourceKey.create(type.key(), id);
            if (stage.registry.containsKey(key)&&!verificationOnly) throw new JEDDefinitionException("Cannot replace a live native registry entry: " + key);
            stage.holders.put(key, new JEDNativeRegistry.StagedHolder<>(stage.registry, key)); stage.inputs.put(key, entry.getValue().value());
        });
    }
    private <T> void planDecoding(RegistryDataLoader.RegistryData<T> type) {
        var stage = (Stage<T>) stages.get(type.key()); if (stage == null) return;
        var decoder = ConditionalOps.createConditionalCodec(NeoForgeExtraCodecs.decodeOnly(type.elementCodec()));
        var conditional = new ConditionalOps<>(ops, ICondition.IContext.TAGS_INVALID);
        for (var entry : stage.inputs.entrySet()) preparation.add(() -> {
            var file = ResourceLocation.fromNamespaceAndPath(entry.getKey().location().getNamespace(), "dimension/" + entry.getKey().location().getPath() + ".json");
            if (strictFlatBiomes && type.key().equals(Registries.LEVEL_STEM) && !Objects.equals(accepted.get(file), JEDJson.canonical(entry.getValue()).toString())) {
                var biomes = stages.get(Registries.BIOME);
                var problem = JEDRegistryResources.unknownFlatBiome(entry.getKey().location(), entry.getValue(), id -> access.registryOrThrow(Registries.BIOME).containsKey(id) || biomes != null && biomes.holders.containsKey(ResourceKey.create(Registries.BIOME, id)));
                if (problem != null) throw new JEDDefinitionException(problem);
            }
            var value = decoder.parse(conditional, entry.getValue()).getOrThrow(error -> new JEDDefinitionException(entry.getKey() + ": " + error));
            if (value.isPresent()) stage.holders.get(entry.getKey()).bind(value.get()); else stage.holders.remove(entry.getKey());
        });
    }
    /** One native operation; callers impose their own soft per-tick deadline. Addon codecs stay on that caller's thread. */
    public boolean advance() { var work = preparation.poll(); if (work == null) return false; work.run(); return true; }
    public boolean prepared() { return preparation.isEmpty(); }
    private <T> void prepare(Stage<T> stage) { appends.add(JEDNativeRegistry.prepare(stage.registry, stage.holders.values())); }
    public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
        var registry = access.registry(key); if (registry.isEmpty()) return Optional.empty();
        var stage = (Stage<T>) stages.get(key); var actual = registry.get();
        return Optional.of(new RegistryOps.RegistryInfo<>(actual.holderOwner(), stage == null ? actual.asLookup() : stage, actual.registryLifecycle()));
    }
    public RegistryOps<JsonElement> ops() { return ops; }
    public <T> Optional<Holder.Reference<T>> holder(ResourceKey<? extends Registry<T>> registry, ResourceLocation id) {
        var stage = (Stage<T>) stages.get(registry); var key = ResourceKey.create(registry, id);
        return stage == null ? access.registryOrThrow(registry).getHolder(key) : stage.get(key);
    }
    public boolean containsDimension(ResourceLocation id) { return holder(Registries.LEVEL_STEM, id).filter(Holder.Reference::isBound).isPresent(); }
    public List<JEDNativeRegistry.Append<?>> appends() { return List.copyOf(appends); }
    public void reorder(com.google.gson.JsonObject wire) {
        if(verificationOnly)throw new JEDDefinitionException("An authoring verification transaction cannot prepare native appends");
        if (published) throw new IllegalStateException("Already published");
        appends.clear();
        for (var stage : stages.values()) reorderStage(stage, wire);
    }
    private <T> void reorderStage(Stage<T> stage, com.google.gson.JsonObject wire) {
        var layout = wire.getAsJsonObject(stage.registry.key().location().toString()).getAsJsonArray("layout");
        var ordered = new ArrayList<Holder.Reference<T>>();
        for (int i = stage.registry.size(); i < layout.size(); i++) ordered.add(Objects.requireNonNull(stage.holders.get(ResourceKey.create(stage.registry.key(), ResourceLocation.parse(layout.get(i).getAsString())))));
        appends.add(JEDNativeRegistry.prepare(stage.registry, ordered));
    }
    public void publish() {
        if(verificationOnly)throw new JEDDefinitionException("An authoring verification transaction cannot publish native registry changes");
        if (!prepared()) throw new IllegalStateException("Native registry preparation is incomplete");
        if (published) throw new IllegalStateException("Registry transaction was already published");
        appends.forEach(JEDNativeRegistry.Append::verify); appends.forEach(JEDNativeRegistry.Append::publish); published = true;
        // Native callbacks observe actual registry owners and the complete batch, including forward references.
        appends.forEach(JEDNativeRegistry.Append::callbacks);
    }
}
