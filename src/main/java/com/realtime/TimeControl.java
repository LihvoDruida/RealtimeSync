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
	private int tickCounter = 0; // Лічильник тика
	public static RealtimeConfig CONFIG; // Зберігаємо конфігурацію

	@Override
	public void onInitialize() {
		// Ініціалізація AutoConfig для завантаження конфігурацій
		AutoConfig.register(RealtimeConfig.class, Toml4jConfigSerializer::new);
		CONFIG = AutoConfig.getConfigHolder(RealtimeConfig.class).getConfig();

		// Реєстрація подій
		ServerWorldEvents.LOAD.register(this::DisableDoDaylightCycle);
		ServerTickEvents.START_SERVER_TICK.register(this::SetTimeToRealtime);
		LOGGER.info("Successfully loaded Realtime");
	}

	// Вимкнення циклу дня та ночі в світі
	public void DisableDoDaylightCycle(MinecraftServer server, ServerWorld _w) {
		server.getWorlds().forEach(world -> {
			world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
		});
		tickCounter = CONFIG.updateInterval - 1; // Скидаємо лічильник
		SetTimeToRealtime(server); // Оновлюємо час одразу при завантаженні світу
	}

	// Метод для синхронізації часу з реальним
	public void SetTimeToRealtime(MinecraftServer server) {
		tickCounter++; // Збільшуємо лічильник тика
		if (tickCounter >= CONFIG.updateInterval) { // Якщо досягнуто інтервал
			try {
				ServerWorld world = server.getOverworld(); // Отримуємо світ
				if (world != null) {
					// Отримуємо поточний час системи і додаємо зміщення
					LocalTime systemTime = LocalTime.now().plusHours(CONFIG.offsetHours);
					long secondsFromSixAM = systemTime.getSecond() + systemTime.getMinute() * 60 + (systemTime.getHour() - 6) * 3600;
					long ticks = (Math.round(secondsFromSixAM * 24000 / 86400.0) + 24000) % 24000; // Перетворюємо в тики
					world.setTimeOfDay(ticks); // Оновлюємо час світу
				} else {
					throw new Error("Failed to get overworld pointer");
				}
			} catch (Exception e) {
				LOGGER.error("Failed to set time of day", e);
			}

			tickCounter = 0; // Скидаємо лічильник після оновлення
		}
	}
}
