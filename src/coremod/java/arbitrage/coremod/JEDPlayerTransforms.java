package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Optional backend bridges only suppress gameplay ticks during explicit equipment reconciliation. */
final class JEDPlayerTransforms extends ArbitrageCoremod.Transformer {
    private final boolean curios;
    JEDPlayerTransforms(boolean curios){this.curios=curios;}
    static List<JEDPlayerTransforms> all(){return List.of(new JEDPlayerTransforms(true),new JEDPlayerTransforms(false));}
    String target(){return curios?"top.theillusivec4.curios.common.event.CuriosEventHandler":"io.wispforest.accessories.impl.AccessoriesEventHandler";}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){
        int items=0,equipment=0;
        for(var method:c.methods)for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call){
            boolean within=curios?(method.name.equals("tick")||method.name.startsWith("lambda$tick$")):method.name.equals("onLivingEntityTick");
            if(!within)continue;
            if(call.owner.equals("net/minecraft/world/item/ItemStack")&&call.name.equals("inventoryTick")&&call.desc.equals("(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V")){
                call.setOpcode(Opcodes.INVOKESTATIC);call.owner="justepicdimensions/player/JEDAccessoryRuntime";call.name="tickItem";call.desc="(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V";call.itf=false;items++;
            }
            if(curios&&call.owner.equals("top/theillusivec4/curios/api/type/capability/ICurio")&&call.name.equals("curioTick")&&call.desc.equals("(Ltop/theillusivec4/curios/api/SlotContext;)V")){
                call.setOpcode(Opcodes.INVOKESTATIC);call.owner="justepicdimensions/player/integration/JEDCuriosInventoryAdapter";call.name="tick";call.desc="(Ltop/theillusivec4/curios/api/type/capability/ICurio;Ltop/theillusivec4/curios/api/SlotContext;)V";call.itf=false;equipment++;
            }
            if(!curios&&call.owner.equals("io/wispforest/accessories/api/Accessory")&&call.name.equals("tick")&&call.desc.equals("(Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;)V")){
                call.setOpcode(Opcodes.INVOKESTATIC);call.owner="justepicdimensions/player/integration/JEDAccessoriesInventoryAdapter";call.name="tick";call.desc="(Lio/wispforest/accessories/api/Accessory;Lnet/minecraft/world/item/ItemStack;Lio/wispforest/accessories/api/slot/SlotReference;)V";call.itf=false;equipment++;
            }
        }
        require(items==1&&equipment==1,target()+" player reconciliation anchors items="+items+" equipment="+equipment);
        System.setProperty("jed.player.coremod."+(curios?"curios":"accessories"),"verified");return c;
    }
}
