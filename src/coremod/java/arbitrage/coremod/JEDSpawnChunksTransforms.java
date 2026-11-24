package arbitrage.coremod;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;

/** Native spawn tickets delegate only for dimensions managed by the spawn-retention controller. */
final class JEDSpawnChunksTransforms extends ArbitrageCoremod.Transformer {
    private static final String HOOK = "justepicdimensions/spawn/JEDSpawnChunks";
    private static final String LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private final String name;
    JEDSpawnChunksTransforms(String name) { this.name = name; }
    String target() { return name; }
    static List<JEDSpawnChunksTransforms> all() {
        return List.of(new JEDSpawnChunksTransforms("net.minecraft.server.level.ServerLevel"), new JEDSpawnChunksTransforms("net.minecraft.server.MinecraftServer"));
    }
    private static MethodInsnNode hook(String method, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, method, descriptor, false); }
    public ClassNode transform(ClassNode c, ITransformerVotingContext context) {
        int radius = 0, changed = 0, activity = 0;
        for (var method : c.methods) {
            if (name.endsWith(".ServerLevel") && method.name.equals("setDefaultSpawnPos")) {
                for (var node : method.instructions.toArray()) {
                    if (node instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/level/GameRules") && call.name.equals("getInt")) {
                        var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(hook("nativeRadius", "(I" + LEVEL + ")I")); method.instructions.insert(call, code); radius++;
                    }
                    if (node.getOpcode() == Opcodes.RETURN) {
                        var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(hook("spawnChanged", "(" + LEVEL + ")V")); method.instructions.insertBefore(node, code); changed++;
                    }
                }
            }
            if (name.endsWith(".ServerLevel") && method.name.equals("tick")) for (var node : method.instructions.toArray()) {
                if (node instanceof MethodInsnNode call && call.owner.equals("net/neoforged/neoforge/common/world/chunk/ForcedChunkManager") && call.name.equals("hasForcedChunks") && call.desc.equals("(" + LEVEL + ")Z")) {
                    var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(hook("continuous", "(Z" + LEVEL + ")Z")); method.instructions.insert(call, code); activity++;
                }
            }
            if (name.endsWith(".MinecraftServer") && method.name.equals("prepareLevels")) for (var node : method.instructions.toArray()) {
                if (node instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/level/GameRules") && call.name.equals("getInt")) {
                    var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(hook("startupRadius", "(ILnet/minecraft/server/MinecraftServer;)I")); method.instructions.insert(call, code); radius++;
                }
            }
            method.maxStack = Math.max(method.maxStack, 8);
        }
        require(radius == 1 && changed == (name.endsWith(".ServerLevel") ? 1 : 0) && activity == (name.endsWith(".ServerLevel") ? 1 : 0), name + " spawn-chunk anchors " + radius + "/" + changed + "/" + activity);
        System.setProperty("jed.spawnchunks.coremod." + name, "verified");
        return c;
    }
}
