package justepicdimensions.pregen;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Native Chunky futures can outlive its task-map entry. No level mutation occurs on callback threads. */
public final class JEDPregenerationHooks {
    private static final ConcurrentHashMap<Object,AtomicInteger> PENDING=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object,AtomicInteger> TASKS=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object,Object> WRITERS=new ConcurrentHashMap<>();
    public static void saved(Object task){if(task instanceof JEDChunkyTaskAccess access&&access.jed$pregenWorld() instanceof JEDChunkyWorldAccess world)WRITERS.put(world.jed$pregenLevel(),task);}
    public static boolean savedBy(Object level,Object task){return WRITERS.get(level)==task;}
    private static final ConcurrentHashMap<Object,Throwable> CRASHES=new ConcurrentHashMap<>();
    public static boolean crashed(Object task){return CRASHES.containsKey(task);}
    public static int tasks(Object level){var count=TASKS.get(level);return count==null?0:count.get();}
    public static boolean retained(Object level){return pending(level)>0||tasks(level)>0;}
    private static final ConcurrentHashMap<Object,java.util.concurrent.atomic.AtomicLong> ERRORS=new ConcurrentHashMap<>();
    public static long failures(Object level){var count=ERRORS.get(level);return count==null?0:count.get();}
    public static void forget(Object level){if(!retained(level)){ERRORS.remove(level);WRITERS.remove(level);CRASHES.keySet().removeIf(task->task instanceof JEDChunkyTaskAccess access&&access.jed$pregenWorld() instanceof JEDChunkyWorldAccess world&&world.jed$pregenLevel()==level);}}
    public static <T> CompletableFuture<T> track(Object level,CompletableFuture<T> future){
        if(level==null||future==null)throw new IllegalArgumentException("Missing Chunky world/future");
        PENDING.compute(level,(key,count)->{if(count==null)count=new AtomicInteger();count.incrementAndGet();return count;});
        future.whenComplete((value,error)->{if(error!=null)ERRORS.computeIfAbsent(level,key->new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();PENDING.computeIfPresent(level,(key,count)->count.decrementAndGet()==0?null:count);});return future;
    }
    public static int pending(Object level){var count=PENDING.get(level);return count==null?0:count.get();}
    public static <T> CompletableFuture<T> trackWorld(Object world,CompletableFuture<T> future){return world instanceof JEDChunkyWorldAccess access?track(access.jed$pregenLevel(),future):future;}
    public static Future<?> submit(ExecutorService executor,Runnable runnable){
        if(!(runnable instanceof JEDChunkyTaskAccess task)||!(task.jed$pregenWorld() instanceof JEDChunkyWorldAccess world))return executor.submit(runnable);
        Object level=world.jed$pregenLevel();TASKS.compute(level,(key,count)->{if(count==null)count=new AtomicInteger();count.incrementAndGet();return count;});var released=new java.util.concurrent.atomic.AtomicBoolean();var started=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release=()->{if(released.compareAndSet(false,true))TASKS.computeIfPresent(level,(key,count)->count.decrementAndGet()==0?null:count);};
        var future=new FutureTask<Void>(()->{try{runnable.run();}catch(Throwable error){CRASHES.put(runnable,error);throw error;}},null){public void run(){started.set(true);try{super.run();}finally{release.run();}}protected void done(){if(!started.get())release.run();}};
        try{executor.execute(future);}catch(RuntimeException error){release.run();throw error;}return future;
    }
    private JEDPregenerationHooks(){}
}
