package justepicdimensions.definition;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.*;
import net.minecraft.resources.ResourceLocation;

/** Validated, immutable main-config permissions. These are not permissions for ordinary native datapacks. */
public record JEDConfigControls(boolean replacement, boolean disabling, boolean providers, boolean biomes,
                                boolean information, ResourceLocation worldPreset) {
    public static final JEDConfigControls DEFAULT = new JEDConfigControls(true, false, true, true, false, null);

    public static JEDConfigControls read(JEDConfigRepository.Selection selection) { return read(JEDConfigRepository.mainFile(selection)); }
    public static JEDConfigControls read(Path path) {
        if (!Files.exists(path)) return DEFAULT;
        try (var config = CommentedFileConfig.of(path)) {
            config.load();
            Object preset = config.getOrElse("world_preset", "");
            if (!(preset instanceof String text)) throw new JEDDefinitionException(path + ": world_preset must be a namespaced preset or an empty string");
            return new JEDConfigControls(flag(config, "enableReplacingRegisteredDimensions", true, path),
                flag(config, "enableUnregisteringDimensions", false, path),
                flag(config, "enableOverrideWorldProvider", true, path),
                flag(config, "enableOverrideBiomeProvider", true, path),
                flag(config, "enableLoggingInfo", false, path),
                text.isEmpty() ? null : JEDDimensionDefinition.id(text, path + ".world_preset"));
        } catch (JEDDefinitionException error) { throw error; }
        catch (RuntimeException error) { throw new JEDDefinitionException("Cannot read JED controls from " + path, error); }
    }
    private static boolean flag(CommentedFileConfig config, String key, boolean fallback, Path path) {
        Object value = config.getOrElse(key, fallback);
        if (value instanceof Boolean result) return result;
        throw new JEDDefinitionException(path + ": " + key + " must be a boolean");
    }
}
