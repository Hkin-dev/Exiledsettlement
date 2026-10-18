package com.hkin.exiledsettlement.command;

import com.hkin.exiledsettlement.citizen.*;
import com.hkin.exiledsettlement.building.SettlementInventoryService;
import com.hkin.exiledsettlement.settlement.*;
import com.hkin.exiledsettlement.registry.ModItems;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import java.util.*;

/** Read-only administrator view of the currently loaded world; never advances residents or supplies items. */
public final class ResidentClimateCommands {
    private ResidentClimateCommands() { }
    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("residents").requires(s -> s.hasPermission(2))
                .executes(ctx -> show(ctx.getSource(),1))
                .then(Commands.argument("page",IntegerArgumentType.integer(1)).executes(ctx -> show(ctx.getSource(),IntegerArgumentType.getInteger(ctx,"page"))));
    }
    private static int show(CommandSourceStack source,int page) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player=source.getPlayerOrException();var level=player.serverLevel();
        var s=SettlementSavedData.get(level).findContaining(player.blockPosition()).orElse(null);
        if(s==null || !s.isMember(player.getUUID())){source.sendFailure(Component.literal("请在有权限的聚落内查看"));return 0;}
        var residents=s.citizens().stream().filter(CitizenRecord::alive).toList();
        int loaded=0,sleeping=0,cold=0;
        for(var c:residents){var e=CitizenProjectionService.findProjection(level,c);if(e!=null){loaded++;if(e.isSleeping())sleeping++;}if(c.thermal().body<ThermalSettings.warning)cold++;}
        send(source,"当前游戏刻 "+level.getGameTime()+" / 当日时刻 "+Math.floorMod(level.getDayTime(),24000)+"；存活 "+residents.size()+" / 已加载 "+loaded+" / 实际上床 "+sleeping+" / 受寒 "+cold);
        int wool=SettlementInventoryService.warehouses(s).stream().flatMap(b->CommunityStock.items(level,s,b).entrySet().stream())
                .filter(r->new ItemStack(r.getKey()).is(ItemTags.WOOL)).mapToInt(Map.Entry::getValue).sum();
        send(source,"公共库存：羊毛 "+wool+" / 皮革 "+CommunityStock.count(level,s,Items.LEATHER)+" / 线 "+CommunityStock.count(level,s,Items.STRING)
                +" / 羊毛衣物 "+CommunityStock.count(level,s,ModItems.WOOL_COAT.get())+" / 皮革衣物 "+CommunityStock.count(level,s,ModItems.LEATHER_COAT.get()));
        int pages=Math.max(1,(residents.size()+5)/6);if(page>pages){source.sendFailure(Component.literal("页码超出范围，共"+pages+"页"));return 0;}
        send(source,"居民 "+page+"/"+pages+"页；未加载者不能视为睡眠通过");
        for(var c:residents.subList((page-1)*6,Math.min(page*6,residents.size()))) {
            var e=CitizenProjectionService.findProjection(level,c);var t=c.thermal();
            var line=Component.literal(c.name()+" | "+(c.canWork()?"成人":"儿童")+" | "+(e==null?"未加载":e.isSleeping()?"已上床":"清醒")+" | ")
                    .append(Component.translatable(c.activity().translationKey())).append(Component.literal(String.format(Locale.ROOT," | %.2f℃ / 环境 %.1f℃ / 湿润 %.0f%% | ",t.body,t.ambient,t.wet*100)))
                    .append(t.garment.isEmpty()?Component.literal("无保温衣物"):t.garment.getHoverName().copy().append(Component.literal(" "+(t.garment.getMaxDamage()-t.garment.getDamageValue())+"/"+t.garment.getMaxDamage())))
                    .append(Component.literal(" | "+(e==null?c.lastPosition():e.blockPosition()).toShortString()+" | "+c.life().task+" "+c.life().workTicks+" | "+c.workFailure()));
            source.sendSuccess(()->line,false);
            com.hkin.exiledsettlement.ExiledSettlementMod.LOGGER.info("[resident-climate-view] {}",line.getString());
        }
        return residents.size();
    }
    private static void send(CommandSourceStack source,String line){source.sendSuccess(()->Component.literal(line),false);}
}
