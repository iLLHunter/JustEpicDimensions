package arbitrage;

import net.neoforged.fml.common.Mod;

/** The visible mod identity; Java transformers are discovered earlier from the nested library. */
@Mod(Arbitrage.ID)
public final class Arbitrage {
    public static final String ID="arbitrage";
    public Arbitrage(){
        if(!"verified".equals(System.getProperty("arbitrage.coremod.registered")))
            throw new IllegalStateException("Arbitrage's transformer library (arbitrage-transformers, packed inside Arbitrage, which is packed inside the Just Epic Dimensions JAR) was not loaded, so Just Epic Dimensions cannot run. Reinstall the Just Epic Dimensions JAR unchanged and not extracted, and remove any separate arbitrage JAR from the mods folder.");
    }
}
