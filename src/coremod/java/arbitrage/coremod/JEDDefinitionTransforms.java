package arbitrage.coremod;

import cpw.mods.modlauncher.api.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Native startup/transaction boundaries for exactly Minecraft 1.21.1 / NeoForge 21.1.243. */
final class JEDDefinitionTransforms {
    static final String HOOK = "justepicdimensions/definition/JEDDefinitionHooks";
    static final String SERVER = "Lnet/minecraft/server/MinecraftServer;";
    static final String RESOURCES = "Lnet/minecraft/server/packs/resources/CloseableResourceManager;";
    static MethodInsnNode call(String name, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, name, descriptor, false); }
    static void require(boolean condition, String target) {
        if (!condition) throw new IllegalStateException("JED 1.21.1 / NeoForge 21.1.243 required definition transform anchor mismatch: " + target);
    }
    abstract static class Transform implements ITransformer<ClassNode> {
        abstract String target();
        public Set<Target<ClassNode>> targets() { return Set.of(Target.targetClass(target())); }
        public TargetType<ClassNode> getTargetType() { return TargetType.CLASS; }
        public TransformerVoteResult castVote(ITransformerVotingContext context) { return TransformerVoteResult.YES; }
    }

    static final class Startup extends Transform {
        String target() { return "net.minecraft.server.WorldLoader$PackConfig"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0;
            for (var method : type.methods) if (method.name.equals("createResourceManager") && method.desc.equals("()Lcom/mojang/datafixers/util/Pair;"))
                for (var node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.ARETURN) {
                    var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    out.add(call("startup", "(Lcom/mojang/datafixers/util/Pair;Lnet/minecraft/server/WorldLoader$PackConfig;)Lcom/mojang/datafixers/util/Pair;"));
                    method.instructions.insertBefore(node, out); anchors++;
                }
            require(anchors == 1, target()); System.setProperty("jed.definition.coremod.startup", "verified"); return type;
        }
    }

    static final class RegistryImage extends Transform {
        String target() { return "net.minecraft.server.WorldLoader"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0, templates = 0;
            for (var method : type.methods) if (method.name.equals("load") && (method.access & Opcodes.ACC_STATIC) != 0) {
                int resourceLocal = -1;
                for (var node : method.instructions.toArray()) {
                    if(node instanceof MethodInsnNode invoke&&invoke.owner.equals("net/minecraft/server/WorldLoader$WorldDataSupplier")&&invoke.name.equals("get")){
                        invoke.setOpcode(Opcodes.INVOKESTATIC);invoke.owner="justepicdimensions/structure/JEDTemplateManager";invoke.name="worldData";
                        invoke.desc="(Lnet/minecraft/server/WorldLoader$WorldDataSupplier;Lnet/minecraft/server/WorldLoader$DataLoadContext;)Lnet/minecraft/server/WorldLoader$DataLoadOutput;";invoke.itf=false;templates++;
                    }
                    if (node instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                        && cast.desc.equals("net/minecraft/server/packs/resources/CloseableResourceManager")) {
                        var next = node.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext();
                        if (next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) resourceLocal = store.var;
                    }
                    if (node instanceof MethodInsnNode invoke && invoke.owner.equals("net/minecraft/core/LayeredRegistryAccess") && invoke.name.equals("replaceFrom")) {
                        require(resourceLocal >= 0, "startup resource manager local");
                        var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD, resourceLocal));
                        out.add(call("registryImage", "(Lnet/minecraft/core/LayeredRegistryAccess;" + RESOURCES + ")Lnet/minecraft/core/LayeredRegistryAccess;"));
                        method.instructions.insert(invoke, out); anchors++;
                    }
                    if (node instanceof MethodInsnNode invoke && invoke.owner.equals("net/minecraft/resources/RegistryDataLoader") && invoke.name.equals("load")) {
                        require(resourceLocal >= 0, "dimension resource manager local");
                        var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD, resourceLocal));
                        out.add(call("dimensionImage", "(Lnet/minecraft/core/RegistryAccess$Frozen;" + RESOURCES + ")Lnet/minecraft/core/RegistryAccess$Frozen;"));
                        method.instructions.insert(invoke,out);
                    }
                }
            }
            require(anchors == 1 && templates == 1, target());System.setProperty("jed.template.coremod.metadata","verified");System.setProperty("jed.definition.coremod.image", "verified");return type;
        }
    }

    static final class Repository extends Transform {
        String target() { return "net.minecraft.server.packs.repository.ServerPacksSource"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int anchors = 0;
            for (var method : type.methods) if (method.name.equals("createPackRepository") && method.desc.equals(
                "(Ljava/nio/file/Path;Lnet/minecraft/world/level/validation/DirectoryValidator;)Lnet/minecraft/server/packs/repository/PackRepository;")) {
                for (var node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.ARETURN) {
                    var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    out.add(call("repository", "(Lnet/minecraft/server/packs/repository/PackRepository;Ljava/nio/file/Path;)Lnet/minecraft/server/packs/repository/PackRepository;"));
                    method.instructions.insertBefore(node, out); anchors++;
                }
            }
            require(anchors == 1, target()); System.setProperty("jed.definition.coremod.repository", "verified"); return type;
        }
    }

    static final class Reload extends Transform {
        String target() { return "net.minecraft.server.MinecraftServer"; }
        public ClassNode transform(ClassNode type, ITransformerVotingContext context) {
            int resources = 0, methods = 0, returns = 0;
            for (var method : type.methods) {
                for (var node : method.instructions.toArray()) if (node instanceof MethodInsnNode invoke
                    && invoke.owner.equals("net/minecraft/server/packs/resources/MultiPackResourceManager") && invoke.name.equals("<init>")) {
                    require((method.access & Opcodes.ACC_STATIC) == 0, "reload resource lambda must capture server");
                    var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    out.add(call("reloadResources", "(" + RESOURCES + SERVER + ")" + RESOURCES));
                    method.instructions.insert(invoke, out); resources++;
                }
                if (method.name.equals("reloadResources") && method.desc.equals("(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;")) {
                    methods++;
                    var start = new LabelNode(); var end = new LabelNode(); var failure = new LabelNode();
                    var begin = new InsnList(); begin.add(new VarInsnNode(Opcodes.ALOAD, 0)); begin.add(call("beginReload", "(" + SERVER + ")V")); begin.add(start);
                    method.instructions.insert(begin);
                    for (var node : method.instructions.toArray()) if (node.getOpcode() == Opcodes.ARETURN) {
                        var out = new InsnList(); out.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        out.add(call("finishReload", "(Ljava/util/concurrent/CompletableFuture;" + SERVER + ")Ljava/util/concurrent/CompletableFuture;"));
                        method.instructions.insertBefore(node, out); returns++;
                    }
                    method.instructions.add(end); method.instructions.add(failure); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.add(call("failedReload", "(Ljava/lang/Throwable;" + SERVER + ")Ljava/lang/Throwable;")); method.instructions.add(new InsnNode(Opcodes.ATHROW));
                    method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failure, null));
                }
            }
            require(resources == 1 && methods == 1 && returns == 1, target() + " resources=" + resources + " methods=" + methods + " returns=" + returns);
            System.setProperty("jed.definition.coremod.reload", "verified"); return type;
        }
    }
    private JEDDefinitionTransforms() {}
}
