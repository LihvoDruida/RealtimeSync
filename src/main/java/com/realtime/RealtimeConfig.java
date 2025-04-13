package com.realtime;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = "realtime")
public class RealtimeConfig implements ConfigData {

    @Comment("Частота оновлення часу (в тиках). 20 тік = 1 секунда.")
    public int updateInterval = 60;

    @Comment("Зсув у годинах відносно системного часу (-23 до +23)")
    public int offsetHours = 0;
}

