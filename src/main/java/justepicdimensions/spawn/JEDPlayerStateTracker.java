package justepicdimensions.spawn;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/** Native save scheduling, UUID identity, dimension-specific checkpoints and persistent hardcore history. */
public final class JEDPlayerStateTracker extends SavedData {
    public record Point(ResourceLocation dimension,BlockPos position,float angle,boolean forced,String token) {}
    public static final class Player {public final Map<ResourceLocation,Point> points=new HashMap<>();public ResourceLocation latest;public boolean hardcoreDead;}
    private final Map<UUID,Player> players=new HashMap<>();
    public static JEDPlayerStateTracker get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(JEDPlayerStateTracker::new,JEDPlayerStateTracker::read),"justepicdimensions_players");}
    public Player player(UUID id){return players.computeIfAbsent(id,k->new Player());}
    public void record(ServerPlayer player,ResourceLocation cleared){var data=player(player.getUUID());var pos=player.getRespawnPosition();if(pos==null){if(cleared!=null&&data.points.remove(cleared)!=null){if(cleared.equals(data.latest))data.latest=null;setDirty();}return;}
        var id=player.getRespawnDimension().location();var previous=data.points.get(id);
        if(previous==null||!previous.position().equals(pos)||previous.angle()!=player.getRespawnAngle()||previous.forced()!=player.isRespawnForced()){data.points.put(id,new Point(id,pos.immutable(),player.getRespawnAngle(),player.isRespawnForced(),UUID.randomUUID().toString()));setDirty();}
        if(!id.equals(data.latest)){data.latest=id;setDirty();}
    }
    public void death(ServerPlayer player){var data=player(player.getUUID());if(!data.hardcoreDead){data.hardcoreDead=true;setDirty();}}
    public Set<UUID> references(ResourceLocation dimension){var result=new HashSet<UUID>();players.forEach((id,data)->{if(data.points.containsKey(dimension))result.add(id);});return Set.copyOf(result);}
    public void forget(UUID id,ResourceLocation dimension){var data=players.get(id);if(data!=null&&data.points.remove(dimension)!=null){if(dimension.equals(data.latest))data.latest=null;setDirty();}}
    public static JEDPlayerStateTracker read(CompoundTag tag,HolderLookup.Provider registries){var store=new JEDPlayerStateTracker();for(var entry:tag.getList("players",Tag.TAG_COMPOUND)){var j=(CompoundTag)entry;var data=store.player(j.getUUID("uuid"));data.hardcoreDead=j.getBoolean("hardcore_dead");if(j.contains("latest"))data.latest=ResourceLocation.parse(j.getString("latest"));for(var item:j.getList("points",Tag.TAG_COMPOUND)){var p=(CompoundTag)item;var id=ResourceLocation.parse(p.getString("dimension"));data.points.put(id,new Point(id,BlockPos.of(p.getLong("position")),p.getFloat("angle"),p.getBoolean("forced"),p.getString("token")));}}return store;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putInt("format_version",1);var list=new ListTag();new TreeMap<>(players).forEach((id,data)->{var j=new CompoundTag();j.putUUID("uuid",id);j.putBoolean("hardcore_dead",data.hardcoreDead);if(data.latest!=null)j.putString("latest",data.latest.toString());var points=new ListTag();new TreeMap<>(data.points).values().forEach(p->{var t=new CompoundTag();t.putString("dimension",p.dimension().toString());t.putLong("position",p.position().asLong());t.putFloat("angle",p.angle());t.putBoolean("forced",p.forced());t.putString("token",p.token());points.add(t);});j.put("points",points);list.add(j);});tag.put("players",list);return tag;}
}
