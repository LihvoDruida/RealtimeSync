package com.realtime;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.Toml4jConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalTime;

public class TimeControl implements ModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Realtime");
	private int tickCounter = 0;
	private long customTicks = 0L;
	public static RealtimeConfig CONFIG;

	@Override
	public void onInitialize() {
		AutoConfig.register(RealtimeConfig.class, Toml4jConfigSerializer::new);
		CONFIG = AutoConfig.getConfigHolder(RealtimeConfig.class).getConfig();

		ServerWorldEvents.LOAD.register(this::DisableDoDaylightCycle);
		ServerTickEvents.START_SERVER_TICK.register(this::SetTimeToRealtime);
		LOGGER.info("Successfully loaded Realtime");
	}

	public void DisableDoDaylightCycle(MinecraftServer server, ServerWorld _w) {
		server.getWorlds().forEach(world -> {
			world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
		});
		tickCounter = CONFIG.updateInterval - 1;
		SetTimeToRealtime(server);
	}

	public void SetTimeToRealtime(MinecraftServer server) {
		tickCounter++;
		if (tickCounter >= CONFIG.updateInterval) {
			try {
				ServerWorld world = server.getOverworld();
				if (world != null) {
					if (CONFIG.customDayLengthMinutes == 0) {
						LocalTime systemTime = LocalTime.now().plusHours(CONFIG.offsetHours);
						long secondsFromSixAM = systemTime.getSecond() + systemTime.getMinute() * 60 + (systemTime.getHour() - 6) * 3600;
						long ticks = (Math.round(secondsFromSixAM * 24000 / 86400.0) + 24000) % 24000;
						world.setTimeOfDay(ticks);
					} else {
						long ticksPerMinute = 20 * 60;
						long totalDayTicks = CONFIG.customDayLengthMinutes * ticksPerMinute;
						customTicks = (customTicks + CONFIG.updateInterval) % totalDayTicks;
						long worldTime = (customTicks * 24000 / totalDayTicks) % 24000;
						world.setTimeOfDay(worldTime);
					}
				} else {
					throw new Error("Failed to get overworld pointer");
				}
			} catch (Exception e) {
				LOGGER.error("Failed to set time of day", e);
			}
			tickCounter = 0;
		}
	}
}
