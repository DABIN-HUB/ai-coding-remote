import request from '@/config/axios'
import type {
  AgentCommand,
  AgentDevice,
  AgentMessage,
  AgentPairingCode,
  AgentProject,
  AgentSession,
  AgentSessionControlResp,
  Artifact,
  ChangeSetDetail,
  ChangeSetDiff,
  FileChange,
  PageResult,
  PermissionRequest,
  RelayTicket
} from './types'

export const getDevicePage = (params: Record<string, unknown>) => {
  return request.get<PageResult<AgentDevice>>({ url: '/agent/device/page', params })
}

export const createPairingCode = () => {
  return request.post<AgentPairingCode>({ url: '/agent/device/createPairingCode' })
}

export const getProjectPage = (params: Record<string, unknown>) => {
  return request.get<PageResult<AgentProject>>({ url: '/agent/project/page', params })
}

export const createSession = (data: { projectId: number; agentType: string }) => {
  return request.post<AgentSession>({ url: '/agent/session/create', data })
}

export const getSession = (sessionId: string) => {
  return request.get<AgentSession>({ url: '/agent/session/get', params: { sessionId } })
}

export const sendPrompt = (data: {
  sessionId: string
  content: string
  clientRequestId: string
}) => {
  return request.post<AgentCommand>({ url: '/agent/session/sendPrompt', data })
}

export const cancelSession = (data: {
  sessionId: string
  targetCommandId: string
  clientRequestId: string
  reason?: string
}) => {
  return request.post<AgentSessionControlResp>({ url: '/agent/session/cancel', data })
}

export const interruptSession = (data: {
  sessionId: string
  targetCommandId: string
  clientRequestId: string
  reason?: string
}) => {
  return request.post<AgentSessionControlResp>({ url: '/agent/session/interrupt', data })
}

export const closeSession = (data: {
  sessionId: string
  targetCommandId?: string
  clientRequestId: string
  reason?: string
}) => {
  return request.post<AgentSessionControlResp>({ url: '/agent/session/close', data })
}

export const getMessagePage = (sessionId: string, params: Record<string, unknown>) => {
  return request.get<PageResult<AgentMessage>>({
    url: '/agent/session/messagePage',
    params: { sessionId, ...params }
  })
}

export const createUserTicket = () => {
  return request.post<RelayTicket>({ url: '/agent/relay/createUserTicket' })
}

export const getPermissionPage = (params: Record<string, unknown>) => {
  return request.get<PageResult<PermissionRequest>>({ url: '/agent/permission/page', params })
}

export const decidePermission = (data: { permissionId: string; decision: string; reason?: string }) => {
  return request.post<PermissionRequest>({ url: '/agent/permission/decide', data })
}

export const getChangeSetByCommand = (commandId: string) => {
  return request.get<ChangeSetDetail>({ url: '/agent/changeSet/getByCommand', params: { commandId } })
}

export const getChangeSetDiff = (changeSetId: string) => {
  return request.get<ChangeSetDiff>({ url: '/agent/changeSet/getDiff', params: { changeSetId } })
}

export const getFileChange = (fileChangeId: string) => {
  return request.get<FileChange>({ url: '/agent/changeSet/getFileChange', params: { fileChangeId } })
}

export const requestArtifactFile = (data: { fileChangeId: string; clientRequestId: string }) => {
  return request.post<Artifact>({ url: '/agent/artifact/requestFile', data })
}

export const getArtifact = (artifactId: string) => {
  return request.get<Artifact>({ url: '/agent/artifact/get', params: { artifactId } })
}

export const downloadArtifact = (artifactId: string) => {
  return request.download<Blob>({ url: '/agent/artifact/download', params: { artifactId } })
}
