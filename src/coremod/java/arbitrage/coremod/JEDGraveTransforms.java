package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Observers never change a backend's placement, drops, retention or recovery decisions. */
final class JEDGraveTransforms extends ArbitrageCoremod.Transformer {
    private final String name,backend,method;private final boolean creation;
    JEDGraveTransforms(String name,String backend,String method,boolean creation){this.name=name;this.backend=backend;this.method=method;this.creation=creation;}
    static List<JEDGraveTransforms> all(){return List.of(
        new JEDGraveTransforms("de.maxhenkel.gravestone.tileentity.GraveStoneTileEntity","gravestone","setDeath",true),
        new JEDGraveTransforms("de.maxhenkel.gravestone.events.DeathEvents","gravestone","playerDeath",false),
        new JEDGraveTransforms("de.maxhenkel.corpse.events.DeathEvents","corpse","playerDeath",false),
        new JEDGraveTransforms("ovh.corail.tombstone.block.entity.BlockEntityPlayerGrave","tombstone","setOwner",true),
        new JEDGraveTransforms("ovh.corail.tombstone.event.EventHandler","tombstone","onDrops",false),
        new JEDGraveTransforms("com.lothrazar.simpletomb.block.BlockEntityTomb","simpletomb","initTombstoneOwner",true),
        new JEDGraveTransforms("com.lothrazar.simpletomb.event.PlayerTombEvents","simpletomb","onPlayerDrops",false),
        new JEDGraveTransforms("com.b1n_ry.yigd.data.DeathInfoManager","yigd","addBackup",true),
        new JEDGraveTransforms("com.b1n_ry.yigd.events.ServerEventHandler","yigd","onPlayerDeathDropItems",false));}
    String target(){return name;}
    public ClassNode transform(ClassNode original,ITransformerVotingContext context){
        var c=new ClassNode();original.accept(c);int methods=0,exits=0;
        for(var m:c.methods)if(m.name.equals(method)){
            if(creation&&backend.equals("simpletomb")&&!m.desc.equals("(Lnet/minecraft/world/entity/player/Player;)V"))continue;
            if(creation&&backend.equals("tombstone")&&!m.desc.equals("(Lnet/minecraft/world/entity/player/Player;JZ)V"))continue;
            if(!m.desc.endsWith(")V"))continue;
            methods++;
            for(var node:m.instructions.toArray())if(node.getOpcode()==Opcodes.RETURN){
                var out=new InsnList();
                if(creation&&backend.equals("yigd")){
                    out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDGraveRegistry","yigdCreated","(Ljava/lang/Object;)V",false));
                }else if(creation){
                    out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(backend.equals("gravestone")?new InsnNode(Opcodes.ACONST_NULL):new VarInsnNode(Opcodes.ALOAD,1));out.add(new LdcInsnNode(backend));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDGraveRegistry","blockCreated","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V",false));
                }else{
                    out.add(new VarInsnNode(Opcodes.ALOAD,(m.access&Opcodes.ACC_STATIC)!=0?0:1));out.add(new LdcInsnNode(backend));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/JEDGraveRegistry","handlerFinished","(Ljava/lang/Object;Ljava/lang/String;)V",false));
                }
                m.instructions.insertBefore(node,out);exits++;
            }
            m.maxStack=Math.max(m.maxStack,3);
        }
        boolean verified=methods==1&&exits>0;
        System.setProperty("jed.grave.coremod."+name,verified?"verified":"unavailable");
        return verified?c:original;
    }
}
