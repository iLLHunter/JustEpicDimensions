package justepicdimensions.travel;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import justepicdimensions.definition.*;

/** Server-owned values, detached during atomic definition preparation. */
public record JEDTravelOptions(boolean tpjAdminOnly, int tpjAdminLevel) {
    public static final JEDTravelOptions DEFAULT = new JEDTravelOptions(true, 2);
    public static JEDTravelOptions read(JEDConfigRepository.Selection selection) {
        var path = JEDConfigRepository.mainFile(selection);
        if (!Files.exists(path)) return DEFAULT;
        try (var config = CommentedFileConfig.of(path)) {
            config.load();
            Object only = config.getOrElse("tpjAdminOnly", true);
            Object level = config.getOrElse("tpjAdminLevel", 2);
            if (!(only instanceof Boolean value) || !(level instanceof Integer permission) || permission < 0 || permission > 4)
                throw new JEDDefinitionException("tpjAdminOnly must be a boolean and tpjAdminLevel an integer from 0 to 4");
            return new JEDTravelOptions(value, permission);
        }
    }
    public boolean permits(net.minecraft.commands.CommandSourceStack source) {
        return !tpjAdminOnly || source.hasPermission(tpjAdminLevel);
    }
    public boolean bypass(net.minecraft.commands.CommandSourceStack source) {
        return source.hasPermission(Math.max(2, tpjAdminLevel));
    }
}
