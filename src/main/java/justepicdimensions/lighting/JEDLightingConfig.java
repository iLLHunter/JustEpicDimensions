package justepicdimensions.lighting;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class JEDLightingConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue COLORS;
    private static final ModConfigSpec.IntValue POSITIONS,SUBMISSIONS;
    private static final ModConfigSpec.DoubleValue OWN_MS,DRAIN_MS;
    private static volatile com.google.gson.JsonObject heldRuntime;
    static {
        var b=new ModConfigSpec.Builder();
        COLORS=b.comment(justepicdimensions.config.JEDConfigComments.text("enableColorOverrides",true)).define("enableColorOverrides",true);
        b.push("client_light_verification");
        POSITIONS=b.comment(justepicdimensions.config.JEDConfigComments.text("client_light_verification.positions_per_tick",true)).defineInRange("positions_per_tick",512,1,Integer.MAX_VALUE);
        SUBMISSIONS=b.comment(justepicdimensions.config.JEDConfigComments.text("client_light_verification.submissions_per_tick",true)).defineInRange("submissions_per_tick",512,1,Integer.MAX_VALUE);
        OWN_MS=b.comment(justepicdimensions.config.JEDConfigComments.text("client_light_verification.own_time_ms",true)).defineInRange("own_time_ms",0.5,0.000001,Double.MAX_VALUE/1_000_000);
        DRAIN_MS=b.comment(justepicdimensions.config.JEDConfigComments.text("client_light_verification.drain_pressure_ms",true)).defineInRange("drain_pressure_ms",2.0,0.000001,Double.MAX_VALUE/1_000_000);
        b.pop();SPEC=b.build();
    }
    public static JEDClientLightScheduler.Budget budget(){var held=heldRuntime;return held==null?new JEDClientLightScheduler.Budget(POSITIONS.get(),SUBMISSIONS.get(),(long)(OWN_MS.get()*1_000_000),(long)(DRAIN_MS.get()*1_000_000)):
        new JEDClientLightScheduler.Budget(held.get("client_light_verification.positions_per_tick").getAsInt(),held.get("client_light_verification.submissions_per_tick").getAsInt(),(long)(held.get("client_light_verification.own_time_ms").getAsDouble()*1_000_000),(long)(held.get("client_light_verification.drain_pressure_ms").getAsDouble()*1_000_000));}
    public static boolean colors(){var held=heldRuntime;return held==null?COLORS.get():held.get("enableColorOverrides").getAsBoolean();}
    public static com.google.gson.JsonObject runtimeValues(){
        var held=heldRuntime;if(held!=null)return held.deepCopy();var values=new com.google.gson.JsonObject();
        values.addProperty("enableColorOverrides",COLORS.get());values.addProperty("client_light_verification.positions_per_tick",POSITIONS.get());
        values.addProperty("client_light_verification.submissions_per_tick",SUBMISSIONS.get());values.addProperty("client_light_verification.own_time_ms",OWN_MS.get());
        values.addProperty("client_light_verification.drain_pressure_ms",DRAIN_MS.get());return values;
    }
    /** Save-only edits survive native file-watcher reloads without changing the running client. */
    public static void holdRuntime(com.google.gson.JsonObject values){heldRuntime=values.deepCopy();}
    public static void releaseRuntime(){heldRuntime=null;}
    private JEDLightingConfig(){}
}
