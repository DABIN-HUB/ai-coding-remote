export interface PageResult<T> {
  list: T[]
  total: number
}

export interface AgentDevice {
  id: number
  deviceId: string
  deviceName?: string
  hostname?: string
  osName?: string
  osVersion?: string
  daemonVersion?: string
  deviceStatus: string
  online?: boolean
  lastSeenAt?: string
  relayNodeId?: string
  runtimeStatus?: string
  runtimeAvailable?: boolean
}

export interface AgentProject {
  id: number
  deviceId: number
  projectId: string
  projectName: string
  workspacePath?: string
  agentType: string
  projectStatus: string
  lastSeenTime?: string
}

export interface AgentSession {
  id: number
  sessionId: string
  deviceId: number
  projectId: number
  runtimeId?: number
  agentType: string
  nativeSessionId?: string
  sessionStatus: string
  lastEventSeq?: number
  lastActiveTime?: string
  startedTime?: string
  closedTime?: string
  errorMessage?: string
}

export interface AgentCommand {
  id: number
  commandId: string
  sessionId: number
  commandType: string
  commandStatus: string
  requestId?: string
  ackCode?: string
  ackMessage?: string
  ackedTime?: string
}

export interface AgentSessionControlResp {
  controlCommandId: string
  sessionId: string
  targetCommandId?: string
  action: SessionControlAction
  commandStatus: string
  sessionStatus: string
}

export interface AgentMessage {
  id: number
  messageId: string
  sessionId: number
  commandId?: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL' | string
  messageType: string
  content: string
  eventSeq?: number
  messageStatus: string
  nativeItemId?: string
  createSource?: string
  createTime?: string
}

export interface RelayTicket {
  ticket: string
  expireAt: string
}

export interface AgentPairingCode {
  pairingCode: string
  expireAt: string
}

export interface PermissionRequest {
  id: number
  permissionId: string
  sessionId: number
  commandId?: number
  permissionType: string
  permissionStatus: string
  title?: string
  reason?: string
  requestJson?: string
  decision?: string
  errorMessage?: string
}

export interface ChangeSet {
  id: number
  changeSetId: string
  sessionId: number
  commandId: number
  projectId: number
  status: string
  fileCount: number
  additions: number
  deletions: number
  diffTruncated?: boolean
  filesTruncated?: boolean
  startedTime?: string
  completedTime?: string
}

export interface FileChange {
  id: number
  fileChangeId: string
  relativePath: string
  oldRelativePath?: string
  changeType: string
  additions?: number
  deletions?: number
  binary?: boolean
  patchTruncated?: boolean
  redacted?: boolean
  summary?: string
  patchText?: string
  patchSha256?: string
}

export interface ChangeSetDetail {
  changeSet: ChangeSet
  files: FileChange[]
}

export interface ChangeSetDiff {
  changeSetId: string
  diffText?: string
  diffSha256?: string
  diffTruncated?: boolean
}

export interface Artifact {
  id: number
  artifactId: string
  sourceType: string
  status: string
  sessionId: number
  sourceCommandId: number
  transferCommandId?: number
  changeSetId: number
  fileChangeId: number
  relativePath: string
  fileName: string
  contentType?: string
  fileSize?: number
  sha256?: string
  fileId?: number
  requestedTime?: string
  readyTime?: string
  expireTime?: string
  errorCode?: string
  errorMessage?: string
}

export type SessionControlAction = 'INTERRUPT' | 'CANCEL' | 'CLOSE_SESSION'

export type AgentEventType =
  | 'SESSION_STARTED'
  | 'SESSION_STATE_CHANGED'
  | 'SESSION_IDLE'
  | 'SESSION_INTERRUPTED'
  | 'SESSION_COMPLETED'
  | 'SESSION_CONTROL_TIMEOUT'
  | 'AGENT_MESSAGE_DELTA'
  | 'AGENT_MESSAGE'
  | 'PERMISSION_REQUIRED'
  | 'PERMISSION_RESOLVED'
  | 'DIFF_UPDATED'
  | 'CHANGE_SET_FINALIZED'
  | 'ERROR'
  | 'WARNING'
  | string

export interface AgentEvent<T = unknown> {
  eventId: string
  traceId?: string
  tenantId?: number
  userId?: number
  deviceId?: string
  projectId?: string
  sessionId: string
  seq: number
  agentType?: string
  type: AgentEventType
  priority?: string
  timestamp?: string
  payload?: T
  extensions?: Record<string, unknown>
}

export interface WsEnvelope<T = unknown> {
  messageId?: string
  type: 'HELLO' | 'WELCOME' | 'PING' | 'PONG' | 'AGENT_EVENT' | string
  protocolVersion?: string
  timestamp?: string
  payload?: T
}

export interface SessionInterruptedPayload {
  nativeSessionId?: string
  targetCommandId?: string
  controlCommandId?: string
  action?: SessionControlAction
  initiatedBy?: string
  reason?: string
}

export interface SessionControlTimeoutPayload {
  targetCommandId?: string
  controlCommandId?: string
  action?: SessionControlAction
  timeoutAt?: string
  reason?: string
}

export interface SessionPayload {
  nativeSessionId?: string
  status?: string
  reason?: string
}

export interface AgentMessagePayload {
  content?: string
  delta?: string
  final?: boolean
}

export interface PermissionRequiredPayload {
  permissionId: string
  permissionType: string
  title?: string
  reason?: string
  detail?: Record<string, unknown>
}

export interface ChangeSetFinalizedPayload {
  changeSetId: string
  status: string
  fileCount: number
  additions: number
  deletions: number
  diffTruncated?: boolean
  filesTruncated?: boolean
  files?: FileChange[]
}

export interface FileChangedPayload {
  path?: string
  oldPath?: string
  changeType?: string
  summary?: string
  additions?: number
  deletions?: number
  binary?: boolean
  truncated?: boolean
  redacted?: boolean
}

export interface DiffUpdatedPayload {
  changeSetId?: string
  diff?: string
  diffSha256?: string
  truncated?: boolean
  fileCount?: number
  additions?: number
  deletions?: number
}
