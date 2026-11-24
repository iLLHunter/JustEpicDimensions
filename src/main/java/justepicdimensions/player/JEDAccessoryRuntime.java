package justepicdimensions.player;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Reuse backend equipment reconciliation without adding a second gameplay item tick. */
public final class JEDAccessoryRuntime {
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private static final ThreadLocal<Boolean> FINISHING=ThreadLocal.withInitial(()->false);
    public static boolean finishingSizing(){return FINISHING.get();}
    public static void finishSizing(Runnable action){boolean before=FINISHING.get();FINISHING.set(true);try{action.run();}finally{if(before)FINISHING.set(true);else FINISHING.remove();}}
    public static boolean reconciling(){return DEPTH.get()>0;}
    public static void reconcile(Runnable action){int before=DEPTH.get();DEPTH.set(before+1);try{action.run();}finally{if(before==0)DEPTH.remove();else DEPTH.set(before);}}
    public static void tickItem(ItemStack stack,Level level,Entity entity,int slot,boolean selected){if(!reconciling())stack.inventoryTick(level,entity,slot,selected);}
    private JEDAccessoryRuntime(){}
}
