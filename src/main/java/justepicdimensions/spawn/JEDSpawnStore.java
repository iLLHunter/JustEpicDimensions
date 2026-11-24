package justepicdimensions.spawn;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Dimension-owned native SavedData: reset/delete naturally remove the chosen spawn. */
public final class JEDSpawnStore extends SavedData {
    public BlockPos position;public float angle;
    public static JEDSpawnStore get(ServerLevel level){return level.getDataStorage().computeIfAbsent(new Factory<>(JEDSpawnStore::new,JEDSpawnStore::read),"justepicdimensions_spawn");}
    static JEDSpawnStore read(CompoundTag tag,HolderLookup.Provider registries){var data=new JEDSpawnStore();if(tag.contains("position",Tag.TAG_LONG)){data.position=BlockPos.of(tag.getLong("position"));data.angle=tag.getFloat("angle");}return data;}
    public void set(BlockPos pos,float yaw){position=pos.immutable();angle=yaw;setDirty();}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putInt("format_version",1);if(position!=null){tag.putLong("position",position.asLong());tag.putFloat("angle",angle);}return tag;}
}
