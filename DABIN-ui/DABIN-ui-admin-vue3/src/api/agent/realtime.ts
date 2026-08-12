import { createUserTicket } from './index'
import type { AgentEvent, WsEnvelope } from './types'

const PROTOCOL_VERSION = '0.1'
const MAX_RECONNECT_DELAY = 10_000

export type AgentRealtimeStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'

type AgentEventListener = (event: AgentEvent) => void
type StatusListener = (status: AgentRealtimeStatus) => void

export class AgentRealtimeClient {
  private socket?: WebSocket
  private closedByUser = false
  private reconnectAttempts = 0
  private eventListeners = new Set<AgentEventListener>()
  private statusListeners = new Set<StatusListener>()
  private status: AgentRealtimeStatus = 'DISCONNECTED'

  connect() {
    this.closedByUser = false
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return
    }
    void this.openSocket()
  }

  disconnect() {
    this.closedByUser = true
    this.socket?.close()
    this.socket = undefined
    this.setStatus('DISCONNECTED')
  }

  onEvent(listener: AgentEventListener) {
    this.eventListeners.add(listener)
    return () => this.eventListeners.delete(listener)
  }

  onStatus(listener: StatusListener) {
    this.statusListeners.add(listener)
    listener(this.status)
    return () => this.statusListeners.delete(listener)
  }

  private async openSocket() {
    this.setStatus(this.reconnectAttempts > 0 ? 'RECONNECTING' : 'CONNECTING')
    try {
      const ticket = await createUserTicket()
      const socket = new WebSocket(this.websocketUrl())
      this.socket = socket
      socket.onopen = () => {
        this.sendEnvelope({
          type: 'HELLO',
          protocolVersion: PROTOCOL_VERSION,
          payload: {
            protocolVersion: PROTOCOL_VERSION,
            relayTicket: ticket.ticket
          }
        })
      }
      socket.onmessage = (message) => this.handleMessage(String(message.data))
      socket.onclose = () => this.handleClose()
      socket.onerror = () => {
        socket.close()
      }
    } catch {
      this.handleClose()
    }
  }

  private websocketUrl() {
    const configured = import.meta.env.VITE_AGENT_RELAY_WS_URL
    if (configured) {
      return configured
    }
    const base = import.meta.env.VITE_BASE_URL || window.location.origin
    return `${base.replace(/^http/, 'ws')}/agent/ws`
  }

  private sendEnvelope(envelope: Partial<WsEnvelope>) {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(
        JSON.stringify({
          protocolVersion: PROTOCOL_VERSION,
          timestamp: new Date().toISOString(),
          ...envelope
        })
      )
    }
  }

  private handleMessage(raw: string) {
    let envelope: WsEnvelope
    try {
      envelope = JSON.parse(raw)
    } catch {
      return
    }
    if (envelope.type === 'WELCOME') {
      this.reconnectAttempts = 0
      this.setStatus('CONNECTED')
      return
    }
    if (envelope.type === 'PING') {
      const payload = envelope.payload as { pingId?: string } | undefined
      this.sendEnvelope({
        type: 'PONG',
        payload: { pingId: payload?.pingId, clientTime: new Date().toISOString() }
      })
      return
    }
    if (envelope.type === 'AGENT_EVENT' && envelope.payload) {
      const event = envelope.payload as AgentEvent
      this.eventListeners.forEach((listener) => listener(event))
    }
  }

  private handleClose() {
    this.socket = undefined
    if (this.closedByUser) {
      this.setStatus('DISCONNECTED')
      return
    }
    this.reconnectAttempts += 1
    this.setStatus('RECONNECTING')
    window.setTimeout(() => this.openSocket(), this.reconnectDelay())
  }

  private reconnectDelay() {
    return Math.min(MAX_RECONNECT_DELAY, 500 * 2 ** Math.min(this.reconnectAttempts, 5))
  }

  private setStatus(status: AgentRealtimeStatus) {
    this.status = status
    this.statusListeners.forEach((listener) => listener(status))
  }
}

export const agentRealtimeClient = new AgentRealtimeClient()
