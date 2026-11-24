package justepicdimensions.dimension;

import com.google.gson.*;
import justepicdimensions.definition.*;

/** Immutable global defaults from the committed main-config snapshot; dimension JSON supplies optional overrides. */
public record JEDIdleUnloadOptions(boolean enabled,int idleSeconds) {
    public static final String ENABLED="automaticUnloadDimensions", DELAY="automaticUnloadIdleSeconds";
    public static final int DEFAULT_SECONDS=300, MAX_SECONDS=31536000;
    public static final JEDIdleUnloadOptions DEFAULT=new JEDIdleUnloadOptions(false,DEFAULT_SECONDS);
    public record Effective(boolean enabled,int idleSeconds,boolean temporary,boolean keepLoaded){}
    public static JEDIdleUnloadOptions read(JsonObject values){
        var automatic=values.get(ENABLED);var delay=values.get(DELAY);
        if(automatic==null||!automatic.isJsonPrimitive()||!automatic.getAsJsonPrimitive().isBoolean())
            throw new JEDDefinitionException(ENABLED+": expected a boolean");
        int seconds=JEDJson.integer(delay,DELAY);
        if(seconds<1||seconds>MAX_SECONDS)throw new JEDDefinitionException(DELAY+": expected 1 to "+MAX_SECONDS+" seconds");
        return new JEDIdleUnloadOptions(automatic.getAsBoolean(),seconds);
    }
    public Effective resolve(JEDDimensionDefinition definition){
        if(definition==null)return new Effective(enabled,idleSeconds,false,false);
        var lifecycle=definition.lifecycle();var root=definition.source().value().getAsJsonObject();
        var raw=root.has("lifecycle")?root.getAsJsonObject("lifecycle"):new JsonObject();
        // Excluded temporary worlds keep their existing local opt-in and local timeout/default.
        boolean automatic=lifecycle.temporary()||raw.has("automatic_unload")?lifecycle.automaticUnload():enabled;
        int seconds=lifecycle.temporary()||raw.has("idle_seconds")?lifecycle.idleSeconds():idleSeconds;
        return new Effective(automatic,seconds,lifecycle.temporary(),lifecycle.keepLoaded());
    }
}
