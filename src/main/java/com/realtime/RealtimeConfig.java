package com.realtime;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment;

@Config(name = "realtime")
public class RealtimeConfig implements ConfigData {

    @Comment("Time update interval in ticks. 20 ticks = 1 second.")
    public int updateInterval = 60;

    @Comment("Offset from system time in hours (-23 to +23).")
    public int offsetHours = 0;

    @Comment("Duration of a full in-game day in real-world minutes. Set to 0 to use real time.")
    public int customDayLengthMinutes = 0;
}

