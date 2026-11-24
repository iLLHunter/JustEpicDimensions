package justepicdimensions.mob;

import net.minecraft.world.entity.MobSpawnType;

/** Native causes plus explicit opt-in for fresh joins whose creator provides no native cause. */
public enum JEDMobSpawnSource {
    NATURAL,CHUNK_GENERATION,SPAWNER,STRUCTURE,BREEDING,MOB_SUMMONED,JOCKEY,EVENT,CONVERSION,
    REINFORCEMENT,TRIGGERED,BUCKET,SPAWN_EGG,COMMAND,DISPENSER,PATROL,TRIAL_SPAWNER,UNKNOWN;
    public static JEDMobSpawnSource of(MobSpawnType cause){return valueOf(cause.name());}
}
