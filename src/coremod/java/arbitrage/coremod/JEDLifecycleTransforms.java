package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Lifecycle-only bridges: saved destination selection, final startup bake and closed storage. */
final class JEDLifecycleTransforms {
    static final String LIFECYCLE = "justepicdimensions/dimension/JEDDimensionLifecycle";
    static final String SAFETY = "justepicdimensions/dimension/JEDShutdownSafety";
    static final class Bake extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.world.level.levelgen.WorldDimensions"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0;
            for (var method : type.methods) if (method.name.equals("bake") && method.desc.equals("(Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;")) {
                int local=method.maxLocals++;
                var begin=new InsnList();begin.add(new VarInsnNode(Opcodes.ALOAD,0));begin.add(new VarInsnNode(Opcodes.ALOAD,1));
                begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/definition/JEDConfiguredDimensions","prepare","(Lnet/minecraft/world/level/levelgen/WorldDimensions;Lnet/minecraft/core/Registry;)Ljustepicdimensions/definition/JEDConfiguredDimensions$Baking;",false));
                begin.add(new VarInsnNode(Opcodes.ASTORE,local));begin.add(new VarInsnNode(Opcodes.ALOAD,local));
                begin.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"justepicdimensions/definition/JEDConfiguredDimensions$Baking","dimensions","()Lnet/minecraft/world/level/levelgen/WorldDimensions;",false));begin.add(new VarInsnNode(Opcodes.ASTORE,0));
                begin.add(new VarInsnNode(Opcodes.ALOAD,local));begin.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"justepicdimensions/definition/JEDConfiguredDimensions$Baking","registry","()Lnet/minecraft/core/Registry;",false));begin.add(new VarInsnNode(Opcodes.ASTORE,1));method.instructions.insert(begin);
                for (var node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.ARETURN) {
                    var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD,1));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,JEDDefinitionTransforms.HOOK,"baked","(Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;",false));
                    method.instructions.insertBefore(node,out); anchors++;
                }
            }
            require(anchors == 1,"final dimension bake"); System.setProperty("jed.dimension.coremod.bake","verified"); return type;
        }
    }
    static final class Storage extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.world.level.storage.LevelStorageSource"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0;
            for (var method : type.methods) if ((method.name.equals("validateAndCreateAccess") || method.name.equals("createAccess")) && method.desc.equals("(Ljava/lang/String;)Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;")) {
                var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD,0)); out.add(new VarInsnNode(Opcodes.ALOAD,1));
                out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/dimension/JEDShutdownCleanup","beforeAccess","(Lnet/minecraft/world/level/storage/LevelStorageSource;Ljava/lang/String;)V",false));
                method.instructions.insert(out); anchors++;
            }
            require(anchors == 2,"save access recovery"); System.setProperty("jed.dimension.coremod.save_access","verified"); return type;
        }
    }
    static final class Shutdown extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.MinecraftServer"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0, scopes = 0;
            for (var method : type.methods) if (method.name.equals("stopServer") && method.desc.equals("()V")) {
                for (var node : method.instructions.toArray()) if (node instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess") && call.name.equals("close") && call.desc.equals("()V")) {
                    var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD,0));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/dimension/JEDShutdownCleanup","closed","(Lnet/minecraft/server/MinecraftServer;)V",false));
                    method.instructions.insert(call,out); anchors++;
                }
                var start = new LabelNode(); var end = new LabelNode(); var handler = new LabelNode();
                var begin = new InsnList(); begin.add(new VarInsnNode(Opcodes.ALOAD,0));
                begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SAFETY,"enter","(Lnet/minecraft/server/MinecraftServer;)V",false)); begin.add(start);
                method.instructions.insert(begin);
                for (var node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.RETURN) {
                    var finish = new InsnList(); finish.add(new VarInsnNode(Opcodes.ALOAD,0));
                    finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SAFETY,"exit","(Lnet/minecraft/server/MinecraftServer;)V",false)); method.instructions.insertBefore(node,finish);
                }
                int failure = method.maxLocals++;
                method.instructions.add(end); method.instructions.add(handler);
                method.instructions.add(new VarInsnNode(Opcodes.ASTORE,failure)); method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SAFETY,"exit","(Lnet/minecraft/server/MinecraftServer;)V",false));
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD,failure)); method.instructions.add(new InsnNode(Opcodes.ATHROW));
                method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null)); scopes++;
            }
            require(anchors == 1 && scopes == 1,"successful native storage close / shutdown scope");
            System.setProperty("jed.dimension.coremod.closed_save","verified"); System.setProperty("jed.dimension.coremod.shutdown_scope","verified"); return type;
        }
    }
    static final class UnloadOwnership extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.level.ChunkMap"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            String map = "it/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap";
            require(type.fields.stream().filter(f -> f.name.equals("pendingUnloads") && f.desc.equals("L"+map+";")).count() == 1,"pending unload ownership map");
            int anchors = 0;
            for (var method : type.methods) if (method.name.equals("lambda$scheduleUnload$12") && method.desc.equals("(Lnet/minecraft/server/level/ChunkHolder;J)V")) {
                require(java.util.Arrays.stream(method.instructions.toArray()).filter(n -> n instanceof MethodInsnNode call && call.owner.equals("net/minecraft/server/level/ChunkHolder") && call.name.equals("isReadyForSaving") && call.desc.equals("()Z")).count() == 1,"unload callback readiness");
                var proceed = new LabelNode(); var begin = new InsnList();
                // A reclaimed holder no longer belongs to this unload callback. Native code already
                // checks this before saving; check before retrying too, including during normal play.
                begin.add(new VarInsnNode(Opcodes.ALOAD,0)); begin.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,"pendingUnloads","L"+map+";")); begin.add(new VarInsnNode(Opcodes.LLOAD,2));
                begin.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,map,"get","(J)Ljava/lang/Object;",false)); begin.add(new VarInsnNode(Opcodes.ALOAD,1)); begin.add(new JumpInsnNode(Opcodes.IF_ACMPEQ,proceed));
                begin.add(new InsnNode(Opcodes.RETURN)); begin.add(proceed); method.instructions.insert(begin); method.maxStack = Math.max(method.maxStack,3); anchors++;
            }
            require(anchors == 1,"native unload ownership callback"); System.setProperty("jed.dimension.coremod.unload_ownership","verified"); return type;
        }
    }
    static final class Login extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.players.PlayerList"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0;
            for (var method : type.methods) if (method.name.equals("placeNewPlayer"))
                for (var node : method.instructions.toArray()) if (node instanceof MethodInsnNode call && call.owner.equals("net/minecraft/server/MinecraftServer") && call.name.equals("getLevel") && call.desc.equals("(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;")) {
                    method.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,2));
                    method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/spawn/JEDSpawnController","loginLevel","(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/server/level/ServerLevel;",false)); anchors++;
                }
            require(anchors == 1,"saved login dimension lookup"); System.setProperty("jed.dimension.coremod.login","verified"); return type;
        }
    }
    static final class Respawn extends ArbitrageCoremod.Transformer {
        String target() { return "net.minecraft.server.level.ServerPlayer"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0;
            for (var method : type.methods) if (method.name.equals("findRespawnPositionAndUseSpawnBlock") && method.desc.equals("(ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/level/portal/DimensionTransition;"))
                for (var node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.ARETURN) {
                    var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD,0)); out.add(new VarInsnNode(Opcodes.ALOAD,2));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,LIFECYCLE,"respawn","(Lnet/minecraft/world/level/portal/DimensionTransition;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/level/portal/DimensionTransition;",false));
                    method.instructions.insertBefore(node,out); anchors++;
                }
            require(anchors == 3,"respawn fallback returns"); System.setProperty("jed.dimension.coremod.respawn_fallback","verified"); return type;
        }
    }
}
