package com.hkin.exiledsettlement.terrain;

import net.minecraft.nbt.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded NBT batches preserve every record without a simultaneous deep-copy forest. */
public final class TerrainProjectPages {
    public static final int PAGE_SIZE=128;
    public static void save(CompoundTag target,Collection<TerrainProject> projects){
        var pages=new ListTag();var records=new ListTag();
        for(var project:projects){
            records.add(project.save());
            if(records.size()==PAGE_SIZE){pages.add(encode(records));records=new ListTag();}
        }
        if(!records.isEmpty())pages.add(encode(records));
        target.putInt("ProjectsEncoding",1);target.putInt("ProjectCount",projects.size());target.put("ProjectPages",pages);
    }
    private static CompoundTag encode(ListTag records){
        var root=new CompoundTag();root.put("Projects",records);
        try(var bytes=new ByteArrayOutputStream()){
            NbtIo.writeCompressed(root,bytes);
            var page=new CompoundTag();page.putInt("Count",records.size());page.putByteArray("Data",bytes.toByteArray());return page;
        }catch(IOException failure){throw new UncheckedIOException("Could not save terrain history batch",failure);}
    }
    public static ListTag decode(CompoundTag page){
        int count=page.getInt("Count");
        if(count<1 || count>PAGE_SIZE || !page.contains("Data",Tag.TAG_BYTE_ARRAY))throw new IllegalArgumentException("Invalid terrain history batch");
        try(var input=new ByteArrayInputStream(page.getByteArray("Data"))){
            var root=NbtIo.readCompressed(input,NbtAccounter.create(128L*1024*1024));
            var records=root.getList("Projects",Tag.TAG_COMPOUND);
            if(records.size()!=count)throw new IllegalArgumentException("Terrain history batch count mismatch");
            return records;
        }catch(IOException failure){throw new UncheckedIOException("Could not read terrain history batch",failure);}
    }
    public static void visit(CompoundTag source,Consumer<CompoundTag> consumer){
        if(!source.contains("ProjectsEncoding")){
            var records=source.getList("Projects",Tag.TAG_COMPOUND);
            for(int i=0;i<records.size();i++)consumer.accept(records.getCompound(i));
            return;
        }
        if(source.getInt("ProjectsEncoding")!=1 || !source.contains("ProjectPages",Tag.TAG_LIST))throw new IllegalArgumentException("Unsupported terrain history encoding");
        var pages=source.getList("ProjectPages",Tag.TAG_COMPOUND);int total=0;
        for(int i=0;i<pages.size();i++){var records=decode(pages.getCompound(i));total+=records.size();for(int j=0;j<records.size();j++)consumer.accept(records.getCompound(j));}
        if(total!=source.getInt("ProjectCount"))throw new IllegalArgumentException("Terrain history total count mismatch");
    }
    private TerrainProjectPages(){}
}
