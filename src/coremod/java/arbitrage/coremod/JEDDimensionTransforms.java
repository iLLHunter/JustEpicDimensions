package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

final class JEDDimensionTransforms {
    static final String HOOKS = "justepicdimensions/dimension/JEDDimensionManager";
    static final String TRAVEL = "justepicdimensions/dimension/JEDPlayerReadiness";
    static final class Server extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.MinecraftServer"; }
        public ClassNode transform(ClassNode c, ITransformerVotingContext context) {
            var inputs = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "jed$levelInputs", "()[Ljava/lang/Object;", null, null);
            inputs.instructions.add(new InsnNode(Opcodes.ICONST_2)); inputs.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            String[] fields = {"executor", "storageSource"}; String[] desc = {"Ljava/util/concurrent/Executor;", "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"};
            for (int i = 0; i < 2; i++) {
                final int index = i; require(c.fields.stream().anyMatch(f -> f.name.equals(fields[index]) && f.desc.equals(desc[index])), fields[i]);
                inputs.instructions.add(new InsnNode(Opcodes.DUP)); inputs.instructions.add(new InsnNode(Opcodes.ICONST_0 + i)); inputs.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                inputs.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, c.name, fields[i], desc[i])); inputs.instructions.add(new InsnNode(Opcodes.AASTORE));
            }
            inputs.instructions.add(new InsnNode(Opcodes.ARETURN)); inputs.maxStack = 4; inputs.maxLocals = 1; c.methods.add(inputs);
            int getters = 0, startup = 0;
            for (var m : c.methods) {
                if (m.name.equals("getLevel") && m.desc.equals("(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;")) {
                    for (var n : m.instructions.toArray()) if (n.getOpcode() == Opcodes.ARETURN) {
                        var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "lookup", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;", false));
                        m.instructions.insertBefore(n, code); getters++;
                    }
                }
                if (m.name.equals("createLevels")) for (var n : m.instructions.toArray()) if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.owner.equals("net/minecraft/world/level/dimension/LevelStem") && f.name.equals("OVERWORLD")) {
                    var next = ArbitrageCoremod.Storage.real(n.getNext());
                    if (next instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ACMPEQ) {
                        var keyLoad = ArbitrageCoremod.Storage.realPrevious(n.getPrevious()); require(keyLoad instanceof VarInsnNode, "startup dimension key local");
                        var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode)keyLoad).var));
                        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "loadAtStartup", "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/resources/ResourceKey;)Z", false)); code.add(new JumpInsnNode(Opcodes.IFEQ, j.label));
                        m.instructions.insert(j, code); startup++;
                    }
                }
            }
            require(getters == 1 && startup == 1, "server level lifecycle " + getters + "/" + startup);
            System.setProperty("jed.dimension.coremod.server", "verified"); return c;
        }
    }
    static final class Player extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.level.ServerPlayer"; }
        public ClassNode transform(ClassNode c, ITransformerVotingContext context) {
            int transitions = 0, teleports = 0, direct = 0;
            for (var m : c.methods) {
                if (m.name.equals("changeDimension") && m.desc.equals("(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;")) {
                    var code = new InsnList(); var ready = new LabelNode();
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRAVEL, "defer", "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/portal/DimensionTransition;)Z", false));
                    code.add(new JumpInsnNode(Opcodes.IFEQ, ready)); code.add(new InsnNode(Opcodes.ACONST_NULL)); code.add(new InsnNode(Opcodes.ARETURN)); code.add(ready); m.instructions.insert(code); transitions++;
                }
                if (m.name.equals("teleportTo") && m.desc.equals("(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")) {
                    var code = new InsnList(); var ready = new LabelNode(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    for (int i : new int[]{2,4,6}) code.add(new VarInsnNode(Opcodes.DLOAD, i)); code.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    code.add(new VarInsnNode(Opcodes.FLOAD, 9)); code.add(new VarInsnNode(Opcodes.FLOAD, 10));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRAVEL, "deferTeleport", "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", false));
                    code.add(new JumpInsnNode(Opcodes.IFEQ, ready)); code.add(new InsnNode(Opcodes.ICONST_0)); code.add(new InsnNode(Opcodes.IRETURN)); code.add(ready); m.instructions.insert(code); m.maxStack = Math.max(m.maxStack, 11); teleports++;
                }
                if (m.name.equals("teleportTo") && m.desc.equals("(Lnet/minecraft/server/level/ServerLevel;DDDFF)V")) {
                    var code = new InsnList(); var ready = new LabelNode(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    for (int i : new int[]{2,4,6}) code.add(new VarInsnNode(Opcodes.DLOAD, i)); code.add(new VarInsnNode(Opcodes.FLOAD, 8)); code.add(new VarInsnNode(Opcodes.FLOAD, 9));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRAVEL, "deferDirectTeleport", "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;DDDFF)Z", false));
                    code.add(new JumpInsnNode(Opcodes.IFEQ, ready)); code.add(new InsnNode(Opcodes.RETURN)); code.add(ready); m.instructions.insert(code); m.maxStack = Math.max(m.maxStack, 10); direct++;
                }
            }
            require(transitions == 1 && teleports == 1 && direct == 1, "player travel gates"); System.setProperty("jed.dimension.coremod.player", "verified"); return c;
        }
    }
    static final class Respawn extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.players.PlayerList"; }
        public ClassNode transform(ClassNode c, ITransformerVotingContext context) {
            int count = 0;
            for (var m : c.methods) if (m.name.equals("respawn") && m.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;")) {
                var code = new InsnList(); var ready = new LabelNode(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new VarInsnNode(Opcodes.ILOAD, 2)); code.add(new VarInsnNode(Opcodes.ALOAD, 3));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRAVEL, "deferRespawn", "(Lnet/minecraft/server/players/PlayerList;Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Z", false));
                code.add(new JumpInsnNode(Opcodes.IFEQ, ready)); code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new InsnNode(Opcodes.ARETURN)); code.add(ready); m.instructions.insert(code); m.maxStack = Math.max(m.maxStack, 4); count++;
            }
            require(count == 1, "respawn gate"); System.setProperty("jed.dimension.coremod.respawn", "verified"); return c;
        }
    }
    static final class ClientCommand extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.network.ServerGamePacketListenerImpl"; }
        public ClassNode transform(ClassNode c, ITransformerVotingContext context) {
            int count = 0;
            for (var m : c.methods) if (m.name.equals("handleClientCommand") && m.desc.equals("(Lnet/minecraft/network/protocol/game/ServerboundClientCommandPacket;)V")) {
                for (var n : m.instructions.toArray()) if (n instanceof MethodInsnNode call && call.owner.equals("net/minecraft/network/protocol/PacketUtils") && call.name.equals("ensureRunningOnSameThread")) {
                    var code = new InsnList(); var ready = new LabelNode(); code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TRAVEL, "deferClientCommand", "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/network/protocol/game/ServerboundClientCommandPacket;)Z", false));
                    code.add(new JumpInsnNode(Opcodes.IFEQ, ready)); code.add(new InsnNode(Opcodes.RETURN)); code.add(ready); m.instructions.insert(call, code); count++;
                }
            }
            require(count == 1, "client respawn command gate"); System.setProperty("jed.dimension.coremod.command", "verified"); return c;
        }
    }
}
