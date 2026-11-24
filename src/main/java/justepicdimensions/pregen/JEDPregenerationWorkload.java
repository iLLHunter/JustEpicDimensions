package justepicdimensions.pregen;
/** Wall-clock recovery avoids lag stretching a configured recovery period. */
public final class JEDPregenerationWorkload {
 private long healthySince=-1;
 public boolean allowed(JEDPregenerationPolicy.Workload rule,boolean running,int players,double tps,long now){
  if(rule.mode().equals("manual")){healthySince=-1;return true;}
  if(rule.mode().equals("empty")){healthySince=-1;return players==0;}
  if(!Double.isFinite(tps)||tps<rule.pauseTps()){healthySince=-1;return false;}
  if(running)return true;
  if(tps<rule.resumeTps()){healthySince=-1;return false;}
  if(healthySince<0||now<healthySince)healthySince=now;
  return now-healthySince>=rule.delaySeconds()*1000L;
 }
}
