package justepicdimensions.time;

/** JED 1.6 celestial/moon formulas, with the approved clock-range and sleep corrections. */
public final class JEDTimeMath {
    public static int moon(JEDTimeDefinition definition,long time) {
        return (int)(time/definition.cycleLength()%8L+8L)%8;
    }
    public static float angle(JEDTimeDefinition definition,long time,float partial) {
        long length=definition.cycleLength(), ticks=time%length;
        if(definition.celestial().equals("range")) {
            var range=definition.angleRange();
            return range.min()+(range.max()-range.min())*(((float)ticks+partial)/(float)length);
        }
        int dawn=(int)(0.075f*length);float angle;
        if(ticks>length-dawn || ticks<definition.dayLength()-dawn) {
            if(ticks>definition.dayLength())ticks-=length-dawn;else ticks+=dawn;
            angle=(((float)ticks+partial)/(float)definition.dayLength()*0.65f)+0.675f;
        } else {
            ticks-=definition.dayLength()-dawn;
            angle=(((float)ticks+partial)/(float)definition.nightLength()*0.35f)+0.325f;
        }
        if(angle>1.0f)--angle;
        float curved=1.0f-(float)((Math.cos(angle*Math.PI)+1.0d)/2.0d);
        return angle+(curved-angle)/3.0f;
    }
    /** Only natural progression calls this; commands and incoming snapshots bypass wrapping. */
    public static long advance(JEDTimeDefinition definition,long current,long delta) {
        var range=definition.clockRange();
        if(range==null || delta<=0)return current+delta;
        long cycle=definition.cycleLength(),position=Math.floorMod(current,cycle),base=current-position;
        if(position<range.min() || position>range.max()) {
            current=base+range.min();position=range.min();delta--;
        }
        long offset=position-range.min()+delta,width=range.width(),wraps=offset/width;
        return current-(position-range.min())+wraps*((long)range.calendarDays()*cycle)+offset%width;
    }
    public static long dawn(JEDTimeDefinition definition,long current) {
        long cycle=definition.cycleLength(),base=current-Math.floorMod(current,cycle);
        var range=definition.clockRange();
        return range==null?base+cycle:base+(long)range.calendarDays()*cycle+range.min();
    }
    private JEDTimeMath() {}
}
