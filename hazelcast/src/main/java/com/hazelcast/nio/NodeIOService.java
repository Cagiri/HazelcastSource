/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.nio;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.SSLConfig;
import com.hazelcast.config.SocketInterceptorConfig;
import com.hazelcast.config.SymmetricEncryptionConfig;
import com.hazelcast.instance.HazelcastThreadGroup;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeState;
import com.hazelcast.instance.OutOfMemoryErrorDispatcher;
import com.hazelcast.internal.ascii.TextCommandService;
import com.hazelcast.internal.properties.GroupProperty;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.tcp.ReadHandler;
import com.hazelcast.nio.tcp.SocketChannelWrapperFactory;
import com.hazelcast.nio.tcp.TcpIpConnection;
import com.hazelcast.nio.tcp.WriteHandler;
import com.hazelcast.spi.EventService;
import com.hazelcast.spi.ExecutionService;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.packetdispatcher.PacketDispatcher;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class NodeIOService implements IOService {

    private final Node node;
    private final NodeEngineImpl nodeEngine;
    private final PacketDispatcher packetDispatcher;

    public NodeIOService(Node node, NodeEngineImpl nodeEngine) {
        this.node = node;
        this.nodeEngine = nodeEngine;
        this.packetDispatcher = nodeEngine.getPacketDispatcher();
    }

    @Override
    public boolean isActive() {
        return node.getState() != NodeState.SHUT_DOWN;
    }

    @Override
    public ILogger getLogger(String name) {
        return node.getLogger(name);
    }

    @Override
    public void onOutOfMemory(OutOfMemoryError oom) {
        OutOfMemoryErrorDispatcher.onOutOfMemory(oom);
    }

    @Override
    public Address getThisAddress() {
        return node.getThisAddress();
    }

    @Override
    public void onFatalError(Exception e) {
        HazelcastThreadGroup threadGroup = node.getHazelcastThreadGroup();
        Thread thread = new Thread(threadGroup.getInternalThreadGroup(), threadGroup.getThreadNamePrefix("io.error.shutdown")) {
            public void run() {
                node.shutdown(false);
            }
        };
        thread.start();
    }

    @Override
    public SocketInterceptorConfig getSocketInterceptorConfig() {
        return node.getConfig().getNetworkConfig().getSocketInterceptorConfig();
    }

    @Override
    public SymmetricEncryptionConfig getSymmetricEncryptionConfig() {
        return node.getConfig().getNetworkConfig().getSymmetricEncryptionConfig();
    }

    @Override
    public SSLConfig getSSLConfig() {
        return node.getConfig().getNetworkConfig().getSSLConfig();
    }

    @Override
    public void handleClientMessage(ClientMessage cm, Connection connection) {
        node.clientEngine.handleClientMessage(cm, connection);
    }

    @Override
    public TextCommandService getTextCommandService() {
        return node.getTextCommandService();
    }

    @Override
    public boolean isMemcacheEnabled() {
        return node.groupProperties.getBoolean(GroupProperty.MEMCACHE_ENABLED);
    }

    @Override
    public boolean isRestEnabled() {
        return node.groupProperties.getBoolean(GroupProperty.REST_ENABLED);
    }

    @Override
    public void removeEndpoint(final Address endPoint) {
        nodeEngine.getExecutionService().execute(ExecutionService.IO_EXECUTOR, new Runnable() {
            @Override
            public void run() {
                node.clusterService.removeAddress(endPoint);
            }
        });
    }

    @Override
    public String getThreadPrefix() {
        HazelcastThreadGroup threadGroup = node.getHazelcastThreadGroup();
        return threadGroup.getThreadPoolNamePrefix("IO");
    }

    @Override
    public ThreadGroup getThreadGroup() {
        HazelcastThreadGroup threadGroup = node.getHazelcastThreadGroup();
        return threadGroup.getInternalThreadGroup();
    }

    @Override
    public void onSuccessfulConnection(Address address) {
        if (!node.joined()) {
            node.getJoiner().unblacklist(address);
        }
    }

    @Override
    public void onFailedConnection(Address address) {
        if (!node.joined()) {
            node.getJoiner().blacklist(address, false);
        }
    }

    @Override
    public void shouldConnectTo(Address address) {
        if (node.getThisAddress().equals(address)) {
            throw new RuntimeException("Connecting to self! " + address);
        }
    }

    @Override
    public boolean isSocketBind() {
        return node.groupProperties.getBoolean(GroupProperty.SOCKET_CLIENT_BIND);
    }

    @Override
    public boolean isSocketBindAny() {
        return node.groupProperties.getBoolean(GroupProperty.SOCKET_CLIENT_BIND_ANY);
    }

    @Override
    public int getSocketReceiveBufferSize() {
        return node.getGroupProperties().getInteger(GroupProperty.SOCKET_RECEIVE_BUFFER_SIZE);
    }

    @Override
    public int getSocketSendBufferSize() {
        return node.getGroupProperties().getInteger(GroupProperty.SOCKET_SEND_BUFFER_SIZE);
    }

    @Override
    public boolean isSocketBufferDirect() {
        return node.getGroupProperties().getBoolean(GroupProperty.SOCKET_BUFFER_DIRECT);
    }

    @Override
    public int getSocketClientReceiveBufferSize() {
        int clientSendBuffer = node.getGroupProperties().getInteger(GroupProperty.SOCKET_CLIENT_RECEIVE_BUFFER_SIZE);
        return clientSendBuffer != -1 ? clientSendBuffer : getSocketReceiveBufferSize();
    }

    @Override
    public int getSocketClientSendBufferSize() {
        int clientReceiveBuffer = node.getGroupProperties().getInteger(GroupProperty.SOCKET_CLIENT_SEND_BUFFER_SIZE);
        return clientReceiveBuffer != -1 ? clientReceiveBuffer : getSocketReceiveBufferSize();
    }

    @Override
    public int getSocketLingerSeconds() {
        return node.getGroupProperties().getSeconds(GroupProperty.SOCKET_LINGER_SECONDS);
    }

    @Override
    public int getSocketConnectTimeoutSeconds() {
        return node.getGroupProperties().getSeconds(GroupProperty.SOCKET_CONNECT_TIMEOUT_SECONDS);
    }

    @Override
    public boolean getSocketKeepAlive() {
        return node.getGroupProperties().getBoolean(GroupProperty.SOCKET_KEEP_ALIVE);
    }

    @Override
    public boolean getSocketNoDelay() {
        return node.getGroupProperties().getBoolean(GroupProperty.SOCKET_NO_DELAY);
    }

    @Override
    public int getInputSelectorThreadCount() {
        return node.groupProperties.getInteger(GroupProperty.IO_INPUT_THREAD_COUNT);
    }

    @Override
    public int getOutputSelectorThreadCount() {
        return node.groupProperties.getInteger(GroupProperty.IO_OUTPUT_THREAD_COUNT);
    }

    @Override
    public void onDisconnect(final Address endpoint) {
    }

    @Override
    public boolean isClient() {
        return false;
    }

    @Override
    public long getConnectionMonitorInterval() {
        return node.groupProperties.getMillis(GroupProperty.CONNECTION_MONITOR_INTERVAL);
    }

    @Override
    public int getConnectionMonitorMaxFaults() {
        return node.groupProperties.getInteger(GroupProperty.CONNECTION_MONITOR_MAX_FAULTS);
    }

    @Override
    public int getBalancerIntervalSeconds() {
        return node.groupProperties.getSeconds(GroupProperty.IO_BALANCER_INTERVAL_SECONDS);
    }

    @Override
    public void executeAsync(final Runnable runnable) {
        nodeEngine.getExecutionService().execute(ExecutionService.IO_EXECUTOR, runnable);
    }

    @Override
    public EventService getEventService() {
        return nodeEngine.getEventService();
    }

    @Override
    public Data toData(Object obj) {
        return nodeEngine.toData(obj);
    }

    @Override
    public Object toObject(Data data) {
        return nodeEngine.toObject(data);
    }

    @Override
    public InternalSerializationService getSerializationService() {
        return node.getSerializationService();
    }

    @Override
    public SocketChannelWrapperFactory getSocketChannelWrapperFactory() {
        return node.getNodeExtension().getSocketChannelWrapperFactory();
    }

    @Override
    public MemberSocketInterceptor getMemberSocketInterceptor() {
        return node.getNodeExtension().getMemberSocketInterceptor();
    }

    @Override
    public ReadHandler createReadHandler(TcpIpConnection connection) {
        return node.getNodeExtension().createReadHandler(connection, this);
    }

    @Override
    public WriteHandler createWriteHandler(TcpIpConnection connection) {
        return node.getNodeExtension().createWriteHandler(connection, this);
    }

    @Override
    public Collection<Integer> getOutboundPorts() {
        final NetworkConfig networkConfig = node.getConfig().getNetworkConfig();
        final Collection<String> portDefinitions = getPortDefinitions(networkConfig);
        final Set<Integer> ports = getPorts(networkConfig);
        if (portDefinitions.isEmpty() && ports.isEmpty()) {
            // means any port
            return Collections.emptySet();
        }
        if (portDefinitions.contains("*") || portDefinitions.contains("0")) {
            // means any port
            return Collections.emptySet();
        }
        transformPortDefinitionsToPorts(portDefinitions, ports);
        if (ports.contains(0)) {
            // means any port
            return Collections.emptySet();
        }
        return ports;
    }

    private void transformPortDefinitionsToPorts(Collection<String> portDefinitions, Set<Integer> ports) {
        // not checking port ranges...
        for (String portDef : portDefinitions) {
            String[] portDefs = portDef.split("[,; ]");
            for (String def : portDefs) {
                def = def.trim();
                if (def.isEmpty()) {
                    continue;
                }
                final int dashPos = def.indexOf('-');
                if (dashPos > 0) {
                    final int start = Integer.parseInt(def.substring(0, dashPos));
                    final int end = Integer.parseInt(def.substring(dashPos + 1));
                    for (int port = start; port <= end; port++) {
                        ports.add(port);
                    }
                } else {
                    ports.add(Integer.parseInt(def));
                }
            }
        }
    }

    private Set<Integer> getPorts(NetworkConfig networkConfig) {
        return networkConfig.getOutboundPorts() == null
                ? new HashSet<Integer>() : new HashSet<Integer>(networkConfig.getOutboundPorts());
    }

    private Collection<String> getPortDefinitions(NetworkConfig networkConfig) {
        return networkConfig.getOutboundPortDefinitions() == null
                ? Collections.<String>emptySet() : networkConfig.getOutboundPortDefinitions();
    }
}

