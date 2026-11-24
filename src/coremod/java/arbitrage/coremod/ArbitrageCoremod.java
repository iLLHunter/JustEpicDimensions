package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.*;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Version-pinned native call sites. Decisions and scanning live in ordinary runtime classes. */
public final class ArbitrageCoremod implements ICoreMod {
    public static final String ID="arbitrage";
    public ArbitrageCoremod(){System.setProperty("arbitrage.coremod.registered","verified");}
    static final String BRIDGE="justepicdimensions/lighting/JEDClientLightHooks";
    static final String STORAGE="net/minecraft/world/level/lighting/LayerLightSectionStorage";
    static final String LAYER="Lnet/minecraft/world/level/LightLayer;";
    static final String DATA="Lnet/minecraft/world/level/chunk/DataLayer;";
    /** Every transformer is labelled so stack traces, failures and crash reports name Arbitrage and Just Epic Dimensions; see {@link ArbitrageReport}. */
    public Iterable<? extends ITransformer<?>> getTransformers(){var raw=transformers();var labelled=new ArrayList<ITransformer<?>>(raw.size());for(var transform:raw)labelled.add(ArbitrageReport.label(transform));ArbitrageReport.registered(labelled.size());return labelled;}
    /** The unlabelled transformers, for contracts that select them by type. */
    List<ITransformer<?>> transformers(){var transforms=new ArrayList<ITransformer<?>>(List.of(new Engine(),new Storage(),new Packets(),new JEDBrightnessTransforms(),
        new JEDDefinitionTransforms.Startup(),new JEDDefinitionTransforms.RegistryImage(),new JEDDefinitionTransforms.Repository(),new JEDDefinitionTransforms.Reload(),new JEDReloadCommandTransform(),new JEDSodiumColorTransform(),new JEDTemplateTransforms(),new JEDClientTemplateTransforms(),
        new JEDRegistryTransforms(),new JEDDimensionTransforms.Server(),new JEDDimensionTransforms.Player(),new JEDDimensionTransforms.Respawn(),new JEDDimensionTransforms.ClientCommand(),new JEDPaletteTransforms(),
        new JEDLifecycleTransforms.Bake(),new JEDLifecycleTransforms.Storage(),new JEDLifecycleTransforms.Shutdown(),new JEDLifecycleTransforms.UnloadOwnership(),new JEDLifecycleTransforms.Login(),new JEDLifecycleTransforms.Respawn()));transforms.addAll(JEDInstanceTransforms.all());transforms.addAll(JEDTeamDepartureTransforms.all());transforms.addAll(JEDWorldTransforms.all());transforms.addAll(JEDTimeTransforms.all());transforms.addAll(JEDWeatherTransforms.all());transforms.addAll(JEDMobSpawnTransforms.all());transforms.addAll(JEDSpawnTransforms.all());transforms.addAll(JEDSpawnChunksTransforms.all());transforms.addAll(JEDPlayerTransforms.all());transforms.add(new JEDCuriosSizingTransform());transforms.addAll(JEDPlayerNativeTransforms.all());transforms.add(new JEDPlayerAttributeTransforms());transforms.addAll(JEDGraveTransforms.all());transforms.addAll(JEDWorldgenTransforms.all());transforms.addAll(JEDEndTransforms.all());transforms.addAll(JEDProviderTransforms.all());transforms.addAll(JEDTravelTransforms.all());transforms.addAll(JEDStructureTransforms.all());transforms.addAll(JEDRenderTransforms.all());transforms.addAll(JEDPlayerCategoryTransforms.all());transforms.add(new JEDIdleActivityTransforms());transforms.add(new JEDAccessRespawnTransforms());transforms.addAll(JEDMobRulesTransforms.all());transforms.addAll(JEDEnvironmentTransforms.all());transforms.add(new JEDChunkyTransforms());transforms.add(new JEDChunkyTaskTransforms());transforms.add(new JEDChunkySchedulerTransforms());transforms.add(new JEDChunkyLoaderTransforms());return transforms;}
    abstract static class Transformer implements ITransformer<ClassNode> {
        abstract String target();
        public Set<Target<ClassNode>> targets(){return Set.of(Target.targetClass(target()));}
        public TargetType<ClassNode> getTargetType(){return TargetType.CLASS;}
        public TransformerVoteResult castVote(ITransformerVotingContext context){return TransformerVoteResult.YES;}
        static MethodInsnNode call(String name,String desc){return new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,name,desc,false);}
        static void require(boolean condition,String target){if(!condition)throw new IllegalStateException("JED 1.21.1 / NeoForge 21.1.243 required light transform anchor mismatch: "+target);}
    }
    static final class Engine extends Transformer {
        String target(){return "net.minecraft.world.level.lighting.LevelLightEngine";}
        public ClassNode transform(ClassNode c,ITransformerVotingContext context){
            int runs=0,returns=0,imports=0;
            for(var m:c.methods){
                if(m.name.equals("runLightUpdates")&&m.desc.equals("()I")){
                    var begin=new InsnList();begin.add(new VarInsnNode(Opcodes.ALOAD,0));
                    storage(begin,c.name,"blockEngine");storage(begin,c.name,"skyEngine");
                    begin.add(call("beforeDrain","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));m.instructions.insert(begin);m.maxStack=Math.max(m.maxStack,6);runs++;
                    for(var n:m.instructions.toArray())if(n.getOpcode()==Opcodes.IRETURN){var end=new InsnList();end.add(new VarInsnNode(Opcodes.ALOAD,0));end.add(call("afterDrain","(ILjava/lang/Object;)I"));m.instructions.insertBefore(n,end);returns++;}
                }
                if(m.name.equals("queueSectionData")&&m.desc.equals("("+LAYER+"Lnet/minecraft/core/SectionPos;"+DATA+")V")){
                    var begin=new InsnList();for(int i=0;i<4;i++)begin.add(new VarInsnNode(Opcodes.ALOAD,i));begin.add(call("queued","(Ljava/lang/Object;"+LAYER+"Lnet/minecraft/core/SectionPos;"+DATA+")V"));m.instructions.insert(begin);imports++;
                }
            }
            require(runs==1&&returns==1&&imports==1,target());System.setProperty("jed.light.coremod.engine","verified");return c;
        }
        static void storage(InsnList out,String owner,String field){
            var present=new LabelNode();var done=new LabelNode();
            out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,owner,field,"Lnet/minecraft/world/level/lighting/LightEngine;"));
            out.add(new InsnNode(Opcodes.DUP));out.add(new JumpInsnNode(Opcodes.IFNONNULL,present));out.add(new InsnNode(Opcodes.POP));out.add(new InsnNode(Opcodes.ACONST_NULL));out.add(new JumpInsnNode(Opcodes.GOTO,done));
            out.add(present);out.add(new FieldInsnNode(Opcodes.GETFIELD,"net/minecraft/world/level/lighting/LightEngine","storage","Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;"));out.add(done);
        }
    }
    static final class Storage extends Transformer {
        String target(){return "net.minecraft.world.level.lighting.LayerLightSectionStorage";}
        public ClassNode transform(ClassNode c,ITransformerVotingContext context){
            int consume=0,publish=0;
            for(var m:c.methods){
                if(m.name.equals("markNewInconsistencies")&&m.desc.equals("(Lnet/minecraft/world/level/lighting/LightEngine;)V")){
                    int key=-1,data=-1;
                    for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode invoke){
                        if(invoke.name.equals("getLongKey")&&invoke.desc.equals("()J")){
                            var next=real(invoke.getNext());if(next instanceof VarInsnNode v&&v.getOpcode()==Opcodes.LSTORE)key=v.var;
                        }
                        if(invoke.name.equals("getValue")&&invoke.owner.equals("it/unimi/dsi/fastutil/longs/Long2ObjectMap$Entry")){
                            var cast=real(invoke.getNext());var next=real(cast.getNext());
                            if(cast instanceof TypeInsnNode t&&t.desc.equals("net/minecraft/world/level/chunk/DataLayer")&&next instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE)data=v.var;
                        }
                        if(invoke.name.equals("remove")&&invoke.owner.equals("it/unimi/dsi/fastutil/objects/ObjectIterator")&&invoke.desc.equals("()V")){
                            require(key>=0&&data>=0,"queued layer key/data locals");
                            var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));source(out);layer(out);
                            out.add(new VarInsnNode(Opcodes.LLOAD,key));out.add(new VarInsnNode(Opcodes.ALOAD,data));
                            out.add(call("consumed","(Ljava/lang/Object;Ljava/lang/Object;"+LAYER+"J"+DATA+")V"));m.instructions.insert(invoke,out);consume++;
                        }
                    }
                }
                if(m.name.equals("swapSectionMap")&&m.desc.equals("()V"))for(var n:m.instructions.toArray()){
                    if(n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(STORAGE)&&field.name.equals("sectionsAffectedByLightUpdates")){
                        // The visible map is installed before external onLightUpdate callbacks can
                        // queue newer data/activation. Count publication at this exact cut point.
                        var receiver=realPrevious(n.getPrevious());require(receiver instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==0,"publication receiver");
                        var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));source(out);layer(out);
                        out.add(call("published","(Ljava/lang/Object;Ljava/lang/Object;"+LAYER+")V"));m.instructions.insertBefore(receiver,out);publish++;break;
                    }
                }
            }
            require(consume==1&&publish==1,target()+" consumed="+consume+" published="+publish);System.setProperty("jed.light.coremod.storage","verified");return c;
        }
        static AbstractInsnNode real(AbstractInsnNode n){while(n!=null&&n.getOpcode()<0)n=n.getNext();return n;}
        static AbstractInsnNode realPrevious(AbstractInsnNode n){while(n!=null&&n.getOpcode()<0)n=n.getPrevious();return n;}
        static void source(InsnList out){out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,STORAGE,"chunkSource","Lnet/minecraft/world/level/chunk/LightChunkGetter;"));}
        static void layer(InsnList out){out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,STORAGE,"layer",LAYER));}
    }
    static final class Packets extends Transformer {
        String target(){return "net.minecraft.client.multiplayer.ClientPacketListener";}
        public ClassNode transform(ClassNode c,ITransformerVotingContext context){
            int handlers=0;
            for(var m:c.methods)if(m.name.equals("handleLevelChunkWithLight")||m.name.equals("handleLightUpdatePacket")){
                String packet=m.name.equals("handleLevelChunkWithLight")?"ClientboundLevelChunkWithLightPacket":"ClientboundLightUpdatePacket";
                if(!m.desc.equals("(Lnet/minecraft/network/protocol/game/"+packet+";)V"))continue;
                int guards=0,tasks=0,returns=0;LabelNode start=new LabelNode(),end=new LabelNode(),failure=new LabelNode();
                for(var n:m.instructions.toArray()){
                    if(n instanceof MethodInsnNode invoke){
                        if(invoke.owner.equals("net/minecraft/network/protocol/PacketUtils")&&invoke.name.equals("ensureRunningOnSameThread")){
                            var begin=new InsnList();begin.add(start);begin.add(new VarInsnNode(Opcodes.ALOAD,0));
                            begin.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"level","Lnet/minecraft/client/multiplayer/ClientLevel;"));begin.add(new VarInsnNode(Opcodes.ALOAD,1));begin.add(call("packetBegin","(Ljava/lang/Object;Ljava/lang/Object;)V"));m.instructions.insert(invoke,begin);guards++;
                        }
                        if(invoke.owner.equals("net/minecraft/client/multiplayer/ClientLevel")&&invoke.name.equals("queueLightUpdate")&&invoke.desc.equals("(Ljava/lang/Runnable;)V")){
                            m.instructions.insertBefore(invoke,call("wrapTask","(Ljava/lang/Runnable;)Ljava/lang/Runnable;"));tasks++;
                        }
                    }
                    if(n.getOpcode()==Opcodes.RETURN){m.instructions.insertBefore(n,call("packetEnd","()V"));returns++;}
                }
                require(guards==1&&tasks==1&&returns==1,m.name);
                m.instructions.add(end);m.instructions.add(failure);m.instructions.add(call("packetFailed","()V"));m.instructions.add(new InsnNode(Opcodes.ATHROW));
                m.tryCatchBlocks.add(new TryCatchBlockNode(start,end,failure,null));handlers++;
            }
            require(handlers==2,target());System.setProperty("jed.light.coremod.packets","verified");return c;
        }
    }
}
