package com.realtime.common;

public interface RealtimeLog {
    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Throwable throwable);
}
