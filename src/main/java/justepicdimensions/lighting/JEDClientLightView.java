package justepicdimensions.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** Main-thread native access. Tests can supply real native engines without loading client classes. */
public interface JEDClientLightView {
    Object lifetime();
    Object source();
    BlockGetter geometry();
    LevelLightEngine engine();
    LevelChunk chunk(int x, int z);
    int minSection();
    int sectionCount();
    int playerSection();
    default int playerChunkX(){return 0;}
    default int playerChunkZ(){return 0;}
    default int minY() { return minSection() << 4; }
    default int maxY() { return (minSection() + sectionCount()) << 4; }
    default void check(BlockPos.MutableBlockPos position) { engine().checkBlock(position); }
}
