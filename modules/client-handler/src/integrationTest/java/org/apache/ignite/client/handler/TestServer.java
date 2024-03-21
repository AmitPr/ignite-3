/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.client.handler;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.client.handler.configuration.ClientConnectorConfiguration;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.cluster.management.ClusterTag;
import org.apache.ignite.internal.cluster.management.network.messages.CmgMessagesFactory;
import org.apache.ignite.internal.compute.IgniteComputeInternal;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.metrics.MetricManager;
import org.apache.ignite.internal.network.ClusterService;
import org.apache.ignite.internal.network.NettyBootstrapFactory;
import org.apache.ignite.internal.network.configuration.NetworkConfiguration;
import org.apache.ignite.internal.placementdriver.PlacementDriver;
import org.apache.ignite.internal.security.authentication.AuthenticationManager;
import org.apache.ignite.internal.security.authentication.AuthenticationManagerImpl;
import org.apache.ignite.internal.security.configuration.SecurityConfiguration;
import org.apache.ignite.internal.sql.engine.QueryProcessor;
import org.apache.ignite.internal.table.IgniteTablesInternal;
import org.apache.ignite.internal.table.TestLowWatermark;
import org.apache.ignite.internal.table.distributed.schema.AlwaysSyncedSchemaSyncService;
import org.apache.ignite.internal.tx.impl.IgniteTransactionsImpl;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mockito;

/** Test server that can be started with SSL configuration. */
public class TestServer {
    private NettyBootstrapFactory bootstrapFactory;

    private final TestSslConfig testSslConfig;

    private final AuthenticationManager authenticationManager;

    private final ClientHandlerMetricSource metrics = new ClientHandlerMetricSource();

    private final CmgMessagesFactory msgFactory = new CmgMessagesFactory();

    private final ClientConnectorConfiguration clientConnectorConfiguration;

    private final NetworkConfiguration networkConfiguration;

    private long idleTimeout = 5000;

    public TestServer(ClientConnectorConfiguration clientConnectorConfiguration, NetworkConfiguration networkConfiguration) {
        this(null, null, clientConnectorConfiguration, networkConfiguration);
    }

    TestServer(
            @Nullable TestSslConfig testSslConfig,
            @Nullable SecurityConfiguration securityConfiguration,
            ClientConnectorConfiguration clientConnectorConfiguration,
            NetworkConfiguration networkConfiguration
    ) {
        this.testSslConfig = testSslConfig;
        this.authenticationManager = securityConfiguration == null
                ? new DummyAuthenticationManager()
                : new AuthenticationManagerImpl(securityConfiguration);
        this.clientConnectorConfiguration = clientConnectorConfiguration;
        this.networkConfiguration = networkConfiguration;

        metrics.enable();
    }

    void idleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    void tearDown() throws Exception {
        bootstrapFactory.stop();
    }

    ClientHandlerModule start(TestInfo testInfo) {
        authenticationManager.start();

        clientConnectorConfiguration.change(
                local -> local
                        .changePort(10800)
                        .changeIdleTimeout(idleTimeout)
        ).join();

        if (testSslConfig != null) {
            clientConnectorConfiguration.ssl().enabled().update(true).join();
            clientConnectorConfiguration.ssl().keyStore().path().update(testSslConfig.keyStorePath()).join();
            clientConnectorConfiguration.ssl().keyStore().password().update(testSslConfig.keyStorePassword()).join();
        }

        bootstrapFactory = new NettyBootstrapFactory(networkConfiguration, testInfo.getDisplayName());

        bootstrapFactory.start();

        ClusterService clusterService = mock(ClusterService.class, RETURNS_DEEP_STUBS);
        Mockito.when(clusterService.topologyService().localMember().id()).thenReturn("id");
        Mockito.when(clusterService.topologyService().localMember().name()).thenReturn("consistent-id");

        var module = new ClientHandlerModule(
                mock(QueryProcessor.class),
                mock(IgniteTablesInternal.class),
                mock(IgniteTransactionsImpl.class),
                mock(IgniteComputeInternal.class),
                clusterService,
                bootstrapFactory,
                () -> CompletableFuture.completedFuture(ClusterTag.clusterTag(msgFactory, "Test Server")),
                mock(MetricManager.class),
                metrics,
                authenticationManager,
                new HybridClockImpl(),
                new AlwaysSyncedSchemaSyncService(),
                mock(CatalogService.class),
                mock(PlacementDriver.class),
                clientConnectorConfiguration,
                new TestLowWatermark()
        );

        module.start().join();

        return module;
    }

    ClientHandlerMetricSource metrics() {
        return metrics;
    }
}
