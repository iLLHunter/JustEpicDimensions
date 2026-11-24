package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Arrival processing follows every native/mod event listener, including LOWEST retention handlers. */
final class JEDPlayerNativeTransforms extends ArbitrageCoremod.Transformer {
    private final String name;JEDPlayerNativeTransforms(String name){this.name=name;}
    static List<JEDPlayerNativeTransforms> all(){return List.of(new JEDPlayerNativeTransforms("net.minecraft.server.players.PlayerList"),new JEDPlayerNativeTransforms("net.minecraft.server.level.ServerPlayer"));}
    String target(){return name;}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){
        int login=0,respawn=0,travel=0,deaths=0,retention=0,drops=0;
        for(var method:c.methods){
            for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call&&call.owner.equals("net/neoforged/neoforge/event/EventHooks")){
                String bridge=switch(call.name){case "firePlayerLoggedIn"->"nativeLogin";case "firePlayerRespawnEvent"->"nativeRespawn";case "firePlayerChangedDimensionEvent"->"nativeTravel";default->null;};
                if(bridge!=null){if(bridge.equals("nativeLogin"))login++;else if(bridge.equals("nativeRespawn"))respawn++;else travel++;call.owner="justepicdimensions/player/JEDPlayerStateController";call.name=bridge;}
            }
            if(name.endsWith("ServerPlayer")&&method.name.equals("restoreFrom"))for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/GameRules")&&call.name.equals("getBoolean")&&call.desc.equals("(Lnet/minecraft/world/level/GameRules$Key;)Z")){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDPlayerStateController","keepInventory","(ZLnet/minecraft/server/level/ServerPlayer;)Z",false));method.instructions.insert(call,out);retention++;method.maxStack+=1;
            }
            if(name.endsWith("ServerPlayer")&&method.name.equals("die")&&method.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;)V")){
                for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call&&call.name.equals("dropAllDeathLoot")&&call.desc.equals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDGraveRegistry","dropsDone","(Lnet/minecraft/server/level/ServerPlayer;)V",false));method.instructions.insert(call,out);drops++;
                }
                var begin=new InsnList();begin.add(new VarInsnNode(Opcodes.ALOAD,0));begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDGraveRegistry","begin","(Lnet/minecraft/server/level/ServerPlayer;)V",false));method.instructions.insert(begin);
                var returns=Arrays.stream(method.instructions.toArray()).filter(n->n.getOpcode()==Opcodes.RETURN).toList();require(returns.size()==2,"player death cancellation/commit exits");
                for(var node:returns){var end=new InsnList();end.add(new VarInsnNode(Opcodes.ALOAD,0));end.add(new InsnNode(node==returns.getLast()?Opcodes.ICONST_1:Opcodes.ICONST_0));end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDGraveRegistry","finish","(Lnet/minecraft/server/level/ServerPlayer;Z)V",false));method.instructions.insertBefore(node,end);deaths++;}
                method.maxStack=Math.max(method.maxStack,2);
            }
        }
        if(name.endsWith("PlayerList"))require(login==1&&respawn==1&&travel==0,"native player login/respawn anchors");
        else require(travel==1&&deaths==2&&login==0&&respawn==0&&retention==1&&drops==1,"native player travel/death/retention anchors");
        System.setProperty("jed.player.coremod."+name,"verified");return c;
    }
}
