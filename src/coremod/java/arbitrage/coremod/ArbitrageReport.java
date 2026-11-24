package arbitrage.coremod;

import cpw.mods.modlauncher.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Makes Arbitrage's changes visible, so a crash involving them points at Just Epic Dimensions instead of whichever mod
 * happened to be on the stack:
 * <ul>
 *   <li>every transformer is labelled, so ModLauncher tags stack-trace lines in changed classes with
 *       {@code xf:fml:arbitrage:justepicdimensions:<part>} instead of the anonymous {@code xf:fml:default};</li>
 *   <li>a failed change is logged as an error naming both mods, the class and the part before it stops loading;</li>
 *   <li>crash reports gain an "Arbitrage" section listing each changed class and other mods' Mixins applied to it,
 *       because another mod's Mixin can fail when Arbitrage has already changed its target.</li>
 * </ul> */
final class ArbitrageReport {
    static final String TAG = "xf:fml:arbitrage:justepicdimensions:";
    static final Logger LOGGER = LoggerFactory.getLogger("Arbitrage");
    private static final Map<String, Set<String>> CHANGED = new ConcurrentSkipListMap<>();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    static String version() { var version = ArbitrageReport.class.getPackage().getImplementationVersion(); return version == null ? "development build" : version; }

    /** The transformer's class name inside this package, e.g. {@code JEDDimensionTransforms.Server}. */
    static String part(ITransformer<?> transformer) {
        var type = transformer.getClass(); return type.getName().substring(type.getPackageName().length() + 1).replace('$', '.');
    }

    static <T> ITransformer<T> label(ITransformer<T> transformer) { return new Labelled<>(transformer, part(transformer)); }

    /** Logs once per launch that Arbitrage is active and how to recognise its changes, and adds the crash-report section. */
    static void registered(int transformers) {
        if (!ANNOUNCED.compareAndSet(false, true)) return;
        LOGGER.info("Arbitrage {} is active for Just Epic Dimensions with {} transformers. Stack-trace lines in classes it changes are tagged {}<part>; crash reports list those classes in an \"Arbitrage\" section.", version(), transformers, TAG);
        try { net.neoforged.fml.CrashReportCallables.registerCrashCallable("Arbitrage", ArbitrageReport::describe); }
        catch (Throwable unavailable) { LOGGER.warn("Arbitrage could not add its crash-report section: {}", unavailable.toString()); }
    }

    static void changed(String className, String part) { CHANGED.computeIfAbsent(className, ignored -> new ConcurrentSkipListSet<>()).add(part); }

    /** The crash-report section. Never throws: a broken report would hide the original crash. */
    static String describe() {
        try {
            var out = new StringBuilder(version()).append(", a required part of Just Epic Dimensions. It changed ").append(CHANGED.size())
                .append(" classes. Stack-trace lines tagged ").append(TAG).append("<part> are in code it changed; if the crash goes through them, or a Mixin failed in one of these classes, report it to Just Epic Dimensions with this report.");
            for (var entry : CHANGED.entrySet()) {
                out.append("\n\t\t").append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue()));
                var mixins = appliedMixins(entry.getKey());
                if (!mixins.isEmpty()) out.append(" (also changed by Mixins: ").append(String.join(", ", mixins)).append(')');
            }
            if (!mixinProblem.isEmpty()) out.append("\n\t\tOther mods' Mixins in these classes could not be listed: ").append(mixinProblem);
            return out.toString();
        } catch (Throwable error) { return "unavailable (" + error + ")"; }
    }

    private static volatile String mixinProblem = "";
    /** Other mods' Mixins applied to a class, as {@code config:mixin}. Read from ModLauncher's audit trail, the record that also
     * tags stack-trace lines, because Mixin's own collections are emptied or incomplete once configs are selected. */
    static Set<String> appliedMixins(String className) {
        try {
            var trail = cpw.mods.modlauncher.Launcher.INSTANCE.environment().getProperty(IEnvironment.Keys.AUDITTRAIL.get());
            var result = new TreeSet<String>();
            if (trail.isPresent()) for (var entry : trail.get().getAuditString(className).split(",")) if (entry.startsWith("pl:mixin:APP:")) result.add(entry.substring("pl:mixin:APP:".length()));
            return result;
        } catch (Throwable unavailable) { mixinProblem = unavailable.toString(); return Set.of(); }
    }

    static IllegalStateException failure(String className, String part, RuntimeException error) {
        String message = "Arbitrage (required by Just Epic Dimensions) could not change " + className + " [" + part + "]: " + error.getMessage()
            + ". Another mod may have changed this class first, or this Minecraft/NeoForge build is not supported. Report this to Just Epic Dimensions with the full log.";
        LOGGER.error(message, error);
        return new IllegalStateException(message, error);
    }

    /** Delegates to the real transformer; adds the label, records the change and explains failures. */
    record Labelled<T>(ITransformer<T> inner, String part) implements ITransformer<T> {
        @Override public T transform(T input, ITransformerVotingContext context) {
            String target = context != null ? context.getClassName() : input instanceof ClassNode node ? node.name.replace('/', '.') : "an unknown class";
            T result;
            try { result = inner.transform(input, context); }
            catch (RuntimeException error) { throw failure(target, part, error); }
            changed(target, part);
            return result;
        }
        @Override public TransformerVoteResult castVote(ITransformerVotingContext context) { return inner.castVote(context); }
        @Override public Set<Target<T>> targets() { return inner.targets(); }
        @Override public TargetType<T> getTargetType() { return inner.getTargetType(); }
        @Override public String[] labels() { return new String[]{"arbitrage", "justepicdimensions", part}; }
    }

    private ArbitrageReport() {}
}
