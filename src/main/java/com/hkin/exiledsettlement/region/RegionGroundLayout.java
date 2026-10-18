package com.hkin.exiledsettlement.region;

import java.util.*;

/** Shared saved soil plane; production facilities remain specific to each region type. */
public final class RegionGroundLayout {
    public static final int VERSION=1;
    private RegionGroundLayout() {}
    public static void grade(ProductionRegion r) {
        if(r.cells.isEmpty())return;
        if(r.flatY==null)r.flatY=r.first.getY()-1;
        r.waterBarriers.clear();r.stairs.clear();
        for(var e:List.copyOf(r.cells.entrySet())){
            var c=e.getValue();var p=c.ground();
            if(c.use()==ProductionRegion.Use.WATER && p.getY()!=r.flatY)r.oldIrrigation.add(p.asLong());
            if(c.use()==ProductionRegion.Use.PATH)r.preserved.remove(e.getKey());
            if(r.plainSoil() || p.getY()!=r.flatY)r.gradeOrigins.putIfAbsent(e.getKey(),p.getY());
            r.cells.put(e.getKey(),new ProductionRegion.Cell(p.atY(r.flatY),c.use(),c.production(),c.type()));
        }
        r.invalidate();
    }
}
