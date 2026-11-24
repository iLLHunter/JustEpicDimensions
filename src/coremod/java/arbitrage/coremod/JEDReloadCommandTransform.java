package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve vanilla reload handling, adding JED's rejection reason for its caller first. */
final class JEDReloadCommandTransform extends ArbitrageCoremod.Transformer {
    String target(){return "net.minecraft.server.commands.ReloadCommand";}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){
        int anchors=0;
        for(var m:c.methods)if(m.name.equals("reloadPacks")&&m.desc.equals("(Ljava/util/Collection;Lnet/minecraft/commands/CommandSourceStack;)V"))
            for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/server/MinecraftServer")&&call.name.equals("reloadResources")&&call.desc.equals("(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;")){
                m.instructions.insertBefore(n,new VarInsnNode(Opcodes.ALOAD,1));
                call.setOpcode(Opcodes.INVOKESTATIC);call.owner="justepicdimensions/definition/JEDReloadFeedback";call.name="reload";
                call.desc="(Lnet/minecraft/server/MinecraftServer;Ljava/util/Collection;Lnet/minecraft/commands/CommandSourceStack;)Ljava/util/concurrent/CompletableFuture;";call.itf=false;anchors++;
            }
        require(anchors==1,"vanilla reload feedback");return c;
    }
}
