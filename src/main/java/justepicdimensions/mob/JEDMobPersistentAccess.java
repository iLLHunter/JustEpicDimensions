package justepicdimensions.mob;

import net.minecraft.nbt.CompoundTag;

/** Read existing NeoForge entity data without allocating an empty compound for unaffected mobs. */
public interface JEDMobPersistentAccess {CompoundTag jed$mobPersistentData();}
