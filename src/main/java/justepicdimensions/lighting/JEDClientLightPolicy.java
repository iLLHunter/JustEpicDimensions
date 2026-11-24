package justepicdimensions.lighting;

/** Server-resolved coverage, independent of a shared native DimensionType. */
public enum JEDClientLightPolicy {
    OFF("off"), UPDATES("updates"), CHUNKS_AND_UPDATES("chunks_and_updates");

    private final String id;
    JEDClientLightPolicy(String id) { this.id = id; }
    public String id() { return id; }
    public boolean accepts(boolean fullChunk) {
        return this == CHUNKS_AND_UPDATES || this == UPDATES && !fullChunk;
    }
    public static JEDClientLightPolicy parse(String id) {
        for (var value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("Unknown client light check policy: " + id);
    }
    public enum Profile { SURFACE, BASE, NETHER, END }
    public static JEDClientLightPolicy resolve(Profile profile, JEDClientLightPolicy explicit) {
        return explicit != null ? explicit : profile == Profile.SURFACE ? UPDATES : CHUNKS_AND_UPDATES;
    }
    public static JEDClientLightPolicy resolve(Profile profile,JEDClientLightPolicy datapack,
            JEDClientLightPolicy json,java.util.function.Consumer<String> conflictLog) {
        if(json!=null&&datapack!=null&&json!=datapack)
            conflictLog.accept("JED client_light_check conflict: JSON="+json.id+" overrides datapack="+datapack.id);
        return resolve(profile,json!=null?json:datapack);
    }
}
