package com.albunyaan.tube.downloads;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class DownloadActivityMonitor {

    private final AtomicInteger activeDownloads = new AtomicInteger(0);

    public void increment() {
        activeDownloads.incrementAndGet();
    }

    public void decrement() {
        activeDownloads.updateAndGet(current -> Math.max(0, current - 1));
    }

    public int getActiveDownloads() {
        return activeDownloads.get();
    }
}
