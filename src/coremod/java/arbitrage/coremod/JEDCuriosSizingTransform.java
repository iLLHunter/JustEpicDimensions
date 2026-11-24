package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve native mid-pass resize deferral; permit an explicit final sizing pass. */
final class JEDCuriosSizingTransform extends ArbitrageCoremod.Transformer {
    String target(){return "top.theillusivec4.curios.common.inventory.CurioStacksHandler";}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){
        int count=0;
        for(var method:c.methods)if(method.name.equals("update")&&method.desc.equals("()V"))
            for(var node:method.instructions.toArray())if(node instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(c.name)&&field.name.equals("clearCacheTick")&&field.desc.equals("I")){
                var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));
                code.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"itemHandler","Ltop/theillusivec4/curios/api/type/capability/ICuriosItemHandler;"));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/player/integration/JEDCuriosInventoryAdapter","sizingCacheTick","(ILtop/theillusivec4/curios/api/type/capability/ICuriosItemHandler;)I",false));
                method.instructions.insert(field,code);count++;
            }
        require(count==1,"Curios final sizing anchor count="+count);System.setProperty("jed.player.coremod.curios_sizing","verified");return c;
    }
}
