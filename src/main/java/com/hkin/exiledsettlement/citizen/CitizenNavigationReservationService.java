package com.hkin.exiledsettlement.citizen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import java.util.Collection;
import java.util.UUID;
/** Legacy call surface. Work stations and passage tokens no longer own cells or scan residents. */
public final class CitizenNavigationReservationService {
 private CitizenNavigationReservationService() { }
 public static boolean requestPassage(Level l,UUID c,BlockPos p,Direction d,long t) { return true; }
 public static boolean requestPassage(Level l,UUID c,BlockPos p,Direction d,Collection<BlockPos> cells,long t) { return true; }
 public static boolean claimStation(Level l,UUID c,BlockPos p,long t) { return true; }
 public static boolean claimStation(Level l,UUID c,BlockPos p,BlockPos current,long t) { return true; }
 @Nullable public static BlockPos stationFor(Level l,UUID c,long t) { return null; }
 public static boolean stationHeldByOther(Level l,UUID c,BlockPos p,long t) { return false; }
 public static void releasePassedPassages(Level l,UUID c,BlockPos p,long t) { }
 public static void releaseAll(Level l,UUID c) { }
 public static void releasePassages(Level l,UUID c) { }
 public static void releaseStation(Level l,UUID c) { }
 public static void resetStationAfterAccessTopologyChange(Level l,UUID c) { }
 public static void abandonStation(Level l,UUID c,long t) { }
}
