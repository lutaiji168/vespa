// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.service.monitor;

import com.yahoo.config.model.api.ApplicationInfo;
import com.yahoo.config.model.api.SuperModel;
import com.yahoo.config.model.api.SuperModelListener;
import com.yahoo.config.model.api.SuperModelProvider;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.service.monitor.internal.LatencyMeasurement;
import com.yahoo.vespa.service.monitor.internal.ServiceMonitorMetrics;

import java.util.List;
import java.util.logging.Logger;

public class SuperModelListenerImpl implements SuperModelListener {
    private static final Logger logger = Logger.getLogger(SuperModelListenerImpl.class.getName());

    private final ServiceMonitorMetrics metrics;
    private final ModelGenerator modelGenerator;

    // superModel and slobrokMonitorManager are always updated together
    // and atomically using this monitor.
    private final Object monitor = new Object();
    private final SlobrokMonitorManager slobrokMonitorManager;
    private SuperModel superModel;

    SuperModelListenerImpl(SlobrokMonitorManager slobrokMonitorManager,
                           ServiceMonitorMetrics metrics,
                           ModelGenerator modelGenerator) {
        this.slobrokMonitorManager = slobrokMonitorManager;
        this.metrics = metrics;
        this.modelGenerator = modelGenerator;
    }

    void start(SuperModelProvider superModelProvider) {
        synchronized (monitor) {
            // This snapshot() call needs to be within the synchronized block,
            // since applicationActivated()/applicationRemoved() may be called
            // asynchronously even before snapshot() returns.
            SuperModel snapshot = superModelProvider.snapshot(this);

            snapshot.getAllApplicationInfos().stream().forEach(application ->
                    applicationActivated(snapshot, application));
        }
    }

    @Override
    public void applicationActivated(SuperModel superModel, ApplicationInfo application) {
        synchronized (monitor) {
            this.superModel = superModel;
            slobrokMonitorManager.applicationActivated(superModel, application);
        }
    }

    @Override
    public void applicationRemoved(SuperModel superModel, ApplicationId id) {
        synchronized (monitor) {
            this.superModel = superModel;
            slobrokMonitorManager.applicationRemoved(superModel, id);
        }
    }

    ServiceModel createServiceModelSnapshot(Zone zone, List<String> configServerHostnames) {
        try (LatencyMeasurement measurement = metrics.startServiceModelSnapshotLatencyMeasurement()) {
            // Reference 'measurement' in a dummy statement, otherwise the compiler
            // complains about "auto-closeable resource is never referenced in body of
            // corresponding try statement". Why hasn't javac fixed this!?
            dummy(measurement);

            // WARNING: The slobrok monitor manager may be out-of-sync with super model (no locking)
            return modelGenerator.toServiceModel(
                    superModel,
                    zone,
                    configServerHostnames,
                    slobrokMonitorManager);
        }
    }

    private void dummy(LatencyMeasurement measurement) {}
}