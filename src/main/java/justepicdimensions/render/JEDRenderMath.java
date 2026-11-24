package justepicdimensions.render;

/** Source JED sky/cloud formulas, independent of the renderer and its graphics state. */
public final class JEDRenderMath {
    public record RGB(double red,double green,double blue){public RGB scale(double f){return new RGB(red*f,green*f,blue*f);}}
    public static double daylight(double angle){return Math.max(0,Math.min(1,Math.cos(angle*Math.PI*2)*2+0.5));}
    public static RGB color(JEDRenderDefinition.Color color){return new RGB(color.red(),color.green(),color.blue());}
    public static RGB skyBase(RGB biome,JEDRenderDefinition.Sky sky){if(sky.color()==null)return biome;var c=color(sky.color());double blend=sky.biomeBlend();return new RGB((biome.red()*(1-blend)+blend)*c.red(),(biome.green()*(1-blend)+blend)*c.green(),(biome.blue()*(1-blend)+blend)*c.blue());}
    public static RGB sky(RGB biome,JEDRenderDefinition.Sky sky,double angle,double rain,double thunder,double flash){if(sky.color()==null)return biome;if(sky.fixed())return color(sky.color());var c=skyBase(biome,sky).scale(daylight(angle)*sky.lightBlend()+1-sky.lightBlend());c=grey(c,rain*0.75,0.6);c=grey(c,thunder*0.75,0.2);double f=Math.max(0,Math.min(1,flash))*0.45;return new RGB(c.red()*(1-f)+0.8*f,c.green()*(1-f)+0.8*f,c.blue()*(1-f)+f);}
    public static RGB cloud(JEDRenderDefinition.Clouds clouds,double angle,double rain,double thunder){var c=color(clouds.color());if(clouds.fixed())return c;c=grey(c,rain*0.95,0.6);double day=daylight(angle);c=new RGB(c.red()*(day*0.9+0.1),c.green()*(day*0.9+0.1),c.blue()*(day*0.85+0.15));return grey(c,thunder*0.95,0.2);}
    private static RGB grey(RGB c,double amount,double scale){double gray=(c.red()*0.3+c.green()*0.59+c.blue()*0.11)*scale;return new RGB(c.red()*(1-amount)+gray*amount,c.green()*(1-amount)+gray*amount,c.blue()*(1-amount)+gray*amount);}
    private JEDRenderMath(){}
}
