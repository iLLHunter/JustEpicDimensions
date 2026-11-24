package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Frozen native registries gain an append-only, copy-on-write storage publication point.
 * All injected signatures use JDK types: MappedRegistry loads before mod runtime classes. */
final class JEDRegistryTransforms extends ArbitrageCoremod.Transformer {
    private static final String OWNER = "net/minecraft/core/MappedRegistry";
    private static final String[] FIELDS = {"byId", "toId", "byLocation", "byKey", "byValue", "registrationInfos", "registryLifecycle"};
    private static final String[] TYPES = {"Lit/unimi/dsi/fastutil/objects/ObjectList;", "Lit/unimi/dsi/fastutil/objects/Reference2IntMap;",
        "Ljava/util/Map;", "Ljava/util/Map;", "Ljava/util/Map;", "Ljava/util/Map;", "Lcom/mojang/serialization/Lifecycle;"};
    String target() { return OWNER.replace('/', '.'); }
    public ClassNode transform(ClassNode c, ITransformerVotingContext context) {
        require(c.fields.stream().noneMatch(f -> f.name.startsWith("jed$")), "registry bridge already present");
        int reads = 0;
        for (var m : c.methods) for (var n : m.instructions.toArray()) if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && f.owner.equals(OWNER)) {
            int i = Arrays.asList(FIELDS).indexOf(f.name);
            if (i >= 0) { require(f.desc.equals(TYPES[i]), f.name); m.instructions.set(f, new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, "jed$read$" + f.name, "()" + f.desc, false)); reads++; }
        }
        require(reads > 40, "native registry storage reads " + reads);
        c.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC, "jed$state", "[Ljava/lang/Object;", null, null));
        for (int i = 0; i < FIELDS.length; i++) {
            final int index = i;
            require(c.fields.stream().anyMatch(f -> f.name.equals(FIELDS[index]) && f.desc.equals(TYPES[index])), FIELDS[i]);
            var m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "jed$read$" + FIELDS[i], "()" + TYPES[i], null, null);
            var original = new LabelNode();
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "jed$state", "[Ljava/lang/Object;"));
            m.instructions.add(new InsnNode(Opcodes.DUP)); m.instructions.add(new JumpInsnNode(Opcodes.IFNULL, original));
            m.instructions.add(new IntInsnNode(Opcodes.BIPUSH, i)); m.instructions.add(new InsnNode(Opcodes.AALOAD));
            m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, TYPES[i].substring(1, TYPES[i].length() - 1))); m.instructions.add(new InsnNode(Opcodes.ARETURN));
            m.instructions.add(original); m.instructions.add(new InsnNode(Opcodes.POP)); m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, FIELDS[i], TYPES[i])); m.instructions.add(new InsnNode(Opcodes.ARETURN));
            m.maxStack = 2; m.maxLocals = 1; c.methods.add(m);
        }
        var snapshot = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "jed$snapshot", "()[Ljava/lang/Object;", null, null);
        snapshot.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 11)); snapshot.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < 11; i++) {
            snapshot.instructions.add(new InsnNode(Opcodes.DUP)); snapshot.instructions.add(new IntInsnNode(Opcodes.BIPUSH, i)); snapshot.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (i < 7) snapshot.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, "jed$read$" + FIELDS[i], "()" + TYPES[i], false));
            else if (i == 7) { snapshot.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "frozen", "Z")); snapshot.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)); }
            else if (i == 8) snapshot.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "unregisteredIntrusiveHolders", "Ljava/util/Map;"));
            else snapshot.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, i == 9 ? "addCallbacks" : "bakeCallbacks", "Ljava/util/List;"));
            snapshot.instructions.add(new InsnNode(Opcodes.AASTORE));
        }
        snapshot.instructions.add(new InsnNode(Opcodes.ARETURN)); snapshot.maxStack = 4; snapshot.maxLocals = 1; c.methods.add(snapshot);
        var publish = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "jed$publish", "([Ljava/lang/Object;)V", null, null);
        publish.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); publish.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        publish.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, OWNER, "jed$state", "[Ljava/lang/Object;")); publish.instructions.add(new InsnNode(Opcodes.RETURN));
        publish.maxStack = 2; publish.maxLocals = 2; c.methods.add(publish);
        // A later unfreeze would permit in-place mutation of published collections. Reject it explicitly.
        int guards = 0;
        for (var m : c.methods) if (m.name.equals("unfreeze") && m.desc.equals("()V")) {
            var ok = new LabelNode(); var code = new InsnList(); code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "jed$state", "[Ljava/lang/Object;")); code.add(new JumpInsnNode(Opcodes.IFNULL, ok));
            code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException")); code.add(new InsnNode(Opcodes.DUP));
            code.add(new LdcInsnNode("JED runtime registry entries are append-only; unfreeze would invalidate live holders and packet IDs"));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)); code.add(new InsnNode(Opcodes.ATHROW)); code.add(ok);
            m.instructions.insert(code); m.maxStack = Math.max(m.maxStack, 3); guards++;
        }
        require(guards == 1, "registry write guard"); System.setProperty("jed.dimension.coremod.registry", "verified"); return c;
    }
}
