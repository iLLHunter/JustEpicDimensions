package justepicdimensions.lighting;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/** One allowance per client tick, shared by all chunks and rendered drains. */
public final class JEDClientLightScheduler {
    public record Budget(int positions, int submissions, long ownNanos, long drainNanos) {
        public static final Budget DEFAULT = new Budget(512, 512, 500_000, 2_000_000);
        public Budget {
            if (positions <= 0 || submissions <= 0 || ownNanos <= 0 || drainNanos <= 0)
                throw new IllegalArgumentException("All light work budgets must be positive");
        }
    }
    final JEDClientLightTracker tracker;
    Budget budget;
    final LongOpenHashSet submitted = new LongOpenHashSet(512);
    final BlockPos.MutableBlockPos checkPosition = new BlockPos.MutableBlockPos();
    long tick = Long.MIN_VALUE, ownSpent, drainStart, drains, ownTotal, ownTickMax, nativeTotal, nativeMax, nativeWork;
    int examined, checks, allowance = 128, headroom;
    long visitedTotal, submittedTotal, maxVisitedTick, maxSubmittedTick;
    public JEDClientLightScheduler(JEDClientLightTracker tracker, Budget budget) {
        this.tracker = tracker; this.budget = budget;
    }
    public void tick(long value) {
        if (tick == value) return;
        tick = value; examined = checks = 0; ownSpent = 0; submitted.clear();
    }
    boolean canExamine() { return examined < Math.min(allowance, budget.positions) && ownSpent < budget.ownNanos; }
    boolean canSubmit() { return checks < Math.min(allowance, budget.submissions) && ownSpent < budget.ownNanos; }
    boolean minimumWorkPending() { return examined==0&&checks==0; }
    void examined() { examined++; visitedTotal++; maxVisitedTick = Math.max(maxVisitedTick, examined); }
    boolean submit(long position) {
        if (submitted.contains(position)) return true;
        if (!canSubmit()) return false;
        submitted.add(position);
        checkPosition.set(BlockPos.getX(position), BlockPos.getY(position), BlockPos.getZ(position));
        tracker.view.check(checkPosition); checks++; submittedTotal++;
        maxSubmittedTick = Math.max(maxSubmittedTick, checks); return true;
    }
    void beforeDrain() {
        if (tick != Long.MIN_VALUE && ownSpent < budget.ownNanos && tracker.hasReadyWork()) {
            long begin = System.nanoTime(), deadline = begin + (budget.ownNanos - ownSpent);
            int remaining = tracker.chunks.size();
            // Round-robin at most 32 examined positions per job slice. A waiting chunk cannot spin.
            while (remaining-- > 0 && (System.nanoTime()-deadline<0||minimumWorkPending()) && (canExamine() || canSubmit())) {
                var chunk = tracker.nextChunk();
                if (chunk == null) break;
                if (chunk.job != null && chunk.geometryReady) {
                    chunk.job.step(this, 32, deadline);
                    if (chunk.job.done()) { chunk.job = null; tracker.completed++; }
                }
                if (remaining == 0 && tracker.hasReadyWork() && System.nanoTime()-deadline<0 && (canExamine() || canSubmit()))
                    remaining = tracker.chunks.size();
            }
            long elapsed = System.nanoTime() - begin; ownSpent += elapsed; ownTotal += elapsed;
            ownTickMax=Math.max(ownTickMax,ownSpent);
        }
        drainStart = System.nanoTime();
    }
    void afterDrain(int work) {
        long elapsed = System.nanoTime() - drainStart;
        drains++; nativeTotal += elapsed; nativeMax = Math.max(nativeMax, elapsed);
        nativeWork+=work;
        if (elapsed > budget.drainNanos) { allowance = Math.max(1, allowance / 2); headroom = 0; }
        else if (++headroom >= 20) {
            allowance = (int)Math.min(Math.max(budget.positions, budget.submissions), (long)allowance + 16); headroom = 0;
        }
    }
    public java.util.Map<String,Object> diagnostics() {
        return java.util.Map.ofEntries(java.util.Map.entry("visited", visitedTotal), java.util.Map.entry("submitted", submittedTotal),
            java.util.Map.entry("max_visited_per_tick", maxVisitedTick), java.util.Map.entry("max_submitted_per_tick", maxSubmittedTick),
            java.util.Map.entry("adaptive_allowance", allowance), java.util.Map.entry("own_total_ns", ownTotal), java.util.Map.entry("own_tick_max_ns",ownTickMax),
            java.util.Map.entry("native_total_ns", nativeTotal), java.util.Map.entry("native_max_ns", nativeMax), java.util.Map.entry("native_drains", drains),java.util.Map.entry("native_work",nativeWork));
    }
}
