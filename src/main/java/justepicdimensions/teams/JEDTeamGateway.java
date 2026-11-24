package justepicdimensions.teams;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.definition.*;
import justepicdimensions.instance.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Independent policy-authorized sessions; public owner controls never open an operator builder session. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDTeamGateway {
    public static final UUID CHANNEL=new UUID(0,15);
    public static final String PREFIX="JEDTEAM1:";
    private static final Map<ServerPlayer,Session> SESSIONS=new IdentityHashMap<>();
    private static final class Session {
        final UUID token=UUID.randomUUID();final JEDBuilderWire.Assembly assembly=new JEDBuilderWire.Assembly();final JEDBuilderWire.RateLimit rate=new JEDBuilderWire.RateLimit();
        final String mode;final ResourceLocation source;String run="default";int sequence;CompletableFuture<JEDTeamSystems.Key> choice;JsonObject preview;UUID confirmation;
        Session(String mode,ResourceLocation source){this.mode=mode;this.source=source;}
    }
    public static void open(ServerPlayer player,String mode,ResourceLocation source){
        if((mode.equals("admin")||mode.equals("picker"))&&!player.hasPermissions(2))throw new JEDDefinitionException("Team authoring tools require operator level 2");
        if(!Set.of("admin","permissions","picker").contains(mode))throw new JEDDefinitionException("Unknown team screen");
        cancel(player);var session=new Session(mode,source);SESSIONS.put(player,session);send(player,session,0,snapshot(player,session));
    }
    public static CompletableFuture<JEDTeamSystems.Key> choose(ServerPlayer player,ResourceLocation source,String run){
        cancel(player);var session=new Session("chooser",source);session.choice=new CompletableFuture<>();session.run=run==null?"default":run;var choices=JEDInstanceController.ownerChoices(player,source,session.run);if(choices.isEmpty())return CompletableFuture.failedFuture(new JEDDefinitionException("No eligible owning groups"));
        SESSIONS.put(player,session);var value=snapshot(player,session);value.addProperty("run",run==null?"default":run);send(player,session,0,value);return session.choice;
    }
    private static void send(ServerPlayer player,Session session,int sequence,JsonObject json){json.addProperty("kind","team");json.addProperty("token",session.token.toString());json.addProperty("mode",session.mode);if(session.source!=null)json.addProperty("source",session.source.toString());for(var frame:JEDBuilderWire.split(CHANNEL,sequence,json.toString()))PacketDistributor.sendToPlayer(player,new JEDBuilderWire.Response(frame));}
    private static JsonObject row(JEDTeamSystems.Group group){var j=new JsonObject();j.addProperty("system",group.key().system());j.addProperty("id",group.key().id().toString());j.addProperty("name",group.name());j.addProperty("type",group.kind());if(group.owner()!=null)j.addProperty("owner",group.owner().toString());return j;}
    private static JsonObject snapshot(ServerPlayer player,Session session){
        var result=new JsonObject();var groups=new JsonArray();var errors=new JsonObject();
        if(session.mode.equals("chooser")){for(var group:JEDInstanceController.ownerChoices(player,session.source,session.run))groups.add(row(group));}
        else for(String system:JEDTeamSystems.SYSTEMS){if(!JEDTeamSystems.installed(system)){errors.addProperty(system,"Not installed");continue;}try{for(var group:JEDTeamSystems.groups(player.server,system))if(player.hasPermissions(2)||JEDTeamSystems.member(player.server,group,player.getUUID()))groups.add(row(group));}catch(RuntimeException error){errors.addProperty(system,String.valueOf(error.getMessage()));}}
        result.add("groups",groups);result.add("systems",errors);return result;
    }
    public static boolean receive(ServerPlayer player,JEDBuilderWire.Frame frame){
        boolean opening=frame.session().equals(CHANNEL);var session=SESSIONS.get(player);
        if(opening){if(frame.request()!=0||frame.index()!=0||frame.count()!=1||!frame.text().equals("open:picker"))return true;try{open(player,"picker",null);}catch(RuntimeException error){var rejected=new Session("error",null);var j=new JsonObject();j.addProperty("error",String.valueOf(error.getMessage()));if(justepicdimensions.network.JEDRejectionReplies.allow(player))send(player,rejected,0,j);}return true;}
        if(session==null||!session.token.equals(frame.session()))return frame.index()==0&&frame.text().startsWith(PREFIX);
        try{
            if(player.hasDisconnected()||frame.count()>8||frame.text().length()>JEDBuilderWire.CHUNK||!session.rate.allow(frame,System.nanoTime(),16)||frame.request()<=session.sequence){
                session.assembly.clear();if(justepicdimensions.network.JEDRejectionReplies.allow(player)){var j=new JsonObject();j.addProperty("error","Team request rejected: session, size, rate or sequence");send(player,session,frame.request(),j);}return true;
            }
            String text=session.assembly.accept(frame,System.nanoTime());if(text==null)return true;session.sequence=frame.request();
            if(!text.startsWith(PREFIX))throw new JEDDefinitionException("Invalid team request");var request=JEDBuilderWire.json(text.substring(PREFIX.length()));
            JEDJson.keys(request,Set.of("action","system","id","instance","operation","player","permission","level","add","confirmation","source"),"team request");String action=JEDJson.string(request.get("action"),"action");
            if(action.equals("cancel")){cancel(player);return true;}
            if(session.mode.equals("chooser")){
                if(!action.equals("choose"))throw new JEDDefinitionException("Choose an owning group or cancel");var selected=key(request);if(JEDInstanceController.ownerChoices(player,session.source,session.run).stream().noneMatch(g->g.key().equals(selected)))throw new JEDDefinitionException("That group is no longer eligible");
                SESSIONS.remove(player);var reply=new JsonObject();reply.addProperty("close",true);send(player,session,frame.request(),reply);session.choice.complete(selected);return true;
            }
            if(action.equals("groups")){send(player,session,frame.request(),snapshot(player,session));return true;}
            if(action.equals("preview")){preview(player,session,request);var j=new JsonObject();j.add("preview",session.preview.deepCopy());j.addProperty("confirmation",session.confirmation.toString());send(player,session,frame.request(),j);return true;}
            if(action.equals("confirm")){confirm(player,session,request);var j=snapshot(player,session);j.addProperty("message","Ownership updated");send(player,session,frame.request(),j);return true;}
            if(session.mode.equals("picker"))throw new JEDDefinitionException("The group picker is read-only");
            var key=key(request);ResourceLocation source=session.source==null?ResourceLocation.parse(JEDJson.string(request.get("source"),"source")):session.source;
            ResourceLocation instance=request.has("instance")&&!request.get("instance").getAsString().isBlank()?ResourceLocation.parse(request.get("instance").getAsString()):null;
            if(!Set.of("view","set","grant","native_manager").contains(action))throw new JEDDefinitionException("Unknown team action");
            if(action.equals("view")){var group=JEDTeamSystems.group(player.server,key);if(!player.hasPermissions(2)&&!JEDTeamPermissions.isOwner(player.server,group,player.getUUID()))throw new JEDDefinitionException("Only the owning group's owner may view these settings");}
            else{
                JEDInstanceController.requireDelegation(player,source,key,instance);
                switch(action){
                    case "set"->JEDTeamPermissions.set(player.server,key,instance,JEDJson.string(request.get("permission"),"permission"),JEDJson.string(request.get("level"),"level"));
                    case "grant"->{var policy=JEDInstanceController.sourcePolicy(player.server,source);String permission=JEDJson.string(request.get("permission"),"permission");if(!JEDTeamPermissions.ACTIONS.contains(permission))throw new JEDDefinitionException("Unknown permission");JEDTeamPermissions.grant(player.server,key,instance,permission,UUID.fromString(JEDJson.string(request.get("player"),"player")),request.get("add").getAsBoolean(),policy.permissions().get(permission).mode());}
                    case "native_manager"->{if(!key.system().equals("minecraft")||instance!=null)throw new JEDDefinitionException("Select native-team defaults to appoint JED managers");JEDTeamPermissions.nativeManager(player.server,key.id(),UUID.fromString(JEDJson.string(request.get("player"),"player")),request.get("add").getAsBoolean());}
                }
            }
            var j=new JsonObject();j.add("settings",JEDTeamPermissions.view(player.server,key,instance));j.addProperty("message",action.equals("view")?"Current delegation settings":"Delegation updated");send(player,session,frame.request(),j);
        }catch(RuntimeException error){var j=new JsonObject();j.addProperty("error",String.valueOf(error.getMessage()));send(player,session,frame.request(),j);}return true;
    }
    private static JEDTeamSystems.Key key(JsonObject request){return new JEDTeamSystems.Key(JEDJson.string(request.get("system"),"system"),UUID.fromString(JEDJson.string(request.get("id"),"id")));}
    private static void preview(ServerPlayer player,Session session,JsonObject request){
        if(!session.mode.equals("admin")||!player.hasPermissions(2))throw new JEDDefinitionException("Ownership tools require current operator level 2");var j=request.deepCopy();String operation=JEDJson.string(j.get("operation"),"operation");
        if(operation.equals("native_owner")){var key=key(j);if(!key.system().equals("minecraft"))throw new JEDDefinitionException("Select a native Minecraft team");var group=JEDTeamSystems.group(player.server,key);UUID target=UUID.fromString(JEDJson.string(j.get("player"),"player"));if(!JEDTeamSystems.member(player.server,group,target))throw new JEDDefinitionException("The replacement JED owner must be a current team member");UUID current=JEDTeamPermissions.nativeOwner(player.server,key.id());j.addProperty("previous",current==null?"unassigned":current.toString());j.addProperty("replacement",target.toString());}
        else if(operation.equals("repair_owner")){var record=JEDInstanceController.instance(player.server,ResourceLocation.parse(JEDJson.string(j.get("instance"),"instance")));var key=key(j);var group=JEDTeamSystems.group(player.server,key);if(record==null||!record.ownership().equals("team")||group==null||!JEDInstanceController.permittedOwner(player.server,record.source(),key))throw new JEDDefinitionException("Select a team-owned instance and a permitted replacement group");j.addProperty("previous",JEDInstanceController.teamKey(record).toString());j.addProperty("replacement",key+" / "+group.name());}
        else throw new JEDDefinitionException("Unknown ownership operation");session.preview=j;session.confirmation=UUID.randomUUID();
    }
    private static void confirm(ServerPlayer player,Session session,JsonObject request){
        if(!session.mode.equals("admin")||!player.hasPermissions(2)||session.preview==null||session.confirmation==null||!session.confirmation.toString().equals(JEDJson.string(request.get("confirmation"),"confirmation")))throw new JEDDefinitionException("Ownership preview expired");
        var j=session.preview;session.preview=null;session.confirmation=null;var key=key(j);
        if(j.get("operation").getAsString().equals("native_owner")){UUID current=JEDTeamPermissions.nativeOwner(player.server,key.id());if(!j.get("previous").getAsString().equals(current==null?"unassigned":current.toString()))throw new JEDDefinitionException("Owner changed; preview the assignment again");JEDTeamPermissions.assignNative(player.server,key.id(),UUID.fromString(j.get("player").getAsString()));}
        else{var id=ResourceLocation.parse(j.get("instance").getAsString());var current=JEDInstanceController.instance(player.server,id);if(current==null||!JEDInstanceController.teamKey(current).toString().equals(j.get("previous").getAsString()))throw new JEDDefinitionException("Owner changed; preview the repair again");JEDInstanceController.repairOwner(player.createCommandSourceStack(),id,key);}
    }
    public static void cancel(ServerPlayer player){var previous=SESSIONS.remove(player);if(previous!=null&&previous.choice!=null)previous.choice.completeExceptionally(new CancellationException("Owning-group selection cancelled"));}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer player)cancel(player);}
    public static void stopped(MinecraftServer server){for(var player:List.copyOf(SESSIONS.keySet()))if(player.server==server)cancel(player);}
    private JEDTeamGateway(){}
}
