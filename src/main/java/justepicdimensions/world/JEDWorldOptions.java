package justepicdimensions.world;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.*;
import justepicdimensions.definition.*;

public record JEDWorldOptions(boolean independent,boolean borders,boolean redirect) {
    public static JEDWorldOptions read(JEDConfigRepository.Selection selection){
        Path path=JEDConfigRepository.mainFile(selection);if(!Files.exists(path))return new JEDWorldOptions(true,true,true);
        try(var config=CommentedFileConfig.of(path)){config.load();return new JEDWorldOptions(flag(config.getOrElse("enableSeparateWorldInfo",true)),flag(config.getOrElse("enableSeparateWorldBorders",true)),flag(config.getOrElse("enableCommandRedirecting",true)));}
    }
    private static boolean flag(Object value){if(value instanceof Boolean result)return result;throw new JEDDefinitionException("Independent world configuration switches must be booleans");}
}
