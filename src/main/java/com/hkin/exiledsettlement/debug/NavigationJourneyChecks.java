package com.hkin.exiledsettlement.debug;

import com.hkin.exiledsettlement.citizen.NavigationJourney;
import net.minecraft.core.BlockPos;
import com.google.gson.JsonObject;

/** Exercises production retry/progress policy without moving any world entity. */
public final class NavigationJourneyChecks {
    public static String run(){
        var checks=new JsonObject();var j=new NavigationJourney();var start=new BlockPos(0,64,0);var target=start.offset(20,0,0);
        j.begin(target,start,20,0);checks.addProperty("normal_route_rejects_reverse_waypoint",!j.accepts(start.offset(-6,0,0),start,target,0));
        j.fail(1,null);checks.addProperty("first_retry_waits_twenty_ticks",j.waiting(20) && !j.waiting(21));
        checks.addProperty("first_failure_allows_twelve_block_detour",j.allowance()==12 && j.accepts(start.offset(-6,0,0),start,target,21));
        j.begin(target,start,20,10);checks.addProperty("repeated_command_does_not_reset_retry",j.failures()==1 && j.waiting(20));
        var other=start.offset(0,0,20);j.begin(other,start,20,12);j.begin(target,start,20,13);
        checks.addProperty("switch_away_and_back_keeps_failure_history",j.failures()==1 && j.waiting(20));
        j.fail(30,null);checks.addProperty("second_retry_waits_forty_ticks",j.waiting(69) && !j.waiting(70) && j.allowance()==32);
        j.fail(80,start.offset(-6,0,0));checks.addProperty("third_retry_waits_eighty_ticks",j.waiting(159) && !j.waiting(160) && j.allowance()==64);
        checks.addProperty("failed_segment_temporarily_rejected",!j.accepts(start.offset(-6,0,0),start,target,160));
        checks.addProperty("failed_segment_expires",j.accepts(start.offset(-6,0,0),start,target,481));
        for(int n=0;n<8;n++)j.fail(500+n*100,null);
        checks.addProperty("retry_and_detour_are_bounded",j.allowance()==64 && !j.waiting(1280));
        j.arrived(1300);checks.addProperty("arrival_restores_normal_policy",j.failures()==0 && j.allowance()==0 && !j.waiting(1300));
        var walk=new NavigationJourney();walk.begin(target,start,20,0);
        for(int n=1;n<=12;n++)walk.observe(start.offset(-n,0,0),20+n,n*20);
        checks.addProperty("walking_away_along_new_cells_counts_as_progress",walk.progressAt()==240 && !walk.looping());
        for(int n=241;n<=500;n++)walk.observe(start.offset(-12,0,0),32,n);
        checks.addProperty("stationary_path_churn_is_not_progress",walk.progressAt()==240);
        var loop=new NavigationJourney();loop.begin(target,start,20,0);
        for(int n=1;n<=6;n++)loop.observe(n%2==1?start.offset(0,0,1):start,20,n*20);
        checks.addProperty("repeated_back_and_forth_detected",loop.looping());
        loop.fail(150,null);checks.addProperty("recovery_clears_loop_but_keeps_retry",!loop.looping() && loop.failures()==1 && loop.waiting(169));
        var patient=new NavigationJourney();patient.begin(target,start,20,0);patient.begin(target,start,20,200);
        checks.addProperty("waiting_time_expands_detour_without_returned_failure",patient.allowance()==12 && patient.failures()==0);
        patient.begin(target,start,20,600);checks.addProperty("time_based_detour_has_limit",patient.allowance()==64);
        patient.completedWaypoint(start.offset(-6,0,0));checks.addProperty("arrival_tolerance_does_not_reselect_same_waypoint",!patient.accepts(start.offset(-6,0,0),start,target,600));
        patient.fail(610,null);patient.geometryChanged(611);checks.addProperty("changed_geometry_wakes_retry_without_erasing_patience",!patient.waiting(611) && patient.allowance()==64 && patient.failures()==1);
        var out=new JsonObject();out.add("checks",checks);out.addProperty("worldEdits",0);out.addProperty("passed",checks.entrySet().stream().allMatch(e->e.getValue().getAsBoolean()));return out.toString();
    }
    private NavigationJourneyChecks(){ }
}
