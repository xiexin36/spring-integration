/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.ip.tcp.connection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * /**
 * Implements a server connection factory that produces {@link TcpNioConnection}s using
 * a {@link ServerSocketChannel}. Must have a {@link TcpListener} registered.
 *
 * @author Gary Russell
 * @author Artem Bilan
 * @since 2.0
 */
public class TcpNioServerConnectionFactory extends AbstractServerConnectionFactory {

    private volatile ServerSocketChannel serverChannel;

    private volatile boolean usingDirectBuffers;

    private final Map<SocketChannel, TcpNioConnection> channelMap = new HashMap<SocketChannel, TcpNioConnection>();

    private volatile Selector selector;

    private volatile TcpNioConnectionSupport tcpNioConnectionSupport = new DefaultTcpNioConnectionSupport();

    /**
     * Listens for incoming connections on the port.
     *
     * @param port The port.
     */
    public TcpNioServerConnectionFactory(int port) {
        super(port);
    }

    @Override
    public String getComponentType() {
        return "tcp-nio-server-connection-factory";
    }

    @Override
    public int getPort() {
        int port = super.getPort();
        ServerSocketChannel channel = this.serverChannel;
        if (port == 0 && channel != null) {
            try {
                SocketAddress address = channel.getLocalAddress();
                if (address instanceof InetSocketAddress) {
                    port = ((InetSocketAddress) address).getPort();
                }
            } catch (IOException e) {
                logger.error("Error getting port", e);
            }
        }
        return port;
    }

    @Override
    @Nullable
    public SocketAddress getServerSocketAddress() {
        if (this.serverChannel != null) {
            try {
                return this.serverChannel.getLocalAddress();
            } catch (IOException e) {
                logger.error("Error getting local address", e);
            }
        }
        return null;
    }

    /**
     * If no listener registers, exits.
     * Accepts incoming connections and creates TcpConnections for each new connection.
     * Invokes {{@link #initializeConnection(TcpConnectionSupport, Socket)} and executes the
     * connection {@link TcpConnection#run()} using the task executor.
     * I/O errors on the server socket/channel are logged and the factory is stopped.
     */
    @Override
    public void run() {
        if (getListener() == null) {
            logger.info(this + " No listener bound to server connection factory; will not read; exiting...");
            return;
        }
        try {
            this.serverChannel = ServerSocketChannel.open();
            int port = super.getPort();
            getTcpSocketSupport().postProcessServerSocket(this.serverChannel.socket());
            this.serverChannel.configureBlocking(false);
            if (getLocalAddress() == null) {
                this.serverChannel.socket().bind(new InetSocketAddress(port), Math.abs(getBacklog()));
            } else {
                InetAddress whichNic = InetAddress.getByName(getLocalAddress());
                this.serverChannel.socket().bind(new InetSocketAddress(whichNic, port), Math.abs(getBacklog()));
            }
            if (logger.isInfoEnabled()) {
                logger.info(this + " Listening");
            }
            final Selector theSelector = Selector.open();
            if (this.serverChannel == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug(this + " stopped before registering the server channel");
                }
            } else {
                this.serverChannel.register(theSelector, SelectionKey.OP_ACCEPT);
                setListening(true);
                publishServerListeningEvent(getPort());
                this.selector = theSelector;
                doSelect(this.serverChannel, theSelector);
            }
        } catch (IOException e) {
            if (isActive()) {
                logger.error("Error on ServerChannel; port = " + getPort(), e);
                publishServerExceptionEvent(e);
            }
            stop();
        } finally {
            setListening(false);
        }
    }

    /**
     * Listens for incoming connections and for notifications that a connected
     * socket is ready for reading.
     * Accepts incoming connections, registers the new socket with the
     * selector for reading.
     * When a socket is ready for reading, unregisters the read interest and
     * schedules a call to doRead which reads all available data. When the read
     * is complete, the socket is again registered for read interest.
     *
     * @param server           the ServerSocketChannel to select
     * @param selectorToSelect the Selector multiplexor
     * @throws IOException
     */
    private void doSelect(ServerSocketChannel server, final Selector selectorToSelect) throws IOException {
        while (isActive()) {
            int soTimeout = getSoTimeout();
            int selectionCount = 0;
            try {
                long timeout = soTimeout < 0 ? 0 : soTimeout;
                if (getDelayedReads().size() > 0 && (timeout == 0 || getReadDelay() < timeout)) {
                    timeout = getReadDelay();
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("Delayed reads: " + getDelayedReads().size() + " timeout " + timeout);
                }
                selectionCount = selectorToSelect.select(timeout);
                processNioSelections(selectionCount, selectorToSelect, server, this.channelMap);
            } catch (@SuppressWarnings("unused") CancelledKeyException cke) {
                logger.debug("CancelledKeyException during Selector.select()");
            } catch (ClosedSelectorException cse) {
                if (isActive()) {
                    logger.error("Selector closed", cse);
                    publishServerExceptionEvent(cse);
                    break;
                }
            }
        }
    }

    /**
     * @param selectorForNewSocket The selector.
     * @param server               The server socket channel.
     * @param now                  The current time.
     */
    @Override
    protected void doAccept(final Selector selectorForNewSocket, ServerSocketChannel server, long now) {
        logger.debug("New accept");
        try {
            // accept connections in a for loop until no new connection is ready
            // when many new connections arrive.
            for (;;){
                SocketChannel channel = server.accept();
                if(channel == null)
                    break;
                if (isShuttingDown()) {
                    if (logger.isInfoEnabled()) {
                        logger.info("New connection from " + channel.socket().getInetAddress().getHostAddress()
                                + ":" + channel.socket().getPort()
                                + " rejected; the server is in the process of shutting down.");
                    }
                    channel.close();
                } else {
                    try {
                        channel.configureBlocking(false);
                        Socket socket = channel.socket();
                        setSocketAttributes(socket);
                        TcpNioConnection connection = createTcpNioConnection(channel);
                        if (connection == null) {
                            return;
                        }
                        connection.setTaskExecutor(getTaskExecutor());
                        connection.setLastRead(now);
                        if (getSslHandshakeTimeout() != null && connection instanceof TcpNioSSLConnection) {
                            ((TcpNioSSLConnection) connection).setHandshakeTimeout(getSslHandshakeTimeout());
                        }
                        this.channelMap.put(channel, connection);
                        channel.register(selectorForNewSocket, SelectionKey.OP_READ, connection);
                        connection.publishConnectionOpenEvent();
                    } catch (IOException e) {
                        logger.error("Exception accepting new connection from "
                                + channel.socket().getInetAddress().getHostAddress()
                                + ":" + channel.socket().getPort(), e);
                        channel.close();
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nullable
    private TcpNioConnection createTcpNioConnection(SocketChannel socketChannel) {
        try {
            TcpNioConnection connection = this.tcpNioConnectionSupport.createNewConnection(socketChannel, true,
                    isLookupHost(), getApplicationEventPublisher(), getComponentName());
            connection.setUsingDirectBuffers(this.usingDirectBuffers);
            TcpConnectionSupport wrappedConnection = wrapConnection(connection);
            initializeConnection(wrappedConnection, socketChannel.socket());
            return connection;
        } catch (Exception e) {
            logger.error("Failed to establish new incoming connection", e);
            return null;
        }
    }

    @Override
    public void stop() {
        setActive(false);
        if (this.selector != null) {
            try {
                this.selector.close();
            } catch (Exception e) {
                logger.error("Error closing selector", e);
            }
        }
        if (this.serverChannel != null) {
            try {
                this.serverChannel.close();
            } catch (IOException e) {
            } finally {
                this.serverChannel = null;
            }
        }

        super.stop();
    }

    public void setUsingDirectBuffers(boolean usingDirectBuffers) {
        this.usingDirectBuffers = usingDirectBuffers;
    }

    public void setTcpNioConnectionSupport(TcpNioConnectionSupport tcpNioSupport) {
        Assert.notNull(tcpNioSupport, "TcpNioSupport must not be null");
        this.tcpNioConnectionSupport = tcpNioSupport;
    }

    /**
     * @return the serverChannel
     */
    protected ServerSocketChannel getServerChannel() {
        return this.serverChannel;
    }

    /**
     * @return the usingDirectBuffers
     */
    protected boolean isUsingDirectBuffers() {
        return this.usingDirectBuffers;
    }

    /**
     * @return the connections
     */
    protected Map<SocketChannel, TcpNioConnection> getConnections() {
        return this.channelMap;
    }
}
