package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Only observes team membership removal. No claiming/loading code or state is targeted. */
final class JEDTeamDepartureTransforms extends ArbitrageCoremod.Transformer {
    private final String name;
    JEDTeamDepartureTransforms(String name){this.name=name;}
    static List<JEDTeamDepartureTransforms> all(){return List.of(new JEDTeamDepartureTransforms("net.minecraft.world.scores.Scoreboard"),new JEDTeamDepartureTransforms("xaero.pac.common.server.parties.party.ServerParty"));}
    String target(){return name;}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){int hooks=0;
        for(var method:type.methods){
            boolean nativeTeam=name.endsWith("Scoreboard");
            boolean match=nativeTeam?method.name.equals("removePlayerFromTeam")&&method.desc.equals("(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V"):method.name.equals("removeMember")&&method.desc.equals("(Ljava/util/UUID;)Lxaero/pac/common/parties/party/member/PartyMember;");
            if(!match)continue;
            for(var node:method.instructions.toArray())if(node.getOpcode()==(nativeTeam?Opcodes.RETURN:Opcodes.ARETURN)){
                var code=new InsnList();if(nativeTeam){for(int i=0;i<3;i++)code.add(new VarInsnNode(Opcodes.ALOAD,i));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/teams/JEDTeamDepartures","nativeLeft","(Lnet/minecraft/world/scores/Scoreboard;Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",false));}
                else{code.add(new InsnNode(Opcodes.DUP));code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/teams/JEDTeamDepartures","xaeroLeft","(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/UUID;)V",false));}
                method.instructions.insertBefore(node,code);method.maxStack+=3;hooks++;
            }
        }
        require(hooks>0,"team departure observer anchors: "+name);System.setProperty("jed.teams.coremod."+name,"verified");return type;
    }
}
