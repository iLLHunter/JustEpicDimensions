package arbitrage.coremod;

import cpw.mods.modlauncher.api.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

final class JEDClientTemplateTransforms extends ArbitrageCoremod.Transformer {
    String target(){return "net.minecraft.client.gui.screens.worldselection.WorldOpenFlows";}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){int anchors=0;for(var method:type.methods)if(method.name.equals("createLevelFromExistingSettings")){
        int repository=-1;
        for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call){
            if(call.owner.equals("net/minecraft/server/packs/repository/ServerPacksSource")&&call.name.equals("createPackRepository")){var next=node.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();if(next instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE)repository=store.var;}
            if(call.owner.equals("net/minecraft/server/WorldStem")&&call.name.equals("<init>")){if(repository<0)throw new IllegalStateException("JED integrated template repository anchor missing");var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new VarInsnNode(Opcodes.ALOAD,repository));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/client/JEDClientTemplateLoading","stem","(Lnet/minecraft/server/WorldStem;Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;Lnet/minecraft/server/packs/repository/PackRepository;)Lnet/minecraft/server/WorldStem;",false));method.instructions.insert(call,out);method.maxStack=Math.max(method.maxStack,16);anchors++;}
        }
    }if(anchors!=1)throw new IllegalStateException("JED integrated template world-stem anchor mismatch: "+anchors);System.setProperty("jed.template.coremod.client","verified");return type;}
}
