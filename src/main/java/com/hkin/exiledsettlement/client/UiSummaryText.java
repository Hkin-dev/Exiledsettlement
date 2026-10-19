package com.hkin.exiledsettlement.client;

import java.util.ArrayList;
import java.util.List;

/** Presentation-only filtering of known introductory prose; engineering sections stay intact. */
public final class UiSummaryText {
    private UiSummaryText() { }

    private static boolean explanation(String line) {
        return line.startsWith("所有聚落使用共享世界历法；")
                || line.startsWith("保留线限制烹饪、流民和易货，")
                || line.startsWith("餐厅由空闲且未锁定的工人")
                || line.startsWith("随身口粮目标 ")
                || line.startsWith("个人产物在休息时交货；")
                || line.startsWith("饱食恢复生命：");
    }

    public static List<String> compact(List<String> input) {
        var output = new ArrayList<String>();
        boolean section = false;
        for (String line : input) {
            if (line.startsWith("——")) section = true;
            // Do not hide failures, stock shortages, events or household records.
            if (section) { output.add(line); continue; }
            if (line.startsWith("居民名称组合库：") || explanation(line)
                    || line.matches("第\\d+年 / 世界第\\d+日 / .*")) continue;
            if (line.startsWith("小麦当量 ")) line = line.split("；", 2)[0];
            else if (line.startsWith("仓库管理员 ")) line = line.split("；", 2)[0];
            else if (line.startsWith("衣物缺口 ")) line = line.split("件；", 2)[0] + "件";
            else if (line.startsWith("成年居民持续工作；") && line.contains("生育：")) line = line.substring(line.indexOf("生育："));
            else if (line.startsWith("政策：")) line = line.split("。", 2)[0];
            else if (line.startsWith("暂无流民家庭申请；")) line = "暂无流民家庭申请";
            else if (line.startsWith("商队易货：")) continue; // Prices are shown on the trade actions.
            output.add(line);
        }
        return List.copyOf(output);
    }

    public static List<String> help(List<String> input) {
        var output = new ArrayList<String>();
        for (String line : input) {
            if (line.startsWith("——")) break;
            if (explanation(line) || line.startsWith("衣物缺口 ")
                    || line.startsWith("成年居民持续工作；") || line.startsWith("仓库管理员 ")
                    || line.startsWith("商队易货：") || line.startsWith("暂无流民家庭申请；")) output.add(line);
        }
        return List.copyOf(output);
    }
}
