package com.hkin.exiledsettlement.debug;

import com.google.gson.JsonObject;
import java.util.Arrays;

/** Test-only measurement includes mod post-tick work omitted by vanilla's rolling average. */
public final class EngineeringTickSamples {
    private static final long[] VALUES=new long[1200];
    private static int cursor,count;
    public static void record(long start) {if(start==0)return;VALUES[cursor++%VALUES.length]=System.nanoTime()-start;count=Math.min(count+1,VALUES.length);}
    public static JsonObject snapshot() {
        var j=new JsonObject();var values=Arrays.copyOf(VALUES,count);Arrays.sort(values);
        j.addProperty("samples",count);
        if(count>0) {j.addProperty("postTickAvgMs",Arrays.stream(values).average().orElse(0)/1e6);
            j.addProperty("postTickP95Ms",values[(count-1)*95/100]/1e6);j.addProperty("postTickMaxMs",values[count-1]/1e6);}
        return j;
    }
    private EngineeringTickSamples() {}
}
