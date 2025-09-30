package com.albunyaan.tube.downloads;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class DownloadMetricsBinder implements MeterBinder {

    private final DownloadActivityMonitor monitor;

    public DownloadMetricsBinder(DownloadActivityMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("downloads.active.count", monitor, DownloadActivityMonitor::getActiveDownloads);
    }
}
