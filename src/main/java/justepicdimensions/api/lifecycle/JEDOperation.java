package justepicdimensions.api.lifecycle;

import java.time.Instant;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Immutable historical completion. A replay describes this operation, not the world's current state. */
public record JEDOperation(UUID id, Kind kind, Instant completedAt, long definitionRevision,
                           Map<ResourceLocation,DimensionState> dimensions) {
    public enum Kind { REGISTERED, LOADED, UNLOADED, UNREGISTERED, REACTIVATED, RESET, DELETED, RELOADED, MAIN_CONFIG_RELOADED }
    public record DimensionState(boolean registered, boolean available, boolean loaded) {}
    public JEDOperation {
        Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(completedAt);
        dimensions = Collections.unmodifiableMap(new TreeMap<>(dimensions));
    }
}
