package justepicdimensions.worldgen;
import java.lang.ref.*;
import java.util.concurrent.*;
/** Weak keys with identity equality, including for native records whose value equality spans dimensions. */
final class JEDWeakIdentityMap<K,V> {
 private static final class Key<T> extends WeakReference<T>{final int hash;Key(T value,ReferenceQueue<T> queue){super(value,queue);hash=System.identityHashCode(value);}public int hashCode(){return hash;}public boolean equals(Object other){return this==other||other instanceof Key<?> key&&get()!=null&&get()==key.get();}}
 private final ReferenceQueue<K> queue=new ReferenceQueue<>();private final ConcurrentMap<Key<K>,V> values=new ConcurrentHashMap<>();
 private void reap(){Reference<? extends K> gone;while((gone=queue.poll())!=null)values.remove(gone);}
 V get(K key){reap();try{return values.get(new Key<>(key,null));}finally{Reference.reachabilityFence(key);}}
 void put(K key,V value){reap();try{values.put(new Key<>(key,queue),value);}finally{Reference.reachabilityFence(key);}}
}
