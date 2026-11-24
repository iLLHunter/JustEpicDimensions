package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Group control modifiers are native-synchronized metadata, separate from ordinary attribute arithmetic. */
final class JEDPlayerAttributeTransforms extends ArbitrageCoremod.Transformer {
    String target() { return "net.minecraft.world.entity.ai.attributes.AttributeInstance"; }

    public ClassNode transform(ClassNode node, ITransformerVotingContext context) {
        int lists = 0, values = 0;
        for (var method : node.methods) {
            if (method.name.equals("getModifiersOrEmpty")
                    && method.desc.equals("(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Ljava/util/Collection;")) {
                for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "justepicdimensions/player/JEDPlayerHealthLimits", "modifiers",
                            "(Ljava/util/Collection;)Ljava/util/Collection;", false));
                    lists++;
                }
            }
            if (method.name.equals("calculateValue") && method.desc.equals("()D")) {
                for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.DRETURN) {
                    var bridge = new InsnList();
                    bridge.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    bridge.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "justepicdimensions/player/JEDPlayerHealthLimits", "maximum",
                            "(DLnet/minecraft/world/entity/ai/attributes/AttributeInstance;)D", false));
                    method.instructions.insertBefore(instruction, bridge);
                    method.maxStack = Math.max(method.maxStack, 3);
                    values++;
                }
            }
        }
        require(lists == 1 && values == 1, "player group attribute anchors");
        System.setProperty("jed.player.attributes.coremod", "verified");
        return node;
    }
}
