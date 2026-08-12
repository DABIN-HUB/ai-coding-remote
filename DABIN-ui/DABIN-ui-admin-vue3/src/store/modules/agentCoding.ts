import { defineStore } from 'pinia'
import { store } from '@/store'
import * as AgentApi from '@/api/agent'
import { agentRealtimeClient, type AgentRealtimeStatus } from '@/api/agent/realtime'
import type {
  AgentDevice,
  AgentEvent,
  AgentMessage,
  AgentMessagePayload,
  AgentProject,
  AgentSession,
  Artifact,
  ChangeSetDetail,
  ChangeSetFinalizedPayload,
  FileChange,
  PageResult,
  PermissionRequiredPayload,
  PermissionRequest,
  SessionControlTimeoutPayload,
  SessionInterruptedPayload
} from '@/api/agent/types'

interface ChatMessage {
  key: string
  role: string
  content: string
  streaming?: boolean
  commandId?: string
  createTime?: string
}

interface AgentCodingState {
  devices: AgentDevice[]
  projects: AgentProject[]
  currentDevice?: AgentDevice
  currentProject?: AgentProject
  currentSession?: AgentSession
  activeCommandId?: string
  controlPending?: boolean
  controlTimeoutMessage?: string
  messages: ChatMessage[]
  pendingPermission?: PermissionRequiredPayload
  latestChangeSet?: ChangeSetDetail
  diffText: string
  artifacts: Record<string, Artifact>
  realtimeStatus: AgentRealtimeStatus
  realtimeBound: boolean
}

const PAGE_SIZE = 50

export const useAgentCodingStore = defineStore('agentCoding', {
  state: (): AgentCodingState => ({
    devices: [],
    projects: [],
    messages: [],
    diffText: '',
    artifacts: {},
    realtimeStatus: 'DISCONNECTED',
    realtimeBound: false
  }),
  getters: {
    isBusy(state): boolean {
      return ['RUNNING', 'WAITING_PERMISSION'].includes(state.currentSession?.sessionStatus || '')
    },
    canSend(state): boolean {
      return (
        Boolean(state.currentSession) &&
        state.currentSession?.sessionStatus !== 'CLOSED' &&
        !['RUNNING', 'WAITING_PERMISSION'].includes(state.currentSession?.sessionStatus || '')
      )
    },
    canStop(state): boolean {
      return Boolean(
        state.currentSession &&
          state.activeCommandId &&
          ['RUNNING', 'WAITING_PERMISSION'].includes(state.currentSession.sessionStatus || '')
      )
    }
  },
  actions: {
    bindRealtime() {
      if (this.realtimeBound) {
        agentRealtimeClient.connect()
        return
      }
      this.realtimeBound = true
      agentRealtimeClient.onStatus((status) => {
        this.realtimeStatus = status
        if (status === 'CONNECTED' && this.currentSession?.sessionId) {
          void this.reloadSessionState()
        }
      })
      agentRealtimeClient.onEvent((event) => this.handleAgentEvent(event))
      agentRealtimeClient.connect()
    },
    disconnectRealtime() {
      agentRealtimeClient.disconnect()
    },
    async loadDevices() {
      const page = await AgentApi.getDevicePage({ pageNo: 1, pageSize: PAGE_SIZE })
      this.devices = page.list || []
      if (!this.currentDevice && this.devices.length > 0) {
        await this.selectDevice(this.devices[0])
      }
    },
    async selectDevice(device: AgentDevice) {
      this.currentDevice = device
      const page = await AgentApi.getProjectPage({ pageNo: 1, pageSize: PAGE_SIZE, deviceDbId: device.id })
      this.projects = page.list || []
      if (!this.currentProject && this.projects.length > 0) {
        this.currentProject = this.projects[0]
      }
    },
    selectProject(project: AgentProject) {
      this.currentProject = project
    },
    async createSession() {
      if (!this.currentProject) {
        return
      }
      this.currentSession = await AgentApi.createSession({
        projectId: this.currentProject.id,
        agentType: this.currentProject.agentType || 'CODEX'
      })
      this.activeCommandId = undefined
      this.controlPending = false
      this.controlTimeoutMessage = undefined
      this.messages = []
      this.latestChangeSet = undefined
      this.diffText = ''
      await this.reloadMessages()
    },
    async sendPrompt(content: string) {
      if (!this.currentSession) {
        return
      }
      const clientRequestId = crypto.randomUUID()
      this.messages.push({
        key: clientRequestId,
        role: 'USER',
        content,
        createTime: new Date().toISOString()
      })
      const command = await AgentApi.sendPrompt({
        sessionId: this.currentSession.sessionId,
        content,
        clientRequestId
      })
      this.activeCommandId = command.commandId
      this.currentSession.sessionStatus = 'RUNNING'
    },
    async stopCurrentPrompt() {
      if (!this.currentSession || !this.activeCommandId) {
        return
      }
      this.controlPending = true
      this.controlTimeoutMessage = undefined
      await AgentApi.cancelSession({
        sessionId: this.currentSession.sessionId,
        targetCommandId: this.activeCommandId,
        clientRequestId: crypto.randomUUID(),
        reason: 'User requested stop from browser'
      })
    },
    async closeCurrentSession() {
      if (!this.currentSession) {
        return
      }
      this.controlPending = true
      await AgentApi.closeSession({
        sessionId: this.currentSession.sessionId,
        targetCommandId: this.activeCommandId,
        clientRequestId: crypto.randomUUID(),
        reason: 'User closed session from browser'
      })
    },
    async approvePermission(decision: string) {
      if (!this.pendingPermission) {
        return
      }
      await AgentApi.decidePermission({
        permissionId: this.pendingPermission.permissionId,
        decision,
        reason: 'Browser decision'
      })
    },
    async openChangeSetByCommand(commandId: string) {
      this.latestChangeSet = await AgentApi.getChangeSetByCommand(commandId)
      if (this.latestChangeSet?.changeSet?.changeSetId) {
        const diff = await AgentApi.getChangeSetDiff(this.latestChangeSet.changeSet.changeSetId)
        this.diffText = diff.diffText || ''
      }
    },
    async requestArtifact(file: FileChange) {
      const artifact = await AgentApi.requestArtifactFile({
        fileChangeId: file.fileChangeId,
        clientRequestId: crypto.randomUUID()
      })
      this.artifacts[file.fileChangeId] = artifact
      void this.pollArtifact(file.fileChangeId, artifact.artifactId)
    },
    async pollArtifact(fileChangeId: string, artifactId: string) {
      const terminal = new Set(['READY', 'FAILED', 'EXPIRED'])
      for (let i = 0; i < 60; i++) {
        const artifact = await AgentApi.getArtifact(artifactId)
        this.artifacts[fileChangeId] = artifact
        if (terminal.has(artifact.status)) {
          return
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1500))
      }
    },
    async downloadArtifact(artifact: Artifact) {
      const blob = await AgentApi.downloadArtifact(artifact.artifactId)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = artifact.fileName || 'artifact.bin'
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    },
    async reloadSessionState() {
      if (!this.currentSession?.sessionId) {
        return
      }
      this.currentSession = await AgentApi.getSession(this.currentSession.sessionId)
      await this.reloadMessages()
      await this.reloadPendingPermission()
    },
    async reloadMessages() {
      if (!this.currentSession?.sessionId) {
        return
      }
      const page = await AgentApi.getMessagePage(this.currentSession.sessionId, {
        pageNo: 1,
        pageSize: 100
      })
      this.messages = normalizeMessages(page)
    },
    async reloadPendingPermission() {
      if (!this.currentSession?.sessionId) {
        return
      }
      const page = await AgentApi.getPermissionPage({
        pageNo: 1,
        pageSize: 10,
        sessionId: this.currentSession.sessionId,
        permissionStatus: 'PENDING'
      })
      const first = page.list?.[0]
      if (first) {
        this.pendingPermission = permissionFromDb(first)
      }
    },
    handleAgentEvent(event: AgentEvent) {
      if (this.currentSession?.sessionId && event.sessionId !== this.currentSession.sessionId) {
        return
      }
      if (event.type === 'AGENT_MESSAGE_DELTA' || event.type === 'AGENT_MESSAGE') {
        this.mergeAssistantMessage(event)
        return
      }
      if (event.type === 'PERMISSION_REQUIRED') {
        this.pendingPermission = event.payload as PermissionRequiredPayload
        if (this.currentSession) {
          this.currentSession.sessionStatus = 'WAITING_PERMISSION'
        }
        return
      }
      if (event.type === 'PERMISSION_RESOLVED') {
        this.pendingPermission = undefined
        if (this.currentSession) {
          this.currentSession.sessionStatus = 'RUNNING'
        }
        return
      }
      if (event.type === 'CHANGE_SET_FINALIZED') {
        const payload = event.payload as ChangeSetFinalizedPayload
        this.latestChangeSet = {
          changeSet: {
            id: 0,
            changeSetId: payload.changeSetId,
            sessionId: 0,
            commandId: 0,
            projectId: 0,
            status: payload.status,
            fileCount: payload.fileCount || 0,
            additions: payload.additions || 0,
            deletions: payload.deletions || 0,
            diffTruncated: payload.diffTruncated,
            filesTruncated: payload.filesTruncated
          },
          files: payload.files || []
        }
        const commandId = String(event.extensions?.platformCommandId || event.extensions?.PLATFORM_COMMAND_ID || '')
        if (commandId) {
          void this.openChangeSetByCommand(commandId)
        }
        return
      }
      if (event.type === 'SESSION_INTERRUPTED') {
        const payload = event.payload as SessionInterruptedPayload
        if (payload.action === 'CANCEL') {
          this.controlPending = false
          this.activeCommandId = undefined
        }
        if (this.currentSession) {
          this.currentSession.sessionStatus = 'IDLE'
        }
        return
      }
      if (event.type === 'SESSION_CONTROL_TIMEOUT') {
        const payload = event.payload as SessionControlTimeoutPayload
        this.controlPending = false
        this.controlTimeoutMessage = payload.reason || '停止请求超时，Agent 可能仍在执行'
        return
      }
      if (event.type === 'SESSION_IDLE') {
        if (this.currentSession && this.currentSession.sessionStatus !== 'CLOSED') {
          this.currentSession.sessionStatus = 'IDLE'
        }
        this.activeCommandId = undefined
        this.controlPending = false
        void this.reloadMessages()
        return
      }
      if (event.type === 'SESSION_COMPLETED') {
        if (this.currentSession) {
          this.currentSession.sessionStatus = 'CLOSED'
        }
        this.activeCommandId = undefined
        this.controlPending = false
        return
      }
      if (event.type === 'ERROR' || event.type === 'WARNING') {
        this.messages.push({
          key: event.eventId,
          role: 'SYSTEM',
          content: readablePayload(event.payload),
          createTime: event.timestamp
        })
      }
    },
    mergeAssistantMessage(event: AgentEvent) {
      const payload = event.payload as AgentMessagePayload | undefined
      const content = payload?.content || ''
      const commandId = String(event.extensions?.platformCommandId || event.extensions?.PLATFORM_COMMAND_ID || '')
      const existing = [...this.messages].reverse().find(
        (message) => message.role === 'ASSISTANT' && message.streaming && message.commandId === commandId
      )
      if (event.type === 'AGENT_MESSAGE_DELTA') {
        if (existing) {
          existing.content += content
        } else {
          this.messages.push({
            key: event.eventId,
            role: 'ASSISTANT',
            content,
            streaming: true,
            commandId,
            createTime: event.timestamp
          })
        }
        return
      }
      if (existing) {
        existing.content = content || existing.content
        existing.streaming = false
      } else {
        this.messages.push({
          key: event.eventId,
          role: 'ASSISTANT',
          content,
          commandId,
          createTime: event.timestamp
        })
      }
      void this.reloadMessages()
    }
  }
})

function normalizeMessages(page: PageResult<AgentMessage>): ChatMessage[] {
  return (page.list || [])
    .slice()
    .sort((a, b) => (a.id || 0) - (b.id || 0))
    .map((message) => ({
      key: message.messageId,
      role: message.role,
      content: message.content,
      createTime: message.createTime
    }))
}

function permissionFromDb(permission: PermissionRequest): PermissionRequiredPayload {
  return {
    permissionId: permission.permissionId,
    permissionType: permission.permissionType,
    title: permission.title,
    reason: permission.reason,
    detail: parseJson(permission.requestJson)
  }
}

function parseJson(value?: string): Record<string, unknown> {
  if (!value) {
    return {}
  }
  try {
    return JSON.parse(value)
  } catch {
    return {}
  }
}

function readablePayload(payload: unknown): string {
  if (!payload || typeof payload !== 'object') {
    return String(payload || '')
  }
  const object = payload as Record<string, unknown>
  return String(object.message || object.reason || object.code || 'Agent 返回了诊断事件')
}

export const useAgentCodingStoreWithOut = () => {
  return useAgentCodingStore(store)
}
