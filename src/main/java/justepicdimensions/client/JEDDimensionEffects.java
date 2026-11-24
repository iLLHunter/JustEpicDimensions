package justepicdimensions.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import justepicdimensions.render.*;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.*;

/** Per-level decorator. Shared native/other-mod effects are never mutated or closed. */
public final class JEDDimensionEffects extends DimensionSpecialEffects implements AutoCloseable {
    private final DimensionSpecialEffects base;
    private final JEDRenderDefinition policy;
    private final ResourceLocation dimension;
    private final JEDRendererFactoryRegistry.Resolved sky,clouds,weather;
    public JEDDimensionEffects(DimensionSpecialEffects base,ResourceLocation dimension,JEDRenderDefinition policy){
        super(base.getCloudHeight(),base.hasGround(),base.skyType(),base.forceBrightLightmap(),base.constantAmbientLight());
        this.base=base;this.policy=policy;this.dimension=dimension;
        sky=JEDRendererFactoryRegistry.resolve(dimension,JEDRendererFactoryRegistry.Channel.SKY,policy.sky().renderer());
        clouds=JEDRendererFactoryRegistry.resolve(dimension,JEDRendererFactoryRegistry.Channel.CLOUDS,policy.clouds().renderer());
        weather=JEDRendererFactoryRegistry.resolve(dimension,JEDRendererFactoryRegistry.Channel.WEATHER,policy.weather());
    }
    @Override public float getCloudHeight(){if(Boolean.FALSE.equals(policy.clouds().visible()))return Float.NaN;if(policy.clouds().height()!=null)return policy.clouds().height().floatValue();float original=base.getCloudHeight();return Boolean.TRUE.equals(policy.clouds().visible())&&Float.isNaN(original)?192:original;}
    @Override public SkyType skyType(){return switch(policy.sky().mode()){case OVERWORLD->SkyType.NORMAL;case END->SkyType.END;case NONE->SkyType.NONE;case INHERIT->base.skyType();};}
    @Override public boolean hasGround(){return switch(policy.sky().mode()){case OVERWORLD->true;case END,NONE->false;case INHERIT->base.hasGround();};}
    @Override public boolean forceBrightLightmap(){return base.forceBrightLightmap();}
    @Override public boolean constantAmbientLight(){return base.constantAmbientLight();}
    @Override public boolean isFoggyAt(int x,int z){return base.isFoggyAt(x,z);}
    @Override public Vec3 getBrightnessDependentFogColor(Vec3 color,float brightness){var f=policy.fog();if(f.color()!=null&&!f.fixed()&&JEDClientRenderRuntime.fogAllowed(f)){var rgb=JEDRenderMath.color(f.color());return new Vec3(rgb.red(),rgb.green(),rgb.blue()).scale(brightness);}return base.getBrightnessDependentFogColor(color,brightness);}
    @Override public float[] getSunriseColor(float angle,float partial){if(Boolean.FALSE.equals(policy.sky().sunrise()))return null;return switch(policy.sky().mode()){case END,NONE->null;case OVERWORLD->super.getSunriseColor(angle,partial);case INHERIT->base.getSunriseColor(angle,partial);};}
    @Override public boolean renderSky(ClientLevel level,int ticks,float partial,Matrix4f model,Camera camera,Matrix4f projection,boolean foggy,Runnable setupFog){
        if(sky.hidden()||policy.sky().mode()==JEDRenderDefinition.SkyMode.NONE)return true;
        if(sky.renderer()!=null)return sky.renderer().renderSky(level,ticks,partial,model,camera,projection,foggy,setupFog);
        return !sky.selected()&&policy.sky().mode()==JEDRenderDefinition.SkyMode.INHERIT&&base.renderSky(level,ticks,partial,model,camera,projection,foggy,setupFog);
    }
    @Override public boolean renderClouds(ClientLevel level,int ticks,float partial,PoseStack pose,double x,double y,double z,Matrix4f model,Matrix4f projection){
        if(clouds.hidden()||Boolean.FALSE.equals(policy.clouds().visible()))return true;
        if(clouds.renderer()!=null)return clouds.renderer().renderClouds(level,ticks,partial,pose,x,y,z,model,projection);
        return !clouds.selected()&&base.renderClouds(level,ticks,partial,pose,x,y,z,model,projection);
    }
    @Override public boolean renderSnowAndRain(ClientLevel level,int ticks,float partial,LightTexture light,double x,double y,double z){
        if(weather.hidden()||!policy.weatherVisible())return true;
        if(weather.renderer()!=null)return weather.renderer().renderSnowAndRain(level,ticks,partial,light,x,y,z);
        return !weather.selected()&&base.renderSnowAndRain(level,ticks,partial,light,x,y,z);
    }
    @Override public boolean tickRain(ClientLevel level,int ticks,Camera camera){if(weather.renderer()!=null)return weather.renderer().tickRain(level,ticks,camera);return !weather.selected()&&base.tickRain(level,ticks,camera);}
    @Override public void adjustLightmapColors(ClientLevel level,float partial,float darken,float flicker,float light,int x,int y,Vector3f colors){base.adjustLightmapColors(level,partial,darken,flicker,light,x,y,colors);}
    @Override public void close(){var closed=Collections.newSetFromMap(new IdentityHashMap<DimensionSpecialEffects,Boolean>());for(var selected:List.of(sky,clouds,weather))if(selected.renderer()!=null&&closed.add(selected.renderer())&&selected.renderer() instanceof AutoCloseable owned)try{owned.close();}catch(Exception error){JEDClientRenderRuntime.warn(dimension,"renderer-close","Could not close renderer resources: "+error.getMessage(),justepicdimensions.JEDText.tr("render.close",justepicdimensions.JEDText.error(error)));}}
}
