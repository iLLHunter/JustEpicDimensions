package justepicdimensions.definition;

import net.neoforged.neoforge.common.ModConfigSpec;
import justepicdimensions.config.JEDConfigComments;

/** Global/per-world selection controls retain upstream's independent copy and use switches. */
public final class JEDLoaderConfig {
    public static final ModConfigSpec SPEC;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.comment(JEDConfigComments.text("copyDimensionConfigToWorld",false)).define("copyDimensionConfigToWorld",true);
        builder.comment(JEDConfigComments.text("usePerWorldDimensionConfig",false)).define("usePerWorldDimensionConfig",false);
        builder.comment(JEDConfigComments.text("copyMainConfigToWorld",false)).define("copyMainConfigToWorld",true);
        builder.comment(JEDConfigComments.text("usePerWorldMainConfig",false)).define("usePerWorldMainConfig",false);
        builder.comment(JEDConfigComments.text("useShowcase",false)).define("useShowcase",false);
        builder.comment(JEDConfigComments.text("enableReplacingRegisteredDimensions",false)).define("enableReplacingRegisteredDimensions",true);
        builder.comment(JEDConfigComments.text("enableUnregisteringDimensions",false)).define("enableUnregisteringDimensions",false);
        builder.comment(JEDConfigComments.text("automaticUnloadDimensions",false)).define("automaticUnloadDimensions",false);
        builder.comment(JEDConfigComments.text("automaticUnloadIdleSeconds",false)).defineInRange("automaticUnloadIdleSeconds",300, 1, 31536000);
        builder.comment(JEDConfigComments.text("monitoringEnabled",false)).define("monitoringEnabled",false);
        builder.comment(JEDConfigComments.text("monitoringPolicy",false)).define("monitoringPolicy","");
        builder.comment(JEDConfigComments.text("dimensionSyncTimeoutSeconds",false)).defineInRange("dimensionSyncTimeoutSeconds",600,0,86400);
        builder.comment(JEDConfigComments.text("instanceRegisteredLimit",false)).defineInRange("instanceRegisteredLimit",256,1,65536);
        builder.comment(JEDConfigComments.text("instanceLoadedLimit",false)).defineInRange("instanceLoadedLimit",16,1,65536);
        builder.comment(JEDConfigComments.text("pregenerationConcurrentJobs",false)).defineInRange("pregenerationConcurrentJobs",1,1,32);
        builder.comment(JEDConfigComments.text("enableOverrideWorldProvider",false)).define("enableOverrideWorldProvider",true);
        builder.comment(JEDConfigComments.text("enableOverrideBiomeProvider",false)).define("enableOverrideBiomeProvider",true);
        builder.comment(JEDConfigComments.text("enableLoggingInfo",false)).define("enableLoggingInfo",false);
        builder.comment(JEDConfigComments.text("world_preset",false)).define("world_preset","");
        builder.comment(JEDConfigComments.text("tpjAdminOnly",false)).define("tpjAdminOnly",true);
        builder.comment(JEDConfigComments.text("tpjAdminLevel",false)).defineInRange("tpjAdminLevel",2, 0, 4);
        builder.comment(JEDConfigComments.text("enableSeparateWorldInfo",false)).define("enableSeparateWorldInfo",true);
        builder.comment(JEDConfigComments.text("enableSeparateWorldBorders",false)).define("enableSeparateWorldBorders",true);
        builder.comment(JEDConfigComments.text("enableCommandRedirecting",false)).define("enableCommandRedirecting",true);
        builder.comment(JEDConfigComments.text("initial_spawn_dimension",false)).define("initial_spawn_dimension","");
        builder.comment(JEDConfigComments.text("enablePlayerInventoryGroups",false)).define("enablePlayerInventoryGroups",true);
        builder.comment(JEDConfigComments.text("globalInventoryItems",false)).defineListAllowEmpty("globalInventoryItems",justepicdimensions.player.JEDPlayerOptions.DEFAULT_GLOBAL_ITEMS,()->"minecraft:stone",value->{
            if(!(value instanceof String text))return false;
            try{JEDDimensionDefinition.id(text,"globalInventoryItems");return true;}catch(RuntimeException error){return false;}
        });
        builder.comment(JEDConfigComments.text("enableForcedGameModes",false)).define("enableForcedGameModes",true);
        builder.comment(JEDConfigComments.text("normalGameMode",false)).define("normalGameMode","survival");
        builder.comment(JEDConfigComments.text("config_id",false)).define("config_id","__default");
        builder.comment(JEDConfigComments.text("config_version",false)).defineInRange("config_version",0, 0, Integer.MAX_VALUE);
        SPEC = builder.build();
    }
    private JEDLoaderConfig() {}
}
