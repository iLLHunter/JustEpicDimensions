package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Biome registries can grow while old global-palette chunk data remains alive or in flight. */
final class JEDPaletteTransforms extends ArbitrageCoremod.Transformer {
    static final String OWNER="net/minecraft/world/level/chunk/PalettedContainer", DATA=OWNER+"$Data", STRATEGY=OWNER+"$Strategy", CONFIG=OWNER+"$Configuration";
    String target(){return OWNER.replace('/','.');}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){
        int reads=0,mutations=0;
        for(var m:c.methods){
            if(m.name.equals("read")&&m.desc.equals("(Lnet/minecraft/network/FriendlyByteBuf;)V"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals(OWNER)&&call.name.equals("createOrReuseData")){call.name="jed$wireData";reads++;}
            if((m.name.equals("set")&&m.desc.equals("(ILjava/lang/Object;)V"))||(m.name.equals("getAndSet")&&m.desc.equals("(ILjava/lang/Object;)Ljava/lang/Object;"))){m.instructions.insert(normalize());mutations++;}
        }
        // No write-time repack: packets already constructed before a registry update retain their original width.
        var wire=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNTHETIC,"jed$wireData","(L"+DATA+";I)L"+DATA+";",null,null);
        var nativePath=new LabelNode();strategy(wire.instructions);wire.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE,nativePath));
        wire.instructions.add(new VarInsnNode(Opcodes.ILOAD,2));wire.instructions.add(new InsnNode(Opcodes.ICONST_3));wire.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLE,nativePath));
        wire.instructions.add(new TypeInsnNode(Opcodes.NEW,CONFIG));wire.instructions.add(new InsnNode(Opcodes.DUP));
        wire.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,STRATEGY,"GLOBAL_PALETTE_FACTORY","Lnet/minecraft/world/level/chunk/Palette$Factory;"));wire.instructions.add(new VarInsnNode(Opcodes.ILOAD,2));
        wire.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,CONFIG,"<init>","(Lnet/minecraft/world/level/chunk/Palette$Factory;I)V",false));
        wire.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wire.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"registry","Lnet/minecraft/core/IdMap;"));wire.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
        wire.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wire.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"strategy","L"+STRATEGY+";"));wire.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,STRATEGY,"size","()I",false));
        wire.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,CONFIG,"createData","(Lnet/minecraft/core/IdMap;Lnet/minecraft/world/level/chunk/PaletteResize;I)L"+DATA+";",false));wire.instructions.add(new InsnNode(Opcodes.ARETURN));
        wire.instructions.add(nativePath);wire.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));wire.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));wire.instructions.add(new VarInsnNode(Opcodes.ILOAD,2));wire.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,OWNER,"createOrReuseData","(L"+DATA+";I)L"+DATA+";",false));wire.instructions.add(new InsnNode(Opcodes.ARETURN));wire.maxStack=4;wire.maxLocals=3;c.methods.add(wire);
        var normalize=new MethodNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNTHETIC,"jed$growBiomePalette","()V",null,null);var done=new LabelNode();strategy(normalize.instructions);normalize.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE,done));
        data(normalize.instructions);normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,DATA,"palette","()Lnet/minecraft/world/level/chunk/Palette;",false));normalize.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF,"net/minecraft/world/level/chunk/GlobalPalette"));normalize.instructions.add(new JumpInsnNode(Opcodes.IFEQ,done));
        normalize.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));normalize.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"registry","Lnet/minecraft/core/IdMap;"));normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"net/minecraft/core/IdMap","size","()I",true));normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"net/minecraft/util/Mth","ceillog2","(I)I",false));normalize.instructions.add(new VarInsnNode(Opcodes.ISTORE,1));
        data(normalize.instructions);normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,DATA,"storage","()Lnet/minecraft/util/BitStorage;",false));normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"net/minecraft/util/BitStorage","getBits","()I",true));normalize.instructions.add(new VarInsnNode(Opcodes.ILOAD,1));normalize.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ,done));
        normalize.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));normalize.instructions.add(new VarInsnNode(Opcodes.ILOAD,1));data(normalize.instructions);normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,DATA,"palette","()Lnet/minecraft/world/level/chunk/Palette;",false));
        data(normalize.instructions);normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,DATA,"storage","()Lnet/minecraft/util/BitStorage;",false));normalize.instructions.add(new InsnNode(Opcodes.ICONST_0));normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"net/minecraft/util/BitStorage","get","(I)I",true));normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,"net/minecraft/world/level/chunk/Palette","valueFor","(I)Ljava/lang/Object;",true));
        normalize.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,OWNER,"onResize","(ILjava/lang/Object;)I",false));normalize.instructions.add(new InsnNode(Opcodes.POP));normalize.instructions.add(done);normalize.instructions.add(new InsnNode(Opcodes.RETURN));normalize.maxStack=6;normalize.maxLocals=2;c.methods.add(normalize);
        require(reads==1&&mutations==2,"native biome palette growth "+reads+"/"+mutations);System.setProperty("jed.dimension.coremod.palette","verified");return c;
    }
    static InsnList normalize(){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,OWNER,"jed$growBiomePalette","()V",false));return out;}
    static void data(InsnList out){out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"data","L"+DATA+";"));}
    static void strategy(InsnList out){out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,OWNER,"strategy","L"+STRATEGY+";"));out.add(new FieldInsnNode(Opcodes.GETSTATIC,STRATEGY,"SECTION_BIOMES","L"+STRATEGY+";"));}
}
