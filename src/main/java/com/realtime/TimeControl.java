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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeControl implements ModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Realtime");
	private int tickCounter = 0;
	private double customTicks = 0.0;
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
		if (tickCounter < CONFIG.updateInterval) {
			return;
		}
		try {
			ServerWorld  world = server.getOverworld();
			if (world == null) {
				throw new Error("Failed to get overworld pointer");
			}

			if (CONFIG.customDayLengthMinutes == 0) {
				ZoneId zoneId = ZoneId.systemDefault(); // Використовуйте часову зону сервера
				ZonedDateTime systemTime = ZonedDateTime.now(zoneId).plusHours(CONFIG.offsetHours);
				LocalDateTime localTime = systemTime.toLocalDateTime();

				int effectiveHour = ((localTime.getHour() - 6 + 24) % 24);
				long secondsFromSixAM = effectiveHour * 3600L
						+ localTime.getMinute() * 60L
						+ localTime.getSecond();
				long ticks = (Math.round(secondsFromSixAM * 24000 / 86400.0) + 24000) % 24000;

				world.setTimeOfDay(ticks);
				LOGGER.debug("Real Time Mode - Real Time: {}, Game Ticks: {}", localTime, ticks);
			} else {
				double addition = (double) CONFIG.updateInterval * 24000 / (CONFIG.customDayLengthMinutes * 60 * 20);
				customTicks = (customTicks + addition) % 24000.0;
				long worldTime = (long) customTicks;

				world.setTimeOfDay(worldTime);
				LOGGER.debug("Custom Time Mode - Custom Ticks: {}, Game Ticks: {}", customTicks, worldTime);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to set time of day", e);
		} finally {
			tickCounter = 0;
		}
	}

}
