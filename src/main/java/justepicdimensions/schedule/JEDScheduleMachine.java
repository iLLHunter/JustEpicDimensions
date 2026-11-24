package justepicdimensions.schedule;

import com.google.gson.*;
import java.time.Instant;
import java.util.Locale;
import static justepicdimensions.schedule.JEDLifecycleSchedulePolicy.*;

/** Pure transitions; the server coordinator owns persistence, admission and native operations. */
public final class JEDScheduleMachine {
    public enum Kind { WAITING, DEFERRED, SKIPPED, WARNING, READY, NATIVE, OPEN, CLOSED, EVACUATE }
    public record Evaluation(JsonObject state,Kind kind){}
    public static JsonObject initial(Rule rule,long now,boolean initialOpen){
        var state=new JsonObject();state.addProperty("signature",rule.signature());state.addProperty("action",rule.action().name().toLowerCase(Locale.ROOT));state.addProperty("activated_at",now);state.addProperty("active",true);state.addProperty("clock",rule.clock().name().toLowerCase(Locale.ROOT));state.addProperty("next_due",rule.next(now));
        if(rule.action()==Action.AVAILABILITY){
            if(rule.clock()==Clock.CALENDAR){var previous=rule.calendar().previousOrEqual(Instant.ofEpochMilli(now));long until=previous==null?0:rule.closes(previous.toEpochMilli());state.addProperty("open_until",until);state.addProperty("was_open",now<until);}
            else{long first=initialOpen?now:rule.next(now);state.addProperty("first_open",first);state.addProperty("open_until",initialOpen?rule.closes(now):0);state.addProperty("was_open",initialOpen);}
        }return state;
    }
    public static Evaluation reset(Rule rule,JsonObject original,long now,int occupants,boolean startup){
        var state=original.deepCopy();if(state.has("native_token"))return new Evaluation(state,Kind.NATIVE);long due=state.get("next_due").getAsLong();long grace=rule.warnings().units(rule.clock());
        if(startup&&rule.clock()!=Clock.SERVER_TICKS&&rule.missed().equals("skip")&&!state.has("pending_due")&&now>due){
            state.addProperty("next_due",nextFuture(rule,due,now,false));clearPending(state);return new Evaluation(state,Kind.SKIPPED);
        }
        if(now<due){
            if(rule.occupied().equals("evacuate")&&rule.warnings().before()&&occupants>0&&due-now<=grace){if(!state.has("warning_end"))state.addProperty("warning_end",due);return new Evaluation(state,Kind.WARNING);}
            return new Evaluation(state,Kind.WAITING);
        }
        if(!state.has("pending_due"))state.addProperty("pending_due",due);
        if(rule.occupied().equals("evacuate")&&state.has("warning_end"))return new Evaluation(state,now<state.get("warning_end").getAsLong()?Kind.WARNING:Kind.READY);
        if(occupants==0)return new Evaluation(state,Kind.READY);
        return switch(rule.occupied()){
            case "defer"->new Evaluation(state,Kind.DEFERRED);
            case "skip"->{state.addProperty("next_due",nextFuture(rule,due,now,true));clearPending(state);yield new Evaluation(state,Kind.SKIPPED);}
            case "evacuate"->{state.addProperty("warning_end",Math.addExact(now,grace));yield new Evaluation(state,grace==0?Kind.READY:Kind.WARNING);}
            default->throw new IllegalArgumentException("Unknown occupied reset policy");
        };
    }
    public static JsonObject completed(Rule rule,JsonObject original,long completion){
        var state=original.deepCopy();long due=state.has("native_due")?state.get("native_due").getAsLong():state.has("pending_due")?state.get("pending_due").getAsLong():state.get("next_due").getAsLong();
        state.addProperty("last_completed",completion);state.addProperty("next_due",rule.clock()==Clock.CALENDAR?rule.next(completion):rule.cadence().equals("completion")?rule.next(completion):nextFuture(rule,due,completion,true));clearPending(state);return state;
    }
    public static Evaluation window(Rule rule,JsonObject original,long now,int occupants){
        var state=original.deepCopy();boolean wasOpen=state.get("was_open").getAsBoolean();long next=state.get("next_due").getAsLong();
        if(!rule.occupied().equals("evacuate")){state.remove("warning_end");state.remove("last_notice");}
        if(now>=next){
            long opened;if(rule.clock()==Clock.CALENDAR){var previous=rule.calendar().previousOrEqual(Instant.ofEpochMilli(now));opened=previous==null?0:previous.toEpochMilli();}else opened=Math.addExact(next,Math.multiplyExact((now-next)/rule.interval(),rule.interval()));
            state.addProperty("open_until",rule.closes(opened));state.addProperty("next_due",nextFuture(rule,next,now,true));
        }
        long until=state.get("open_until").getAsLong();boolean open=now<until;state.addProperty("was_open",open);
        if(open){
            if(state.has("warning_end")&&(state.get("warning_end").getAsLong()!=until||now>=state.get("warning_end").getAsLong())){state.remove("warning_end");state.remove("last_notice");}
            boolean actualGap=state.get("next_due").getAsLong()>until;
            if(rule.occupied().equals("evacuate")&&rule.warnings().before()&&actualGap&&until-now<=rule.warnings().units(rule.clock())){if(!state.has("warning_end"))state.addProperty("warning_end",until);return new Evaluation(state,Kind.WARNING);}
            return new Evaluation(state,Kind.OPEN);
        }
        if(wasOpen&&rule.occupied().equals("evacuate")&&!state.has("warning_end"))state.addProperty("warning_end",Math.addExact(now,rule.warnings().units(rule.clock())));
        if(state.has("warning_end")){if(now<state.get("warning_end").getAsLong())return new Evaluation(state,Kind.WARNING);if(occupants>0)return new Evaluation(state,Kind.EVACUATE);state.remove("warning_end");state.remove("last_notice");}
        return new Evaluation(state,Kind.CLOSED);
    }
    public static boolean openAt(Rule rule,JsonObject state,long now){
        if(rule.clock()==Clock.CALENDAR){var previous=rule.calendar().previousOrEqual(Instant.ofEpochMilli(now));return previous!=null&&now<rule.closes(previous.toEpochMilli());}
        long first=state.get("first_open").getAsLong();if(now<first)return false;long opened=Math.addExact(first,Math.multiplyExact((now-first)/rule.interval(),rule.interval()));return now<rule.closes(opened);
    }
    public static long nextFuture(Rule rule,long anchor,long now,boolean strict){
        if(rule.clock()==Clock.CALENDAR){var next=rule.calendar().next(Instant.ofEpochMilli(strict?now:now-1));return next==null?Long.MAX_VALUE:next.toEpochMilli();}
        if(anchor>now||(!strict&&anchor==now))return anchor;long elapsed=now-anchor,steps=elapsed/rule.interval();if(strict||elapsed%rule.interval()!=0)steps=Math.addExact(steps,1);return Math.addExact(anchor,Math.multiplyExact(steps,rule.interval()));
    }
    private static void clearPending(JsonObject state){for(String key:new String[]{"pending_due","warning_end","last_notice","native_token","native_due","blocked_reason"})state.remove(key);}
    private JEDScheduleMachine(){}
}
