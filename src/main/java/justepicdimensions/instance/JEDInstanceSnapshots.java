package justepicdimensions.instance;

import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import justepicdimensions.dimension.JEDDimensionFiles;
import justepicdimensions.player.JEDPlayerInventoryState;

/** Deletes only snapshots bearing a proven, expired managed-instance identity. Active state waits for its normal arrival transaction. */
public final class JEDInstanceSnapshots {
    private static final Map<MinecraftServer,Deque<Path>> QUEUES=new IdentityHashMap<>();
    public static boolean prune(MinecraftServer server,CompoundTag state){
        boolean changed=false;var expired=new HashSet<UUID>();var dimensions=new HashSet<String>();for(var r:JEDInstanceController.instances(server))if(r.status().equals("purged")){expired.add(r.uuid());dimensions.add(r.dimension().toString());}if(expired.isEmpty())return false;
        var groups=state.getCompound("groups");String active=state.getString("active_group");for(String key:List.copyOf(groups.getAllKeys()))if(!key.equals(active)&&expiredInventory(key,expired)){groups.remove(key);state.getCompound("visited_group").remove(key);changed=true;}if(state.contains("groups"))state.put("groups",groups);
        var categories=state.getCompound("categories");for(String category:List.copyOf(categories.getAllKeys())){var data=categories.getCompound(category);var saved=data.getCompound("groups");String current=data.getString("active_group");for(String key:List.copyOf(saved.getAllKeys()))if(!key.equals(current)&&expiredCategory(key,category,expired)){saved.remove(key);changed=true;}data.put("groups",saved);categories.put(category,data);}if(state.contains("categories"))state.put("categories",categories);
        var visits=state.getCompound("visited_dimension");for(String dim:dimensions)if(visits.contains(dim)){visits.remove(dim);changed=true;}if(state.contains("visited_dimension"))state.put("visited_dimension",visits);return changed;
    }
    private static boolean expiredInventory(String key,Set<UUID> expired){if(!key.startsWith("@instance/"))return false;var parts=key.split("/",3);try{return parts.length==3&&expired.contains(UUID.fromString(parts[1]));}catch(IllegalArgumentException ignored){return false;}}
    private static boolean expiredCategory(String key,String category,Set<UUID> expired){var prefix="justepicdimensions:instance_groups/";if(!key.startsWith(prefix))return false;var parts=key.substring(prefix.length()).split("/",4);try{return parts.length==4&&parts[1].equals(category)&&expired.contains(UUID.fromString(parts[0]));}catch(IllegalArgumentException ignored){return false;}}
    private static boolean pruneReturns(MinecraftServer server,CompoundTag persisted){var tag=persisted.getCompound("justepicdimensions:instances");var returns=tag.getCompound("returns");var selected=tag.getCompound("selected");boolean changed=false;for(var r:JEDInstanceController.instances(server))if(r.status().equals("purged")){if(returns.contains(r.uuid().toString())){returns.remove(r.uuid().toString());changed=true;}for(String key:List.copyOf(selected.getAllKeys()))if(selected.getString(key).equals(r.dimension().toString())){selected.remove(key);changed=true;}}if(changed){tag.put("returns",returns);tag.put("selected",selected);persisted.put("justepicdimensions:instances",tag);}return changed;}
    public static void enqueue(MinecraftServer server){
        for(var player:server.getPlayerList().getPlayers()){var state=JEDPlayerInventoryState.read(player);if(prune(server,state))JEDPlayerInventoryState.commit(player,state);pruneReturns(server,player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG));}
        var directory=server.getWorldPath(LevelResource.ROOT).resolve("playerdata").toAbsolutePath().normalize();JEDDimensionFiles.requireSafe(server.getWorldPath(LevelResource.ROOT),directory,false);if(!Files.isDirectory(directory))return;
        try(var files=Files.list(directory)){var queue=QUEUES.computeIfAbsent(server,s->new ArrayDeque<>());for(var file:files.sorted().toList())if(file.getFileName().toString().matches("[a-fA-F0-9-]{36}\\.dat(?:_old)?")&&!queue.contains(file))queue.add(file);}catch(java.io.IOException error){throw new IllegalStateException("Cannot enumerate expired instance snapshots",error);}
    }
    public static void tick(MinecraftServer server){var queue=QUEUES.get(server);if(queue==null||queue.isEmpty())return;var file=queue.removeFirst();String name=file.getFileName().toString();UUID uuid;try{uuid=UUID.fromString(name.substring(0,36));}catch(IllegalArgumentException invalid){com.mojang.logging.LogUtils.getLogger().warn("Skipping non-UUID player data filename during instance retention: {}",file);return;}if(server.getPlayerList().getPlayer(uuid)!=null)return;
        Path temporary=null;try{
            JEDDimensionFiles.requireSafe(server.getWorldPath(LevelResource.ROOT).resolve("playerdata"),file,false);if(!Files.exists(file))return;var root=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());var persisted=root.getCompound("NeoForgeData").getCompound(Player.PERSISTED_NBT_TAG);var state=persisted.getCompound(JEDPlayerInventoryState.KEY);boolean groupsChanged=prune(server,state),returnsChanged=pruneReturns(server,persisted);if(!groupsChanged&&!returnsChanged)return;persisted.put(JEDPlayerInventoryState.KEY,state);
            temporary=Files.createTempFile(file.getParent(),".jed-instance-state-",".dat");NbtIo.writeCompressed(root,temporary);try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception error){com.mojang.logging.LogUtils.getLogger().error("Could not prune expired instance snapshots in "+file+"; player data retained",error);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(java.io.IOException ignored){}}
    }
    public static void stopped(MinecraftServer server){QUEUES.remove(server);}
}
