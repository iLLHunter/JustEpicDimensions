package justepicdimensions.notification;

import java.util.*;
import justepicdimensions.api.lifecycle.JEDNotificationRegistry;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.*;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

/** Metadata is an enforceable API declaration; it is not a proof of arbitrary addon side effects. */
public final class JEDNotificationCompliance {
    public record Declaration(String modId,int protocol,Set<ResourceLocation> consumers){public Declaration{consumers=Set.copyOf(consumers);}}
    public static List<String> errors(Collection<Declaration> declarations,Map<ResourceLocation,JEDNotificationRegistry.ConsumerType> registered){
        var errors=new ArrayList<String>();var declared=new HashMap<ResourceLocation,Declaration>();
        for(var mod:declarations){
            if(mod.protocol()!=JEDNotificationRegistry.PROTOCOL)errors.add(mod.modId()+": unsupported notification protocol "+mod.protocol()+"; required "+JEDNotificationRegistry.PROTOCOL);
            if(mod.consumers().isEmpty())errors.add(mod.modId()+": declared notifications without consumer IDs");
            for(var id:mod.consumers()){
                if(!id.getNamespace().equals(mod.modId()))errors.add(mod.modId()+": consumer belongs to another namespace: "+id);
                if(declared.putIfAbsent(id,mod)!=null)errors.add(mod.modId()+": duplicate declared consumer "+id);
                if(!registered.containsKey(id))errors.add(mod.modId()+": missing acknowledged duplicate-safe consumer registration "+id);
            }
        }
        for(var type:registered.values()){
            if(!declared.containsKey(type.id()))errors.add(type.id().getNamespace()+": notification consumer is not declared in mod metadata: "+type.id());
            if(type.protocol()!=JEDNotificationRegistry.PROTOCOL)errors.add(type.id()+": unsupported registered protocol "+type.protocol());
            if(type.contract()!=JEDNotificationRegistry.Contract.DURABLE_DUPLICATE_SAFE_ACKNOWLEDGEMENTS)errors.add(type.id()+": duplicate-safe acknowledgements are required");
        }
        return List.copyOf(errors);
    }
    public static void loadComplete(FMLLoadCompleteEvent event){event.enqueueWork(()->{
        var declarations=new ArrayList<Declaration>();var issues=new ArrayList<ModLoadingIssue>();
        for(var mod:ModList.get().getMods())try{
            var properties=mod.getModProperties();var protocol=properties.get("jedNotificationProtocol");var consumers=properties.get("jedNotificationConsumers");
            if(protocol==null&&consumers==null)continue;
            if(!(protocol instanceof Number number)||number.intValue()!=number.doubleValue()||!(consumers instanceof List<?> list))throw new IllegalArgumentException("jedNotificationProtocol must be an integer and jedNotificationConsumers an array");
            var ids=new HashSet<ResourceLocation>();for(var value:list){if(!(value instanceof String text)||!ids.add(ResourceLocation.parse(text)))throw new IllegalArgumentException("consumer IDs must be unique namespaced strings");}
            declarations.add(new Declaration(mod.getModId(),number.intValue(),ids));
        }catch(RuntimeException error){issues.add(ModLoadingIssue.error("justepicdimensions.notifications.noncompliant",mod.getModId(),error.getMessage()).withAffectedMod(mod).withCause(error));}
        for(var error:errors(declarations,JEDNotificationRegistry.consumers())){
            com.mojang.logging.LogUtils.getLogger().error("JED rejected notification addon: {}",error);
            issues.add(ModLoadingIssue.error("justepicdimensions.notifications.noncompliant",error.split(":",2)[0],error));
        }
        JEDNotificationRegistry.freeze();
        if(!issues.isEmpty())throw new ModLoadingException(issues);
    });}
    private JEDNotificationCompliance(){}
}
