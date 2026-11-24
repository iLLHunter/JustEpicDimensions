package justepicdimensions.spawn;

/** Ordinary classes observed during cold native ticket propagation; no initializers are run. */
public final class JEDSpawnChunkClasses {
    private static final String[] TYPES = {
        "it.unimi.dsi.fastutil.shorts.ShortIterable",
        "it.unimi.dsi.fastutil.shorts.ShortCollection",
        "it.unimi.dsi.fastutil.shorts.ShortSet",
        "java.util.concurrent.atomic.AtomicReferenceArray",
        "net.minecraft.util.StaticCache2D$Initializer"
    };
    public record Preparation(int classes, long elapsedNanos) {}
    private static volatile Preparation prepared;
    public static synchronized Preparation prepare() {
        if (prepared != null) return prepared;
        long start = System.nanoTime(); var loader = JEDSpawnChunkClasses.class.getClassLoader();
        for (String name : TYPES) {
            try { Class.forName(name, false, loader); }
            catch (ClassNotFoundException error) { throw new IllegalStateException("JED spawn-chunk startup class is missing: " + name, error); }
        }
        return prepared = new Preparation(TYPES.length, System.nanoTime() - start);
    }
    public static Preparation preparation() { return prepared; }
    private JEDSpawnChunkClasses() {}
}
