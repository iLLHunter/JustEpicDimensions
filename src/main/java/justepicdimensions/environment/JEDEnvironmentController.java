package justepicdimensions.environment;

import java.util.*;
import com.mojang.serialization.JsonOps;
import justepicdimensions.definition.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Evaluate on actual entity ticks; no unloaded/offline damage or catch-up pulses. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDEnvironmentController {
    public static final String CLOCKS="jed_environment_clocks";
    public record Prepared(Map<ResourceLocation,JEDEnvironmentPolicy> dimensions){}
    private static volatile Map<MinecraftServer,Prepared> SERVERS=Map.of();
    private static final ThreadLocal<Set<DamageSource>> INDEPENDENT=ThreadLocal.withInitial(()->Collections.newSetFromMap(new IdentityHashMap<>()));
    public static Prepared prepare(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var policies=candidate.environmentPolicies(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()));var dimensions=new HashMap<ResourceLocation,JEDEnvironmentPolicy>();
        definitions.forEach((id,d)->{if(d.environment()!=null)dimensions.put(id,Objects.requireNonNull(policies.get(d.environment()),"Validated environmental policy disappeared"));});return new Prepared(Map.copyOf(dimensions));
    }
    public static synchronized void publish(MinecraftServer server,Prepared prepared){var next=new IdentityHashMap<>(SERVERS);next.put(server,prepared);SERVERS=Collections.unmodifiableMap(next);}
    public static JEDEnvironmentPolicy policy(ServerLevel level){var state=SERVERS.get(level.getServer());return state==null?null:state.dimensions().get(level.dimension().location());}
    @SubscribeEvent public static void tick(EntityTickEvent.Post event){if(event.getEntity() instanceof LivingEntity living&&living.level() instanceof ServerLevel)tick(living);}
    public static void tick(LivingEntity entity){
        if(!(entity.level() instanceof ServerLevel level)||!entity.isAlive()||entity.isRemoved()||justepicdimensions.travel.JEDTeleportService.pending(entity))return;
        if(entity instanceof ServerPlayer player&&!justepicdimensions.dimension.JEDPlayerReadiness.isReady(player))return;
        var policy=policy(level);var active=new HashSet<String>();var persistent=entity.getPersistentData();var clocks=persistent.getCompound(CLOCKS);var next=new CompoundTag();boolean selected=false;
        if(policy!=null)for(var rule:policy.rules()){
            if(entity.level()!=level||!entity.isAlive()||entity.isRemoved()||(selected&&policy.selection().equals("first")))break;
            if(!rule.targets().test(entity)||(entity instanceof ServerPlayer player&&!rule.modes().contains(player.gameMode.getGameModeForPlayer()))||!rule.when().test(entity))continue;
            var protection=rule.protection().test(entity);boolean effects=!rule.effects().isEmpty(),healing=rule.healing()>0,damage=rule.damage()!=null&&protection.remaining()>0;
            if(protection.immune()||(!effects&&!healing&&!damage))continue;
            selected=true;String key=rule.key(policy.id());active.add(key);var clock=clocks.getCompound(key);String signature=level.dimension().location()+"|"+rule.signature();
            int remaining=signature.equals(clock.getString("signature"))?clock.getInt("remaining"):rule.delay();
            if(remaining<=0){
                for(String action:rule.order()){
                    if(entity.level()!=level||!entity.isAlive()||entity.isRemoved())break;
                    switch(action){
                        case "effects"->{for(var effect:rule.effects())if(entity.level()==level&&entity.isAlive()&&!entity.isRemoved())JEDEnvironmentEffects.apply(entity,policy,rule,effect);}
                        case "healing"->{if(healing)entity.heal(rule.healing()*2);}
                        case "damage"->{if(damage){var source=new DamageSource(rule.damage().type());if(rule.damage().independent())independent(entity,source,rule.damage().hearts()*2*(float)protection.remaining());else entity.hurt(source,rule.damage().hearts()*2*(float)protection.remaining());}}
                    }
                }
                remaining=rule.interval();
            }
            var updated=new CompoundTag();updated.putString("signature",signature);updated.putInt("remaining",remaining-1);next.put(key,updated);
        }
        if(next.isEmpty())persistent.remove(CLOCKS);else persistent.put(CLOCKS,next);JEDEnvironmentEffects.cleanup(entity,active);
    }
    public static boolean independent(DamageSource source){var active=INDEPENDENT.get();boolean value=active.contains(source);if(active.isEmpty())INDEPENDENT.remove();return value;}
    public static boolean cooldown(DamageSource source,net.minecraft.tags.TagKey<net.minecraft.world.damagesource.DamageType> tag){return source.is(tag)||independent(source);}
    private static void independent(LivingEntity entity,DamageSource source,float amount){var set=INDEPENDENT.get();set.add(source);try{entity.hurt(source,amount);}finally{set.remove(source);if(set.isEmpty())INDEPENDENT.remove();}}
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent event){var next=new IdentityHashMap<>(SERVERS);next.remove(event.getServer());SERVERS=Collections.unmodifiableMap(next);}
    private JEDEnvironmentController(){}
}
