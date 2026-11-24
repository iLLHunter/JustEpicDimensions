package justepicdimensions.config;

import java.io.IOException;
import java.nio.file.*;
import justepicdimensions.definition.JEDDefinitionException;
import net.neoforged.fml.loading.FMLPaths;

/** One directory for JED's authored data and TOMLs; old files migrate without replacing edits. */
public final class JEDConfigPaths {
    public static final String DIRECTORY="justepicdimensions", MAIN="justepicdimensions.toml", CLIENT="justepicdimensions-client.toml";
    public static final String MAIN_REGISTRATION=DIRECTORY+"/"+MAIN, CLIENT_REGISTRATION=DIRECTORY+"/"+CLIENT;
    public static Path global(){return global(FMLPaths.CONFIGDIR.get());}
    public static Path global(Path config){return config.resolve(DIRECTORY).toAbsolutePath().normalize();}
    public static Path main(Path config){return global(config).resolve(MAIN);}
    public static Path worldMain(Path world){return world.resolve("serverconfig").resolve(DIRECTORY).resolve(MAIN).toAbsolutePath().normalize();}
    public static void migrateGlobal(){migrateGlobal(FMLPaths.CONFIGDIR.get());}
    public static synchronized void migrateGlobal(Path config){migrate(config.resolve(MAIN),main(config));migrate(config.resolve(CLIENT),global(config).resolve(CLIENT));}
    public static synchronized void migrateWorld(Path world){migrate(world.resolve("serverconfig").resolve(MAIN),worldMain(world));}
    private static void migrate(Path old,Path target){
        if(!Files.exists(old))return;
        safe(old);safe(target);
        if(Files.exists(target)){
            com.mojang.logging.LogUtils.getLogger().warn("JED uses {}; legacy configuration {} also exists and was retained without overwriting either file",target,old);return;
        }
        try{Files.createDirectories(target.getParent());Files.move(old,target);}
        catch(IOException error){throw new JEDDefinitionException("Cannot migrate JED configuration from "+old+" to "+target+"; no settings were intentionally replaced",error);}
    }
    private static void safe(Path path){
        // The TOML itself must not be a link; the operator-chosen directories that contain it may be.
        if(Files.isSymbolicLink(path.toAbsolutePath().normalize()))throw new JEDDefinitionException("Configuration symlink is not supported: "+path.toAbsolutePath().normalize());
        if(Files.exists(path)&&!Files.isRegularFile(path))throw new JEDDefinitionException("Not a configuration file: "+path);
    }
    /** Files owned by the independent TOML copy/editor, including its sibling backups. */
    public static boolean toml(Path relative){return relative.getNameCount()==1&&(relative.toString().startsWith(MAIN)||relative.toString().startsWith(CLIENT));}
    private JEDConfigPaths(){}
}
