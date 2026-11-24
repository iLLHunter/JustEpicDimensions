package justepicdimensions.dimension;

import java.util.*;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Wall time and actual server-thread work are distinct: waiting for a worker is not a tick stall. */
public final class JEDCreationTimings {
    public record Phase(double wallMs, double serverMs, double workerMs, double maxServerSliceMs, int calls) {}
    public record Report(ResourceLocation dimension, String outcome, double elapsedMs, Map<String, Phase> phases) {
        public Report { phases = Map.copyOf(phases); }
    }
    private static final Map<MinecraftServer, Report> LAST = new IdentityHashMap<>();
    private final ResourceLocation dimension;
    private final Thread serverThread;
    private final long start = System.nanoTime();
    private final Map<String, double[]> phases = new LinkedHashMap<>();
    public JEDCreationTimings(MinecraftServer server, ResourceLocation dimension) {
        if (!server.isSameThread()) throw new IllegalStateException("Timing recorder must start on the server thread");
        this.dimension = dimension; this.serverThread = Thread.currentThread();
    }
    public final class Span implements AutoCloseable {
        private final String name;
        private final long begin = System.nanoTime();
        private boolean closed;
        private Span(String name) { this.name = name; }
        public void close() {
            if (closed) return; closed = true;
            double ms = (System.nanoTime() - begin) / 1e6;
            synchronized (phases) {
                var phase = phases.computeIfAbsent(name, ignored -> new double[5]); phase[0] += ms; phase[4]++;
                if (Thread.currentThread() == serverThread) { phase[1] += ms; phase[3] = Math.max(phase[3], ms); }
                else phase[2] += ms;
            }
        }
    }
    public Span span(String name) { return new Span(name); }
    public <T> T measure(String name, Supplier<T> work) { try (var ignored = span(name)) { return work.get(); } }
    public void measure(String name, Runnable work) { try (var ignored = span(name)) { work.run(); } }
    public Report finish(MinecraftServer server, String outcome) {
        if (!server.isSameThread()) throw new IllegalStateException("Timing results require the server thread");
        var values = new LinkedHashMap<String, Phase>();
        synchronized (phases) { phases.forEach((name, p) -> values.put(name, new Phase(p[0], p[1], p[2], p[3], (int)p[4]))); }
        var report = new Report(dimension, outcome, (System.nanoTime() - start) / 1e6, values); LAST.put(server, report);
        justepicdimensions.definition.JEDInfoLog.info(server,"JED creation timing {} {}: elapsed={} ms, phases={}", dimension, outcome, report.elapsedMs(), values);
        return report;
    }
    public static Optional<Report> last(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Timing inspection requires the server thread");
        return Optional.ofNullable(LAST.get(server));
    }
    public static void stopped(MinecraftServer server) { LAST.remove(server); }
}
