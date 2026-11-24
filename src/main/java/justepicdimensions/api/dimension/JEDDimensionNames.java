package justepicdimensions.api.dimension;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import justepicdimensions.dimension.JEDDimensionNamesSync;

/** Read-only configured dimension names synchronized from the connected server. */
public final class JEDDimensionNames {
    /** Empty before synchronization, after disconnect, or when the dimension has no configured display_name. */
    public static Optional<String> displayName(ResourceLocation dimension){return Optional.ofNullable(JEDDimensionNamesSync.clientNames().get(dimension));}
    /** Immutable current snapshot; callers may retain it, but should query again after configuration changes. */
    public static Map<ResourceLocation,String> displayNames(){return JEDDimensionNamesSync.clientNames();}
    private JEDDimensionNames(){}
}
