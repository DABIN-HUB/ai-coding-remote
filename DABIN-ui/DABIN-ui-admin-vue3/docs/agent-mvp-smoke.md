# AI Coding MVP Smoke

本文档只说明 Phase 3B MVP Smoke 所需的前端到 Relay WebSocket 部署方式。

## Browser -> Relay WebSocket

前端实时链路复用现有 `WsEnvelope<AgentEvent>` 协议，不新增 `/agent/chat/ws` 或其他 WebSocket。

前端 WebSocket 地址选择顺序：

1. 如果配置了 `VITE_AGENT_RELAY_WS_URL`，直接使用该地址。
2. 否则使用当前页面同源地址，并连接 `/agent/ws`。

## 生产推荐模式

Browser 只访问平台域名：

```text
wss://platform.example.com/agent/ws
```

Nginx 将 `/agent/ws` 转发到 Relay 内网地址，例如：

```nginx
location /agent/ws {
    proxy_pass http://DABIN-agent-relay:48180/agent/ws;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

这种模式下 Browser 不需要知道 Relay 内部端口。

## 开发模式

如果前端开发服务器和 Relay 不同源，可以在本地 `.env.local` 或启动环境中配置：

```text
VITE_AGENT_RELAY_WS_URL='ws://127.0.0.1:48180/agent/ws'
```

不要把本地 Relay 地址写入业务源码。

## Smoke 检查点

1. 登录后进入 `/agent/coding`。
2. 调用 `/agent/relay/createUserTicket` 获取 USER Relay Ticket。
3. Browser 连接 `/agent/ws` 或 `VITE_AGENT_RELAY_WS_URL`。
4. Browser 发送 `HELLO`。
5. Relay 返回 `WELCOME`。
6. 后续 `WsEnvelope<AgentEvent>` 只按 authenticated ticket 的 tenant/user fanout。
