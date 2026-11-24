package justepicdimensions.player;

import java.util.Optional;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.resources.ResourceLocation;

/** Each category has its own resource directory and independent player membership. */
public enum JEDPlayerCategory {
    XP("xp"), HEALTH("health"), ABSORPTION("absorption"), FOOD("food"), EFFECTS("effects");

    public static final String DIRECTORY = "justepicdimensions/player_groups";
    private final String key;

    JEDPlayerCategory(String key) { this.key = key; }
    public String key() { return key; }
    public String directory() { return DIRECTORY + "/" + key; }
    public ResourceLocation resource(ResourceLocation group) {
        return ResourceLocation.fromNamespaceAndPath(group.getNamespace(), directory() + "/" + group.getPath() + ".json");
    }
    public static JEDPlayerCategory parse(String key, String source) {
        for (var category : values()) if (category.key.equals(key)) return category;
        throw new JEDDefinitionException(source + ": unknown player-state category '" + key + "'");
    }
    public static Optional<JEDPlayerCategory> resourceCategory(ResourceLocation resource) {
        for (var category : values())
            if (resource.getPath().startsWith(category.directory() + "/") && resource.getPath().endsWith(".json"))
                return Optional.of(category);
        return Optional.empty();
    }
}
