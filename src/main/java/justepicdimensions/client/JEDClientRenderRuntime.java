package justepicdimensions.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.serialization.JsonOps;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.render.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.*;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** Client-thread policy/asset changes; steady rendering uses cached objects and native rendering. */
@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDClientRenderRuntime {
    private static final Map<ClientLevel,JEDDimensionEffects> EFFECTS=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile ClientLevel active;
    private static final Map<ResourceLocation,String> SIGNATURES=new HashMap<>();
    private static final Map<ResourceLocation,Boolean> TEXTURES=new HashMap<>();
    private static final Set<String> WARNED=new HashSet<>();
    private static final ArrayDeque<Component> MESSAGES=new ArrayDeque<>();
    private static final Map<JEDRenderDefinition.Music,Music> MUSIC=new HashMap<>();
    private static Refresh refresh;
    private static long maxRefreshNanos,markedSections,tagRefreshes;
    private static final class Refresh {final ClientLevel level;final ArrayDeque<ChunkPos> chunks;int section;Refresh(ClientLevel level,ArrayDeque<ChunkPos> chunks){this.level=level;this.chunks=chunks;section=level.getMinSection();}}
    public static void receive(ResourceLocation id,JEDRenderDefinition policy){
        String signature=policy==null?"":policy.json().toString();if(signature.equals(SIGNATURES.getOrDefault(id,"")))return;
        var before=JEDRenderController.client(id);SIGNATURES.put(id,signature);JEDRenderController.client(id,policy);
        WARNED.removeIf(key->key.startsWith(id+"/"));MUSIC.clear();var mc=Minecraft.getInstance();var level=mc.level;
        if(level!=null&&level.dimension().location().equals(id)){
            retire(level);JEDBiomeColorController.bind(level,policy);level.updateSkyBrightness();JEDRenderController.skyEffects(level);
            String oldColors=before==null?"{}":before.json().get("biome_colors").toString(),newColors=policy==null?"{}":policy.json().get("biome_colors").toString();
            if(!oldColors.equals(newColors))queueRefresh(level);
        }
    }
    public static void bind(ClientLevel level){var previous=active;active=level;JEDRenderController.skyEffects(level);if(previous!=null&&previous!=level){retire(previous);refresh=null;}JEDBiomeColorController.bind(level,JEDRenderController.policy(level));level.updateSkyBrightness();level.effects();}
    public static void registriesChanged(){var level=Minecraft.getInstance().level;if(level!=null)JEDBiomeColorController.bind(level,JEDRenderController.policy(level));}
    @SubscribeEvent public static void tagsUpdated(TagsUpdatedEvent event){
        if(event.getUpdateCause()!=TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED)return;
        var registries=event.getRegistryAccess();var mc=Minecraft.getInstance();mc.execute(()->{
            var level=mc.level;
            if(level==null||level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)!=registries.registryOrThrow(net.minecraft.core.registries.Registries.BIOME))return;
            var policy=JEDRenderController.policy(level);
            if(policy==null||policy.biomeColors().values().stream().flatMap(Collection::stream).noneMatch(rule->rule.tag()!=null))return;
            JEDBiomeColorController.bind(level,policy);queueRefresh(level);tagRefreshes++;
        });
    }
    public static DimensionSpecialEffects effects(DimensionSpecialEffects original,ClientLevel level){var policy=JEDRenderController.policy(level);if(original==null||policy==null||level!=active)return original;var ready=EFFECTS.get(level);if(ready!=null)return ready;
        // Chunk workers also call effects() for native shading. They must never construct GPU renderers.
        if(!Minecraft.getInstance().isSameThread())return original;
        var created=new JEDDimensionEffects(original,level.dimension().location(),policy);EFFECTS.put(level,created);return created;
    }
    public static Vec3 skyBase(Vec3 original,ClientLevel level){var p=JEDRenderController.policy(level);if(p==null||p.sky().color()==null)return original;return vector(JEDRenderMath.skyBase(rgb(original),p.sky()));}
    public static float skyLight(float nativeValue,ClientLevel level){var p=JEDRenderController.policy(level);return p==null||p.sky().color()==null?nativeValue:(float)(nativeValue*p.sky().lightBlend()+1-p.sky().lightBlend());}
    public static Vec3 fixedSky(Vec3 original,ClientLevel level){var p=JEDRenderController.policy(level);return p!=null&&p.sky().color()!=null&&p.sky().fixed()?vector(JEDRenderMath.color(p.sky().color())):original;}
    public static Vec3 cloudColor(Vec3 original,ClientLevel level,float partial){var p=JEDRenderController.policy(level);return p==null||p.clouds().color()==null?original:vector(JEDRenderMath.cloud(p.clouds(),level.getTimeOfDay(partial),level.getRainLevel(partial),level.getThunderLevel(partial)));}
    public static float stars(float original,ClientLevel level){var p=JEDRenderController.policy(level);return p!=null&&!p.sky().stars()?0:original;}
    public static float sunBrightness(float original,ClientLevel level){var p=JEDRenderController.policy(level);return p!=null&&p.sky().brightness()!=null?p.sky().brightness().floatValue():original;}
    private static JEDRenderMath.RGB rgb(Vec3 v){return new JEDRenderMath.RGB(v.x,v.y,v.z);}
    private static Vec3 vector(JEDRenderMath.RGB v){return new Vec3(v.red(),v.green(),v.blue());}

    public static ResourceLocation texture(ResourceLocation dimension,ResourceLocation requested,ResourceLocation fallback){
        boolean present=TEXTURES.computeIfAbsent(requested,key->Minecraft.getInstance().getResourceManager().getResource(key).isPresent());if(present)return requested;
        warn(dimension,"texture-"+requested,"Texture "+requested+" is missing; using "+fallback,justepicdimensions.JEDText.tr("render.texture",requested,fallback));return fallback;
    }
    public static void warn(ResourceLocation dimension,String channel,String message){warn(dimension,channel,message,justepicdimensions.JEDText.fixed(message));}
    public static void warn(ResourceLocation dimension,String channel,String message,Component display){if(WARNED.add(dimension+"/"+channel)){String text="JED ["+dimension+"]: "+message;org.slf4j.LoggerFactory.getLogger(JEDClientRenderRuntime.class).warn(text);MESSAGES.add(justepicdimensions.JEDText.tr("render.warning",dimension,display));}}
    public static boolean fogAllowed(JEDRenderDefinition.Fog policy){var mc=Minecraft.getInstance();var camera=mc.gameRenderer.getMainCamera();
        if(camera.getFluidInCamera()!=FogType.NONE&&!policy.overrideFluids())return false;
        if(camera.getEntity() instanceof LivingEntity entity&&(entity.hasEffect(MobEffects.BLINDNESS)||entity.hasEffect(MobEffects.DARKNESS))&&!policy.overrideEffects())return false;
        return policy.overrideBoss()||!mc.gui.getBossOverlay().shouldCreateWorldFog();
    }
    @SubscribeEvent public static void fogColor(ViewportEvent.ComputeFogColor event){var mc=Minecraft.getInstance();if(mc.level==null)return;var p=JEDRenderController.policy(mc.level);if(p==null||p.fog().color()==null||!fogAllowed(p.fog()))return;
        // Ordinary air color was applied before native rain/vision adjustments in the effects decorator.
        boolean special=event.getCamera().getFluidInCamera()!=FogType.NONE||event.getCamera().getEntity() instanceof LivingEntity entity&&(entity.hasEffect(MobEffects.BLINDNESS)||entity.hasEffect(MobEffects.DARKNESS));
        if(!p.fog().fixed()&&!special)return;
        var c=JEDRenderMath.color(p.fog().color());if(!p.fog().fixed())c=c.scale(JEDRenderMath.daylight(mc.level.getTimeOfDay((float)event.getPartialTick())));event.setRed((float)c.red());event.setGreen((float)c.green());event.setBlue((float)c.blue());
    }
    @SubscribeEvent public static void fogDistance(ViewportEvent.RenderFog event){var mc=Minecraft.getInstance();if(mc.level==null)return;var p=JEDRenderController.policy(mc.level);if(p==null||!fogAllowed(p.fog()))return;var f=p.fog();if(f.near()==null&&f.far()==null&&f.shape()==null)return;
        float end=f.far()==null?event.getFarPlaneDistance():f.far().floatValue(),start=f.near()==null?event.getNearPlaneDistance():f.near().floatValue();if(start>=end){if(f.near()==null)start=Math.min(0,end-1);else end=start+1;}
        event.setNearPlaneDistance(start);event.setFarPlaneDistance(end);if(f.shape()!=null)event.setFogShape(f.shape().equals("sphere")?FogShape.SPHERE:FogShape.CYLINDER);event.setCanceled(true);
    }
    public static Music selectedMusic(Music original,JEDRenderDefinition.Music p,ResourceLocation dimension){
        if(p.mode()==JEDRenderDefinition.MusicMode.NATIVE)return original;if(p.respectSpecial()&&original!=null&&(original.getEvent().equals(Musics.END_BOSS.getEvent())||original.getEvent().equals(Musics.UNDER_WATER.getEvent())))return original;
        if(p.mode()==JEDRenderDefinition.MusicMode.SILENT)return null;
        var cached=MUSIC.get(p);if(cached!=null)return cached;
        Holder<SoundEvent> sound;
        if(p.mode()==JEDRenderDefinition.MusicMode.SOUND)sound=Holder.direct(SoundEvent.createVariableRangeEvent(p.sound()));
        else sound=switch(p.preset()){case "creative"->Musics.CREATIVE.getEvent();case "nether"->SoundEvents.MUSIC_BIOME_NETHER_WASTES;case "end"->Musics.END.getEvent();case "end_boss"->Musics.END_BOSS.getEvent();case "under_water"->Musics.UNDER_WATER.getEvent();case "menu"->Musics.MENU.getEvent();case "credits"->Musics.CREDITS.getEvent();default->Musics.GAME.getEvent();};
        if(Minecraft.getInstance().getSoundManager().getSoundEvent(sound.value().getLocation())==null){warn(dimension,"music-"+sound.value().getLocation(),"Music "+sound.value().getLocation()+" is missing; using native music",justepicdimensions.JEDText.tr("render.music",sound.value().getLocation()));return original;}
        var music=new Music(sound,p.minDelay(),p.maxDelay(),p.replace());MUSIC.put(p,music);return music;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) public static void music(SelectMusicEvent event){var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen!=null&&mc.screen.getBackgroundMusic()!=null)return;var p=JEDRenderController.policy(mc.level);
        if(p!=null&&p.music().mode()!=JEDRenderDefinition.MusicMode.NATIVE){event.overrideMusic(selectedMusic(event.getOriginalMusic(),p.music(),mc.level.dimension().location()));return;}
        var provider=justepicdimensions.provider.JEDProviderController.policy(mc.level);if(provider==null)return;
        event.setMusic(provider.music(event.getOriginalMusic(),mc.level,mc.player.blockPosition(),mc.gui.getBossOverlay().shouldPlayMusic()));
    }

    public static void colorOptionChanged(){var mc=Minecraft.getInstance();if(mc.level!=null){JEDBiomeColorController.bind(mc.level,JEDRenderController.policy(mc.level));queueRefresh(mc.level);}}
    private static void queueRefresh(ClientLevel level){level.clearTintCaches();var mc=Minecraft.getInstance();if(mc.player==null)return;var center=mc.player.chunkPosition();int radius=mc.options.getEffectiveRenderDistance();var positions=new ArrayList<ChunkPos>();
        for(int x=center.x-radius;x<=center.x+radius;x++)for(int z=center.z-radius;z<=center.z+radius;z++)if(level.getChunkSource().getChunk(x,z,false)!=null)positions.add(new ChunkPos(x,z));
        positions.sort(Comparator.comparingInt(p->Math.abs(p.x-center.x)+Math.abs(p.z-center.z)));refresh=new Refresh(level,new ArrayDeque<>(positions));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();if(mc.player!=null)while(!MESSAGES.isEmpty())mc.player.sendSystemMessage(MESSAGES.remove());
        if(refresh==null)return;if(refresh.level!=mc.level){refresh=null;return;}long start=System.nanoTime();int count=0;
        while(!refresh.chunks.isEmpty()&&count<64&&(count==0||System.nanoTime()-start<500_000)){
            var pos=refresh.chunks.peek();if(refresh.level.getChunkSource().getChunk(pos.x,pos.z,false)==null){refresh.chunks.remove();refresh.section=refresh.level.getMinSection();continue;}
            mc.levelRenderer.setSectionDirty(pos.x,refresh.section++,pos.z);count++;markedSections++;
            if(refresh.section>=refresh.level.getMaxSection()){refresh.chunks.remove();refresh.section=refresh.level.getMinSection();}
        }maxRefreshNanos=Math.max(maxRefreshNanos,System.nanoTime()-start);if(refresh.chunks.isEmpty())refresh=null;
    }
    public static Map<String,Long> diagnostics(){return Map.of("marked_sections",markedSections,"max_refresh_ns",maxRefreshNanos,"pending_chunks",refresh==null?0L:(long)refresh.chunks.size(),"effects_instances",(long)EFFECTS.size(),"warnings",(long)WARNED.size(),"tag_refreshes",tagRefreshes);}
    private static void retire(ClientLevel level){var effects=EFFECTS.remove(level);if(effects!=null)effects.close();}
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event){if(event.getLevel() instanceof ClientLevel level){if(active==level){active=null;JEDRenderController.skyEffects(null);}retire(level);if(refresh!=null&&refresh.level==level)refresh=null;}}
    public static void assetsChanged(){EFFECTS.values().forEach(JEDDimensionEffects::close);EFFECTS.clear();TEXTURES.clear();MUSIC.clear();WARNED.clear();var level=Minecraft.getInstance().level;if(level!=null){JEDBiomeColorController.bind(level,JEDRenderController.policy(level));queueRefresh(level);}}
    public static void clear(){active=null;JEDRenderController.skyEffects(null);EFFECTS.values().forEach(JEDDimensionEffects::close);EFFECTS.clear();SIGNATURES.clear();TEXTURES.clear();MUSIC.clear();WARNED.clear();MESSAGES.clear();refresh=null;maxRefreshNanos=markedSections=tagRefreshes=0;JEDBiomeColorController.clear();JEDRenderController.clearClient();}
    @EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void configReload(net.neoforged.fml.event.config.ModConfigEvent.Reloading event){
            if(event.getConfig().getSpec()==justepicdimensions.lighting.JEDLightingConfig.SPEC)Minecraft.getInstance().execute(JEDConfigClient::configReload);
        }
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{for(String type:List.of("net.minecraft.client.multiplayer.ClientLevel","net.minecraft.client.renderer.LevelRenderer")){
            try{Class.forName(type,false,JEDClientRenderRuntime.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
            if(!"verified".equals(System.getProperty("arbitrage.render.coremod."+type)))throw new IllegalStateException("Arbitrage required client rendering transform missing: "+type);
        }});}
        @SubscribeEvent public static void shaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event){
            try{event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(JustEpicDimensions.ID,"sky_darken"),com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX),JEDSkyRenderer::darkenShader);}
            catch(java.io.IOException|RuntimeException error){JEDSkyRenderer.darkenShader(null);org.slf4j.LoggerFactory.getLogger(JEDClientRenderRuntime.class).warn("JED sky_darken shader did not load; tinted sun and moon textures with soft edges keep a dark rim",error);}
        }
        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event){event.registerReloadListener((ResourceManagerReloadListener)manager->Minecraft.getInstance().execute(JEDClientRenderRuntime::assetsChanged));}
    }
    private JEDClientRenderRuntime(){}
}
