package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Optional Sodium adapter. Its cache and blending remain responsible for every sample. */
final class JEDSodiumColorTransform extends ArbitrageCoremod.Transformer {
    String target(){return "net.caffeinemc.mods.sodium.client.world.LevelSlice";}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){
        int anchors=0;
        require(c.fields.stream().anyMatch(f->f.name.equals("level")&&f.desc.equals("Lnet/minecraft/client/multiplayer/ClientLevel;")),"Sodium tint level");
        for(var m:c.methods)if(m.name.equals("getBlockTint")&&m.desc.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/ColorResolver;)I")){
            var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new VarInsnNode(Opcodes.ALOAD,0));
            out.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"level","Lnet/minecraft/client/multiplayer/ClientLevel;"));
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/client/JEDBiomeColorController","resolver","(Lnet/minecraft/world/level/ColorResolver;Lnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/world/level/ColorResolver;",false));
            out.add(new VarInsnNode(Opcodes.ASTORE,2));m.instructions.insert(out);anchors++;
        }
        require(anchors==1,"Sodium biome tint entry");System.setProperty("arbitrage.render.sodium","verified");return c;
    }
}
