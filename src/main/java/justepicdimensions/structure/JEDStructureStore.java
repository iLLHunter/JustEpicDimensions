package justepicdimensions.structure;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** A dimension-owned generation marker; lifecycle reset/delete remove it with the dimension. */
public final class JEDStructureStore extends SavedData {
    public boolean initialized;
    public UUID operation;
    public static JEDStructureStore get(ServerLevel level){return level.getDataStorage().computeIfAbsent(new Factory<>(JEDStructureStore::new,JEDStructureStore::read),"justepicdimensions_structure");}
    private static JEDStructureStore read(CompoundTag tag,HolderLookup.Provider registries){var data=new JEDStructureStore();data.initialized=tag.getBoolean("initialized");if(tag.hasUUID("operation"))data.operation=tag.getUUID("operation");return data;}
    public void begin(){if(operation==null){operation=UUID.randomUUID();setDirty();}}
    public void complete(){initialized=true;setDirty();}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putInt("format_version",1);tag.putBoolean("initialized",initialized);if(operation!=null)tag.putUUID("operation",operation);return tag;}
}
