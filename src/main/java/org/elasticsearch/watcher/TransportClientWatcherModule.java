/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher;


import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.SpawnModules;
import org.elasticsearch.watcher.transport.WatcherTransportModule;

import java.util.Collections;


public class TransportClientWatcherModule extends AbstractModule implements SpawnModules {

    @Override
    public Iterable<? extends Module> spawnModules() {
        return Collections.singleton(new WatcherTransportModule());
    }

    @Override
    protected void configure() {
    }

}
