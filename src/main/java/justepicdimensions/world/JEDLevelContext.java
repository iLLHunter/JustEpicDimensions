package justepicdimensions.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;

/** Ownership and construction policy; mutable engine state remains in native data APIs. */
public record JEDLevelContext(ServerLevel level,ServerLevelData data,boolean independent,boolean authored,JEDWorldOptions options) {}
