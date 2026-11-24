package justepicdimensions.schedule;

import com.google.gson.*;
import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.util.*;
import justepicdimensions.definition.*;

/** Calendar matching is local; explicit JED gap/overlap rules resolve candidates to instants. */
public final class JEDScheduleCalendar {
    public enum Gap { FIRST_VALID, SHIFT, SKIP }
    public enum Overlap { FIRST, SECOND, BOTH }
    private static final int GREGORIAN_CYCLE_DAYS=146097;
    private static final Map<String,Integer> MONTHS=names("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC",1);
    private static final Map<String,Integer> WEEKDAYS=names("SUN MON TUE WED THU FRI SAT",0);
    private final ZoneId zone;
    private final Gap gap;
    private final Overlap overlap;
    private final BitSet months,monthDays,weekdays;
    private final boolean cron,monthDayWildcard,weekdayWildcard;
    private final List<LocalTime> times;
    private final Map<LocalDate,List<Instant>> cache=Collections.synchronizedMap(new LinkedHashMap<>(){
        @Override protected boolean removeEldestEntry(Map.Entry<LocalDate,List<Instant>> entry){return size()>16;}
    });
    private JEDScheduleCalendar(ZoneId zone,Gap gap,Overlap overlap,BitSet months,BitSet monthDays,BitSet weekdays,
            boolean cron,boolean monthDayWildcard,boolean weekdayWildcard,List<LocalTime> times){
        this.zone=zone;this.gap=gap;this.overlap=overlap;this.months=months;this.monthDays=monthDays;this.weekdays=weekdays;
        this.cron=cron;this.monthDayWildcard=monthDayWildcard;this.weekdayWildcard=weekdayWildcard;this.times=List.copyOf(times);
        var start=LocalDate.of(2000,1,1);boolean possible=false;
        for(int day=0;day<GREGORIAN_CYCLE_DAYS&&!possible;day++)possible=matches(start.plusDays(day));
        if(!possible)throw new JEDDefinitionException("Calendar has no matching date in the Gregorian cycle");
    }
    public String signature(){return zone.getId()+"|"+gap+"|"+overlap+"|"+months+"|"+monthDays+"|"+weekdays+"|"+(cron&&!monthDayWildcard&&!weekdayWildcard)+"|"+times;}
    public ZoneId zone(){return zone;}
    public Gap gap(){return gap;}
    public Overlap overlap(){return overlap;}
    public static JEDScheduleCalendar decode(JsonElement raw,ZoneId zone,Gap gap,Overlap overlap,String at){
        var json=JEDJson.object(raw,at);JEDJson.keys(json,Set.of("cron","at","weekdays","months","month_days"),at);
        BitSet months,days,weekdays;boolean cron=json.has("cron"),domStar=true,dowStar=true;var times=new TreeSet<LocalTime>();
        if(cron){
            if(json.size()!=1)throw new JEDDefinitionException(at+": cron and structured calendar fields cannot be mixed");
            String[] fields=JEDJson.string(json.get("cron"),at+".cron").strip().split("\\s+");if(fields.length!=5)throw new JEDDefinitionException(at+": expected five cron fields: minute hour day-of-month month day-of-week");
            var minutes=field(fields[0],0,59,Map.of(),false,at);var hours=field(fields[1],0,23,Map.of(),false,at);
            days=field(fields[2],1,31,Map.of(),false,at);months=field(fields[3],1,12,MONTHS,false,at);weekdays=field(fields[4],0,7,WEEKDAYS,true,at);
            domStar=fields[2].contains("*");dowStar=fields[4].contains("*");
            for(int hour=hours.nextSetBit(0);hour>=0;hour=hours.nextSetBit(hour+1))for(int minute=minutes.nextSetBit(0);minute>=0;minute=minutes.nextSetBit(minute+1))times.add(LocalTime.of(hour,minute));
        }else{
            if(!json.has("at"))throw new JEDDefinitionException(at+": structured calendar requires at (one time or an array of times)");
            for(var value:values(json.get("at"))){try{var time=LocalTime.parse(JEDJson.string(value,at+".at"));if(time.getNano()!=0)throw new DateTimeException("Use whole seconds");times.add(time);}catch(DateTimeException error){throw new JEDDefinitionException(at+": expected HH:mm or HH:mm:ss without fractional seconds",error);}}
            if(times.isEmpty())throw new JEDDefinitionException(at+": at must not be empty");
            months=selection(json.get("months"),1,12,MONTHS,false,at+".months");days=selection(json.get("month_days"),1,31,Map.of(),false,at+".month_days");weekdays=selection(json.get("weekdays"),0,7,WEEKDAYS,true,at+".weekdays");
        }
        return new JEDScheduleCalendar(zone,gap,overlap,months,days,weekdays,cron,domStar,dowStar,new ArrayList<>(times));
    }
    public boolean matches(LocalDate date){
        if(!months.get(date.getMonthValue()))return false;boolean dom=monthDays.get(date.getDayOfMonth()),dow=weekdays.get(date.getDayOfWeek().getValue()%7);
        // Traditional five-field cron ORs two restricted day fields. Structured filters intersect.
        return cron&&!monthDayWildcard&&!weekdayWildcard?dom||dow:dom&&dow;
    }
    public List<Instant> resolve(LocalDateTime local){
        var rules=zone.getRules();var offsets=rules.getValidOffsets(local);
        if(offsets.size()==1)return List.of(local.toInstant(offsets.getFirst()));
        if(offsets.isEmpty()){
            ZoneOffsetTransition transition=rules.getTransition(local);if(gap==Gap.SKIP)return List.of();
            return List.of(gap==Gap.FIRST_VALID?transition.getInstant():local.plus(transition.getDuration()).toInstant(transition.getOffsetAfter()));
        }
        var first=local.toInstant(offsets.getFirst());var second=local.toInstant(offsets.getLast());
        return switch(overlap){case FIRST->List.of(first);case SECOND->List.of(second);case BOTH->first.isBefore(second)?List.of(first,second):List.of(second,first);};
    }
    public List<Instant> occurrences(LocalDate date){
        var known=cache.get(date);if(known!=null)return known;if(!matches(date))return List.of();
        var values=new TreeSet<Instant>();for(var time:times)values.addAll(resolve(date.atTime(time)));var result=List.copyOf(values);cache.put(date,result);return result;
    }
    /** Strictly after the argument; folded hours are ordered by instant, not by local time. */
    public Instant next(Instant after){return find(after,true);}
    public Instant previousOrEqual(Instant before){return find(before,false);}
    private Instant find(Instant point,boolean forward){
        var local=point.atZone(zone).toLocalDate();var date=local.plusDays(forward?-2:2);Instant best=null;
        // ZoneOffset is bounded to +/-18h; two neighboring local dates cover even date-line changes.
        for(int count=0;count<GREGORIAN_CYCLE_DAYS+5;count++,date=date.plusDays(forward?1:-1)){
            if(best!=null){var bound=best.atZone(zone).toLocalDate().plusDays(forward?2:-2);if(forward?date.isAfter(bound):date.isBefore(bound))break;}
            var candidates=occurrences(date);for(var candidate:candidates){boolean eligible=forward?candidate.isAfter(point):!candidate.isAfter(point);if(eligible&&(best==null||(forward?candidate.isBefore(best):candidate.isAfter(best))))best=candidate;}
        }return best;
    }
    private static Map<String,Integer> names(String names,int first){var result=new HashMap<String,Integer>();int i=first;for(String value:names.split(" "))result.put(value,i++);if(first==1)for(var month:Month.values())result.put(month.name(),month.getValue());else for(var day:DayOfWeek.values())result.put(day.name(),day.getValue()%7);return Map.copyOf(result);}
    private static JsonArray values(JsonElement raw){if(raw==null)throw new JEDDefinitionException("Expected calendar selection");if(raw.isJsonArray())return raw.getAsJsonArray();var result=new JsonArray();result.add(raw);return result;}
    private static BitSet selection(JsonElement raw,int min,int max,Map<String,Integer> names,boolean sunday,String at){
        if(raw==null)return field("*",min,max,names,sunday,at);var result=new BitSet();for(var value:values(raw)){String text=value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()?Integer.toString(JEDJson.integer(value,at)):JEDJson.string(value,at);result.or(field(text,min,max,names,sunday,at));}
        if(result.isEmpty())throw new JEDDefinitionException(at+": selection must not be empty");return result;
    }
    private static BitSet field(String raw,int min,int max,Map<String,Integer> names,boolean sunday,String at){
        var result=new BitSet();for(String part:raw.toUpperCase(Locale.ROOT).split(",",-1)){
            String[] step=part.split("/",-1);if(step.length>2||step[0].isEmpty())throw new JEDDefinitionException(at+": invalid cron field "+raw);
            int increment=step.length==2?numeric(step[1],at):1;if(increment<=0)throw new JEDDefinitionException(at+": cron step must be positive");int low,high;
            if(step[0].equals("*")){low=min;high=max;}else{String[] range=step[0].split("-",-1);if(range.length>2)throw new JEDDefinitionException(at+": invalid cron range "+part);low=value(range[0],names,at);high=range.length==2?value(range[1],names,at):step.length==2?max:low;}
            if(low<min||high>max||low>high)throw new JEDDefinitionException(at+": cron range outside "+min+".."+max+": "+part);
            for(long i=low;i<=high;i+=increment)result.set(sunday&&(int)i==7?0:(int)i);
        }if(result.isEmpty())throw new JEDDefinitionException(at+": empty cron field");return result;
    }
    private static int value(String raw,Map<String,Integer> names,String at){String text=raw.toUpperCase(Locale.ROOT);var named=names.get(text);return named!=null?named:numeric(text,at);}
    private static int numeric(String raw,String at){try{if(!raw.matches("[0-9]+"))throw new NumberFormatException();return Integer.parseInt(raw);}catch(NumberFormatException error){throw new JEDDefinitionException(at+": invalid calendar number "+raw,error);}}
}
