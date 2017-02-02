/*
 *  Copyright (c) 2017 WSO2 Inc. (http://wso2.com) All Rights Reserved.
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
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.transport.http.netty.listener;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AsciiString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.BufferFactory;
import org.wso2.carbon.messaging.CarbonTransportInitializer;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.common.ssl.SSLHandlerFactory;
import org.wso2.carbon.transport.http.netty.config.ListenerConfiguration;
import org.wso2.carbon.transport.http.netty.config.RequestSizeValidationConfiguration;
import org.wso2.carbon.transport.http.netty.config.TransportProperty;
import org.wso2.carbon.transport.http.netty.listener.http2.HTTP2SourceHandlerBuilder;
import org.wso2.carbon.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A class that responsible for create server side channels.
 */
public class HTTPServerChannelInitializer extends ChannelInitializer<SocketChannel>
        implements CarbonTransportInitializer {

    private static final Logger log = LoggerFactory.getLogger(HTTPServerChannelInitializer.class);
    private ConnectionManager connectionManager;

    private Map<Integer, ListenerConfiguration> listenerConfigurationMap = new HashMap<>();

    public HTTPServerChannelInitializer() {
    }

    public void registerListenerConfig(ListenerConfiguration listenerConfiguration) {
        listenerConfigurationMap.put(listenerConfiguration.getPort(), listenerConfiguration);
    }

    public void unRegisterListenerConfig(ListenerConfiguration listenerConfiguration) {
        listenerConfigurationMap.remove(listenerConfiguration.getPort());
    }

    @Override
    //TODO: Check the usage of this
    public void setup(Map<String, String> parameters) {
        if (parameters != null && parameters.get(Constants.OUTPUT_CONTENT_BUFFER_SIZE) != null) {
            BufferFactory.createInstance(Integer.parseInt(parameters.get(Constants.OUTPUT_CONTENT_BUFFER_SIZE)));
        }
    }

    public void setupConnectionManager(Set<TransportProperty> transportPropertySet) {
        try {
            connectionManager = ConnectionManager.getInstance(transportPropertySet);
        } catch (Exception e) {
            log.error("Error initializing the transport ", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Initializing source channel pipeline");
        }
        int port = ch.localAddress().getPort();
        ListenerConfiguration listenerConfiguration = listenerConfigurationMap.get(port);
        // Check listener has enable https
        if (listenerConfiguration.getSslConfig() != null) {
            /**
             * If listener has enable HTTP2 over TLS , HTTP2 use Application Layer Protocol Negotiation (ALPN)
             * protocol for decide which protocol version needs to use over SSL.
             */
            if (listenerConfiguration.isHttp2TLS()) {
                SslContext sslContext = new SSLHandlerFactory(listenerConfiguration.getSslConfig())
                        .createHttp2TLSContext();
                // ALPN handler will be added to find http protocol version
                configureHttp2upgrade(ch, listenerConfiguration, sslContext);
            } else {
                // If ALPN protocol has not been enables, HTTP will be used over SSL.
                SslHandler sslHandler = new SSLHandlerFactory(listenerConfiguration.getSslConfig()).create();
                ch.pipeline().addLast("ssl", sslHandler);
                // Configure the pipeline for HTTP handlers.
                configureHTTPPipeline(ch, listenerConfiguration, connectionManager);
            }
        } else {
            // If SSL not enabled HTTP1 > HTTP2 upgrade handler will be configured
            configureHttp2upgrade(ch, listenerConfiguration);
        }
    }


    /**
     * Configure the pipeline for TLS NPN negotiation to HTTP/2.
     * @param ch Channel
     * @param listenerConfiguration Listener Configuration
     * @param sslContext Netty http2 ALPN SSL context
     */
    private void configureHttp2upgrade(SocketChannel ch, ListenerConfiguration listenerConfiguration, SslContext
            sslContext) {
        ChannelPipeline p = ch.pipeline();
        p.addLast("ssl", sslContext.newHandler(ch.alloc()));
        p.addLast("http-upgrade", new HTTPProtocolNegotiationHandler(connectionManager,
                listenerConfiguration));

    }

    /**
     * Configure the pipeline for a cleartext upgrade from HTTP to HTTP/2.0
     * @param ch Channel
     * @param listenerConfiguration Listener Configuration
     */
    private void configureHttp2upgrade(SocketChannel ch, ListenerConfiguration listenerConfiguration) {
        ChannelPipeline p = ch.pipeline();
        final HttpServerCodec sourceCodec = new HttpServerCodec();
        final HttpServerUpgradeHandler.UpgradeCodecFactory upgradeCodecFactory = protocol -> {
            if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                return new Http2ServerUpgradeCodec("http2-handler", new
                        HTTP2SourceHandlerBuilder(connectionManager, listenerConfiguration).build());
            } else {
                return null;
            }
        };
        p.addLast("encoder", sourceCodec);
        p.addLast("http2-upgrade", new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory));
        /**
         * Requests will be propagated to following handlers if no upgrade has been attempted and the client is just
         * talking HTTP.
         */
        configureHTTPPipeline(ch, listenerConfiguration, connectionManager);
    }

    /**
     * Configure the pipeline if user sent HTTP requests
     * @param ch Channel
     * @param listenerConfiguration Listener Configuration
     * @param connectionManager Connection Manager
     */
    public static void configureHTTPPipeline(SocketChannel ch, ListenerConfiguration listenerConfiguration,
                                             ConnectionManager connectionManager) {
        ChannelPipeline p = ch.pipeline();
        if (RequestSizeValidationConfiguration.getInstance().isHeaderSizeValidation()) {
            p.addLast("decoder", new CustomHttpRequestDecoder());
        } else {
            p.addLast("decoder", new HttpRequestDecoder());
        }
        if (RequestSizeValidationConfiguration.getInstance().isRequestSizeValidation()) {
            p.addLast("custom-aggregator", new CustomHttpObjectAggregator());
        }
        p.addLast("compressor", new HttpContentCompressor());
        p.addLast("chunkWriter", new ChunkedWriteHandler());
        try {
            p.addLast("handler", new SourceHandler(connectionManager, listenerConfiguration));
        } catch (Exception e) {
            log.error("Cannot Create SourceHandler ", e);
        }
    }

    @Override
    public boolean isServerInitializer() {
        return true;
    }

}