package justepicdimensions.player;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;

/** Detached main-config values; the controller never reads TOML during an arrival. */
public record JEDPlayerOptions(boolean inventoryGroups, boolean forcedModes, GameType normalMode, Set<ResourceLocation> globalInventoryItems) {
    public static final List<String> DEFAULT_GLOBAL_ITEMS=List.of("justepicdimensions:guide_book");
    public JEDPlayerOptions{globalInventoryItems=Set.copyOf(globalInventoryItems);}
    public JEDPlayerOptions(boolean inventoryGroups,boolean forcedModes,GameType normalMode){this(inventoryGroups,forcedModes,normalMode,DEFAULT_GLOBAL_ITEMS.stream().map(ResourceLocation::parse).collect(java.util.stream.Collectors.toUnmodifiableSet()));}
    public static JEDPlayerOptions read(JEDConfigRepository.Selection selection) {
        var path = JEDConfigRepository.mainFile(selection);
        if (!Files.exists(path)) return new JEDPlayerOptions(true, true, GameType.SURVIVAL);
        try (var config = CommentedFileConfig.of(path)) {
            config.load();
            Object groups = config.getOrElse("enablePlayerInventoryGroups", true);
            Object modes = config.getOrElse("enableForcedGameModes", true);
            Object normal = config.getOrElse("normalGameMode", "survival");
            if (!(groups instanceof Boolean g) || !(modes instanceof Boolean m) || !(normal instanceof String name))
                throw new JEDDefinitionException("Player-state TOML switches must be booleans; normalGameMode must be a game-mode name");
            GameType mode = GameType.byName(name, null);
            if (mode == null) throw new JEDDefinitionException("Unknown normalGameMode: " + name);
            Object retained=config.getOrElse("globalInventoryItems",DEFAULT_GLOBAL_ITEMS);
            if(!(retained instanceof List<?> list))throw new JEDDefinitionException("globalInventoryItems must be a list of full item registry IDs");
            var items=new HashSet<ResourceLocation>();
            for(var item:list){if(!(item instanceof String text))throw new JEDDefinitionException("globalInventoryItems entries must be item registry ID strings");items.add(JEDDimensionDefinition.id(text,"globalInventoryItems"));}
            return new JEDPlayerOptions(g, m, mode, items);
        }
    }
}
