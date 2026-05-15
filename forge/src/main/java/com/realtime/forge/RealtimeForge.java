package com.realtime.forge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeController;
import com.realtime.common.RealtimeLog;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(RealtimeConstants.MOD_ID)
public final class RealtimeForge {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);

    private final RealtimeController controller;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean serverWorkQueued = new AtomicBoolean(false);

    private MinecraftServer activeServer;

    public RealtimeForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        controller = new RealtimeController(configDir, LOG);

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RealtimeSync-Forge-Ticker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::queueServerTick, 50L, 50L, TimeUnit.MILLISECONDS);

        LOGGER.info("{} loaded for Forge. Config: {}", RealtimeConstants.MOD_NAME, controller.configPath().toAbsolutePath());
    }

    private void queueServerTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (activeServer != null) {
                controller.onServerStopped(activeServer);
            }
            activeServer = null;
            serverWorkQueued.set(false);
            return;
        }

        if (!serverWorkQueued.compareAndSet(false, true)) {
            return;
        }

        server.execute(() -> {
            try {
                handleServerTick(server);
            } finally {
                serverWorkQueued.set(false);
            }
        });
    }

    private void handleServerTick(MinecraftServer server) {
        if (activeServer != server) {
            activeServer = server;
            controller.onServerStarted(server);
            return;
        }

        controller.onServerTick(server);
    }
}
