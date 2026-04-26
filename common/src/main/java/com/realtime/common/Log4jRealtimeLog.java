package com.realtime.common;

import org.apache.logging.log4j.Logger;

public final class Log4jRealtimeLog implements RealtimeLog {
    private final Logger logger;

    public Log4jRealtimeLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(message, args);
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warn(message, args);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
}
