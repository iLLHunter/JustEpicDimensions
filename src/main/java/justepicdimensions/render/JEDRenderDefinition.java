package justepicdimensions.render;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Immutable common-side appearance data. No renderer, sound engine or client types are resolved here. */
public record JEDRenderDefinition(Sky sky, Clouds clouds, Fog fog, Selection weather, boolean weatherVisible,
                                  Map<String,List<BiomeRule>> biomeColors, Music music) {
    public enum SkyMode { INHERIT, OVERWORLD, END, NONE }
    public enum MusicMode { NATIVE, SILENT, PRESET, SOUND }
    public record Color(int rgb) {
        public double red(){return (rgb>>16&255)/255.0;}
        public double green(){return (rgb>>8&255)/255.0;}
        public double blue(){return (rgb&255)/255.0;}
        public String hex(){return String.format(Locale.ROOT,"#%06X",rgb);}
    }
    /** Settings are retained as canonical text so callers cannot mutate a published selection. */
    public record Selection(ResourceLocation id,String settingsJson) {
        public JsonObject settings(){return JsonParser.parseString(settingsJson).getAsJsonObject();}
        public JsonObject json(){var j=new JsonObject();j.addProperty("id",id.toString());j.add("settings",settings());return j;}
    }
    public record Celestial(boolean visible,double scale,Color color,ResourceLocation texture) {}
    public record Sky(SkyMode mode,Selection renderer,Color color,double biomeBlend,double lightBlend,boolean fixed,
                      Boolean sunrise,Celestial sun,Celestial moon,boolean stars,Double brightness,Double brightnessFactor) {}
    public record Clouds(Boolean visible,Double height,Color color,boolean fixed,Selection renderer) {}
    public record Fog(Color color,boolean fixed,Double near,Double far,String shape,boolean overrideFluids,
                      boolean overrideEffects,boolean overrideBoss) {}
    public record Music(MusicMode mode,String preset,ResourceLocation sound,int minDelay,int maxDelay,boolean replace,
                        boolean respectSpecial) {}
    public record BiomeRule(ResourceLocation biome,ResourceLocation tag,String regex,Pattern pattern,Color color) {}
    private static final Celestial BODY=new Celestial(true,1,new Color(0xffffff),null);
    public static final JEDRenderDefinition DEFAULT=new JEDRenderDefinition(
        new Sky(SkyMode.INHERIT,null,null,0,1,false,null,BODY,BODY,true,null,null),
        new Clouds(null,null,null,false,null),new Fog(null,false,null,null,null,false,false,false),null,true,Map.of(),
        new Music(MusicMode.NATIVE,null,null,12000,24000,true,false));
    public JEDRenderDefinition {var copy=new LinkedHashMap<String,List<BiomeRule>>();biomeColors.forEach((k,v)->copy.put(k,List.copyOf(v)));biomeColors=Collections.unmodifiableMap(copy);}

    public static JEDRenderDefinition decode(JsonElement value,String source){
        var root=JEDJson.object(value,source);JEDJson.keys(root,Set.of("sky","clouds","fog","weather","biome_colors","music"),source);
        var s=object(root,"sky",source);String sp=source+".sky";
        JEDJson.keys(s,Set.of("mode","renderer","color","biome_blend","light_blend","fixed","sunrise","sun","moon","stars","brightness","brightness_factor"),sp);
        var sky=new Sky(choice(s,"mode",SkyMode.class,SkyMode.INHERIT,sp),selection(s,"renderer",sp),color(s,"color",sp),number(s,"biome_blend",0,1,0,sp),number(s,"light_blend",0,1,1,sp),bool(s,"fixed",false,sp),nullableBool(s,"sunrise",sp),body(s,"sun",sp),body(s,"moon",sp),bool(s,"stars",true,sp),number(s,"brightness",0,1,sp),number(s,"brightness_factor",0,1,sp));
        var c=object(root,"clouds",source);String cp=source+".clouds";JEDJson.keys(c,Set.of("visible","height","color","fixed","renderer"),cp);
        // The End has no clouds: the End sky hides them unless clouds.visible says otherwise
        Boolean cloudsVisible=nullableBool(c,"visible",cp);if(cloudsVisible==null&&sky.mode()==SkyMode.END)cloudsVisible=false;
        var clouds=new Clouds(cloudsVisible,number(c,"height",-32768,32768,cp),color(c,"color",cp),bool(c,"fixed",false,cp),selection(c,"renderer",cp));
        var f=object(root,"fog",source);String fp=source+".fog";JEDJson.keys(f,Set.of("color","fixed","near","far","shape","override_fluids","override_effects","override_boss"),fp);
        String shape=string(f,"shape",null,fp);if(shape!=null&&!Set.of("sphere","cylinder").contains(shape))throw new JEDDefinitionException(fp+".shape: expected sphere or cylinder");
        var fog=new Fog(color(f,"color",fp),bool(f,"fixed",false,fp),number(f,"near",-32768,32768,fp),number(f,"far",0.001,32768,fp),shape,bool(f,"override_fluids",false,fp),bool(f,"override_effects",false,fp),bool(f,"override_boss",false,fp));
        if(fog.near()!=null&&fog.far()!=null&&fog.near()>=fog.far())throw new JEDDefinitionException(fp+": near must be smaller than far");
        var w=object(root,"weather",source);JEDJson.keys(w,Set.of("visible","renderer"),source+".weather");
        var palettes=new LinkedHashMap<String,List<BiomeRule>>();var b=object(root,"biome_colors",source);JEDJson.keys(b,Set.of("grass","foliage","water"),source+".biome_colors");
        for(var channel:b.keySet()){
            if(!b.get(channel).isJsonArray())throw new JEDDefinitionException(source+".biome_colors."+channel+": expected an array");
            var rules=new ArrayList<BiomeRule>();for(var element:b.getAsJsonArray(channel)){
                String rp=source+".biome_colors."+channel+"["+rules.size()+"]";var r=JEDJson.object(element,rp);JEDJson.keys(r,Set.of("biome","tag","regex","color"),rp);
                if((r.has("biome")?1:0)+(r.has("tag")?1:0)+(r.has("regex")?1:0)!=1)throw new JEDDefinitionException(rp+": choose exactly one biome, tag or regex selector");
                var tint=color(r,"color",rp);if(tint==null)throw new JEDDefinitionException(rp+": color is required");String regex=string(r,"regex",null,rp);Pattern pattern=null;
                if(regex!=null){if(regex.length()>1024)throw new JEDDefinitionException(rp+": regex is too long");try{pattern=Pattern.compile(regex);}catch(PatternSyntaxException error){throw new JEDDefinitionException(rp+": invalid biome regex",error);}}
                rules.add(new BiomeRule(id(r,"biome",rp),id(r,"tag",rp),regex,pattern,tint));
            }palettes.put(channel,List.copyOf(rules));
        }
        var m=object(root,"music",source);String mp=source+".music";JEDJson.keys(m,Set.of("mode","preset","sound","min_delay","max_delay","replace","respect_special"),mp);
        var mode=choice(m,"mode",MusicMode.class,MusicMode.NATIVE,mp);String preset=string(m,"preset",null,mp);var sound=id(m,"sound",mp);
        if(mode==MusicMode.PRESET&&(preset==null||!Set.of("game","creative","nether","end","end_boss","under_water","menu","credits").contains(preset)))throw new JEDDefinitionException(mp+": choose a known music preset");
        if(mode==MusicMode.SOUND&&sound==null)throw new JEDDefinitionException(mp+": sound mode requires a namespaced sound ID");
        if(mode!=MusicMode.PRESET&&preset!=null||mode!=MusicMode.SOUND&&sound!=null)throw new JEDDefinitionException(mp+": preset/sound must match the selected music mode");
        int baseMin=mode==MusicMode.PRESET?switch(preset){case "menu"->20;case "credits","end_boss"->0;case "end"->6000;default->12000;}:12000;
        int baseMax=mode==MusicMode.PRESET?switch(preset){case "menu"->600;case "credits","end_boss"->0;default->24000;}:24000;
        int min=integer(m,"min_delay",baseMin,mp),max=integer(m,"max_delay",baseMax,mp);if(min<0||max<min||max>72000)throw new JEDDefinitionException(mp+": delays must satisfy 0 <= min_delay <= max_delay <= 72000 ticks");
        boolean replace=mode!=MusicMode.PRESET||Set.of("menu","credits","end_boss","end").contains(preset);
        var music=new Music(mode,preset,sound,min,max,bool(m,"replace",replace,mp),bool(m,"respect_special",false,mp));
        return new JEDRenderDefinition(sky,clouds,fog,selection(w,"renderer",source+".weather"),bool(w,"visible",true,source+".weather"),palettes,music);
    }
    private static Celestial body(JsonObject root,String key,String source){var j=object(root,key,source);String p=source+"."+key;JEDJson.keys(j,Set.of("visible","scale","color","texture"),p);return new Celestial(bool(j,"visible",true,p),number(j,"scale",0,16,1,p),j.has("color")?color(j,"color",p):BODY.color(),id(j,"texture",p));}
    private static Selection selection(JsonObject root,String key,String source){if(!root.has(key))return null;String p=source+"."+key;var j=JEDJson.object(root.get(key),p);JEDJson.keys(j,Set.of("id","settings"),p);var id=id(j,"id",p);if(id==null)throw new JEDDefinitionException(p+": id is required");var settings=j.has("settings")?JEDJson.object(j.get("settings"),p+".settings"):new JsonObject();return new Selection(id,JEDJson.canonical(settings).toString());}
    public static Color color(JsonObject j,String key,String source){if(!j.has(key))return null;String value=JEDJson.string(j.get(key),source+"."+key);if(!value.matches("#[0-9a-fA-F]{6}"))throw new JEDDefinitionException(source+"."+key+": expected #RRGGBB");return new Color(Integer.parseInt(value.substring(1),16));}
    private static JsonObject object(JsonObject j,String key,String p){return j.has(key)?JEDJson.object(j.get(key),p+"."+key):new JsonObject();}
    private static ResourceLocation id(JsonObject j,String key,String p){return j.has(key)?JEDDimensionDefinition.id(JEDJson.string(j.get(key),p+"."+key),p+"."+key):null;}
    private static String string(JsonObject j,String key,String fallback,String p){return j.has(key)?JEDJson.string(j.get(key),p+"."+key):fallback;}
    private static Boolean nullableBool(JsonObject j,String key,String p){if(!j.has(key))return null;if(!j.get(key).isJsonPrimitive()||!j.get(key).getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(p+"."+key+": expected a boolean");return j.get(key).getAsBoolean();}
    private static boolean bool(JsonObject j,String key,boolean fallback,String p){var b=nullableBool(j,key,p);return b==null?fallback:b;}
    private static Double number(JsonObject j,String key,double min,double max,String p){if(!j.has(key))return null;var v=j.get(key);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||!Double.isFinite(v.getAsDouble())||v.getAsDouble()<min||v.getAsDouble()>max)throw new JEDDefinitionException(p+"."+key+": expected a finite number in ["+min+", "+max+"]");return v.getAsDouble();}
    private static double number(JsonObject j,String key,double min,double max,double fallback,String p){var n=number(j,key,min,max,p);return n==null?fallback:n;}
    private static int integer(JsonObject j,String key,int fallback,String p){return j.has(key)?JEDJson.integer(j.get(key),p+"."+key):fallback;}
    private static <T extends Enum<T>> T choice(JsonObject j,String key,Class<T> type,T fallback,String p){if(!j.has(key))return fallback;try{return Enum.valueOf(type,JEDJson.string(j.get(key),p+"."+key).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw new JEDDefinitionException(p+"."+key+": unknown value",e);}}

    public JsonObject json(){
        var root=new JsonObject();var s=new JsonObject();s.addProperty("mode",sky.mode().name().toLowerCase(Locale.ROOT));put(s,"renderer",sky.renderer());put(s,"color",sky.color());s.addProperty("biome_blend",sky.biomeBlend());s.addProperty("light_blend",sky.lightBlend());s.addProperty("fixed",sky.fixed());if(sky.sunrise()!=null)s.addProperty("sunrise",sky.sunrise());s.add("sun",bodyJson(sky.sun()));s.add("moon",bodyJson(sky.moon()));s.addProperty("stars",sky.stars());if(sky.brightness()!=null)s.addProperty("brightness",sky.brightness());if(sky.brightnessFactor()!=null)s.addProperty("brightness_factor",sky.brightnessFactor());root.add("sky",s);
        var c=new JsonObject();if(clouds.visible()!=null)c.addProperty("visible",clouds.visible());if(clouds.height()!=null)c.addProperty("height",clouds.height());put(c,"color",clouds.color());c.addProperty("fixed",clouds.fixed());put(c,"renderer",clouds.renderer());root.add("clouds",c);
        var f=new JsonObject();put(f,"color",fog.color());f.addProperty("fixed",fog.fixed());if(fog.near()!=null)f.addProperty("near",fog.near());if(fog.far()!=null)f.addProperty("far",fog.far());if(fog.shape()!=null)f.addProperty("shape",fog.shape());f.addProperty("override_fluids",fog.overrideFluids());f.addProperty("override_effects",fog.overrideEffects());f.addProperty("override_boss",fog.overrideBoss());root.add("fog",f);
        var w=new JsonObject();w.addProperty("visible",weatherVisible);put(w,"renderer",weather);root.add("weather",w);
        var b=new JsonObject();biomeColors.forEach((channel,rules)->{var a=new JsonArray();rules.forEach(rule->{var r=new JsonObject();if(rule.biome()!=null)r.addProperty("biome",rule.biome().toString());if(rule.tag()!=null)r.addProperty("tag",rule.tag().toString());if(rule.regex()!=null)r.addProperty("regex",rule.regex());r.addProperty("color",rule.color().hex());a.add(r);});b.add(channel,a);});root.add("biome_colors",b);
        var m=new JsonObject();m.addProperty("mode",music.mode().name().toLowerCase(Locale.ROOT));if(music.preset()!=null)m.addProperty("preset",music.preset());if(music.sound()!=null)m.addProperty("sound",music.sound().toString());m.addProperty("min_delay",music.minDelay());m.addProperty("max_delay",music.maxDelay());m.addProperty("replace",music.replace());m.addProperty("respect_special",music.respectSpecial());root.add("music",m);return root;
    }
    private static JsonObject bodyJson(Celestial body){var j=new JsonObject();j.addProperty("visible",body.visible());j.addProperty("scale",body.scale());put(j,"color",body.color());if(body.texture()!=null)j.addProperty("texture",body.texture().toString());return j;}
    private static void put(JsonObject j,String key,Color color){if(color!=null)j.addProperty(key,color.hex());}
    private static void put(JsonObject j,String key,Selection selection){if(selection!=null)j.add(key,selection.json());}
    public static void reset(String path,String source){
        if(path.equals("render"))return;
        var parts=path.split("\\.");if(parts.length==2&&Set.of("sky","clouds","fog","weather","biome_colors","music").contains(parts[1]))return;
        if(parts.length==3){Set<String> keys=switch(parts[1]){case "sky"->Set.of("mode","renderer","color","biome_blend","light_blend","fixed","sunrise","sun","moon","stars","brightness","brightness_factor");case "clouds"->Set.of("visible","height","color","fixed","renderer");case "fog"->Set.of("color","fixed","near","far","shape","override_fluids","override_effects","override_boss");case "weather"->Set.of("visible","renderer");case "biome_colors"->Set.of("grass","foliage","water");case "music"->Set.of("mode","preset","sound","min_delay","max_delay","replace","respect_special");default->Set.of();};if(keys.contains(parts[2]))return;}
        if(parts.length==4&&parts[1].equals("sky")&&Set.of("sun","moon").contains(parts[2])&&Set.of("visible","scale","color","texture").contains(parts[3]))return;
        if(parts.length>=4&&Set.of("sky","clouds","weather").contains(parts[1])&&parts[2].equals("renderer")){
            if(parts.length==4&&Set.of("id","settings").contains(parts[3]))return;
            if(parts.length>4&&parts[3].equals("settings"))return;
        }
        throw new JEDDefinitionException(source+": unknown or non-resettable render property '"+path+"'");
    }
}
