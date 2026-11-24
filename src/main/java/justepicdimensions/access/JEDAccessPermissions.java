package justepicdimensions.access;

import java.lang.reflect.*;
import java.util.UUID;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.*;

/** Optional LuckPerms calls use its public API types, without a hard class-loading or runtime dependency. */
public final class JEDAccessPermissions {
    /** Timeout only JED's wait. The provider may finish later and must still be releasable. */
    private static final class Pending extends java.util.concurrent.CompletableFuture<Object>{
        final java.util.concurrent.CompletableFuture<?> loading;boolean released;
        Pending(java.util.concurrent.CompletableFuture<?> loading,long timeout,java.util.concurrent.TimeUnit unit){
            this.loading=java.util.Objects.requireNonNull(loading);
            loading.whenComplete((user,error)->{if(error==null)complete(user);else completeExceptionally(error);});
            orTimeout(timeout,unit);whenComplete((user,error)->{if(error!=null)release();});
        }
        synchronized void release(){if(!released){released=true;JEDAccessPermissions.release(loading);}}
    }

    private static final ThreadLocal<Boolean> OFFLINE=ThreadLocal.withInitial(()->false);
    public static <T>T beforeLogin(boolean offline,java.util.function.Supplier<T> action){boolean previous=OFFLINE.get();if(offline)OFFLINE.set(true);try{return action.get();}finally{if(previous)OFFLINE.set(true);else OFFLINE.remove();}}
    public static boolean test(ServerPlayer player,String provider,String name){
        if(provider.equals("neoforge")){
            var node=PermissionAPI.getRegisteredNodes().stream().filter(n->n.getNodeName().equals(name)).findFirst()
                .orElseThrow(()->new JEDDefinitionException("Unknown registered NeoForge permission node: "+name));
            if(node.getType()!=PermissionTypes.BOOLEAN)throw new JEDDefinitionException("Access requires a boolean NeoForge permission node: "+name);
            @SuppressWarnings("unchecked") var flag=(PermissionNode<Boolean>)node;
            return OFFLINE.get()?PermissionAPI.getOfflinePermission(player.getUUID(),flag):PermissionAPI.getPermission(player,flag);
        }
        try{
            var loader=JEDAccessPermissions.class.getClassLoader();
            var api=Class.forName("net.luckperms.api.LuckPerms",true,loader);
            var instance=Class.forName("net.luckperms.api.LuckPermsProvider",true,loader).getMethod("get").invoke(null);
            var manager=api.getMethod("getUserManager").invoke(instance);
            var user=Class.forName("net.luckperms.api.model.user.UserManager",true,loader).getMethod("getUser",UUID.class).invoke(manager,player.getUUID());
            if(user==null)throw new JEDDefinitionException("LuckPerms has not loaded this player's permission data");
            var cached=Class.forName("net.luckperms.api.model.PermissionHolder",true,loader).getMethod("getCachedData").invoke(user);
            var permission=Class.forName("net.luckperms.api.cacheddata.CachedDataManager",true,loader).getMethod("getPermissionData").invoke(cached);
            var value=Class.forName("net.luckperms.api.cacheddata.CachedPermissionData",true,loader).getMethod("checkPermission",String.class).invoke(permission,name);
            return (boolean)Class.forName("net.luckperms.api.util.Tristate",true,loader).getMethod("asBoolean").invoke(value);
        }catch(ReflectiveOperationException|LinkageError error){
            var cause=error instanceof InvocationTargetException invocation?invocation.getCause():error;
            throw new JEDDefinitionException("LuckPerms permission evaluation is unavailable for "+name,cause);
        }
    }
    public static java.util.concurrent.CompletableFuture<?> prepare(ServerPlayer player){
        try{
            var loader=JEDAccessPermissions.class.getClassLoader();
            var api=Class.forName("net.luckperms.api.LuckPerms",true,loader);
            var instance=Class.forName("net.luckperms.api.LuckPermsProvider",true,loader).getMethod("get").invoke(null);
            var manager=api.getMethod("getUserManager").invoke(instance);
            var type=Class.forName("net.luckperms.api.model.user.UserManager",true,loader);
            if(type.getMethod("getUser",UUID.class).invoke(manager,player.getUUID())!=null)return java.util.concurrent.CompletableFuture.completedFuture(null);
            return new Pending((java.util.concurrent.CompletableFuture<?>)type.getMethod("loadUser",UUID.class).invoke(manager,player.getUUID()),30,java.util.concurrent.TimeUnit.SECONDS);
        }catch(ClassNotFoundException absent){return java.util.concurrent.CompletableFuture.completedFuture(null);}
        catch(ReflectiveOperationException|LinkageError|RuntimeException error){return java.util.concurrent.CompletableFuture.failedFuture(new JEDDefinitionException("Cannot prepare LuckPerms admission data",error));}
    }
    public static void release(java.util.concurrent.CompletableFuture<?> prepared){
        if(prepared instanceof Pending pending){pending.release();return;}
        prepared.whenComplete((user,error)->{
            if(error!=null||user==null)return;
            try{
                var loader=JEDAccessPermissions.class.getClassLoader();
                var api=Class.forName("net.luckperms.api.LuckPerms",true,loader);
                var instance=Class.forName("net.luckperms.api.LuckPermsProvider",true,loader).getMethod("get").invoke(null);
                var manager=api.getMethod("getUserManager").invoke(instance);
                var userType=Class.forName("net.luckperms.api.model.user.User",true,loader);
                Class.forName("net.luckperms.api.model.user.UserManager",true,loader).getMethod("cleanupUser",userType).invoke(manager,user);
            }catch(ReflectiveOperationException|LinkageError failure){com.mojang.logging.LogUtils.getLogger().warn("JED could not release abandoned LuckPerms admission data",failure);}
        });
    }
    private JEDAccessPermissions(){}
}
