package com.wangbin.ai.agent.daemon.cloud.relay;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface RelayWebSocketConnector {

    CompletableFuture<WebSocket> connect(URI relayUri, WebSocket.Listener listener);

}
