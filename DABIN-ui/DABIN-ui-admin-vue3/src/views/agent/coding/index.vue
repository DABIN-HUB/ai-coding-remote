<template>
  <div class="agent-coding">
    <header class="agent-coding__header">
      <div>
        <p class="agent-coding__eyebrow">AI Coding</p>
        <h1>远程编码工作台</h1>
      </div>
      <div class="agent-coding__status">
        <el-tag :type="realtimeTagType">{{ realtimeText }}</el-tag>
        <el-button class="agent-coding__mobile-picker" @click="deviceDrawer = true">设备 / 项目</el-button>
      </div>
    </header>

    <el-alert
      v-if="store.realtimeStatus === 'RECONNECTING'"
      title="实时连接已断开，正在重连。页面会通过 REST 自动恢复最终消息。"
      type="warning"
      :closable="false"
      class="mb-16px"
    />

    <el-alert
      v-if="store.controlTimeoutMessage"
      :title="store.controlTimeoutMessage"
      type="error"
      show-icon
      class="mb-16px"
    />

    <main class="agent-coding__grid">
      <aside class="agent-coding__sidebar">
        <DeviceProjectPicker />
      </aside>

      <section class="agent-coding__workspace">
        <div class="agent-coding__workspace-head">
          <div>
            <h2>{{ store.currentProject?.projectName || '请选择项目' }}</h2>
            <p>{{ store.currentProject?.workspacePath || '选择在线设备和项目后开始 Session' }}</p>
          </div>
          <div class="agent-coding__actions">
            <el-tag :type="sessionTagType">{{ sessionStatusText }}</el-tag>
            <el-button :disabled="!store.currentProject" type="primary" @click="handleCreateSession">
              开始会话
            </el-button>
            <el-button :disabled="!store.currentSession" @click="handleCloseSession">关闭会话</el-button>
          </div>
        </div>

        <div ref="messageContainer" class="agent-coding__messages">
          <div
            v-for="message in store.messages"
            :key="message.key"
            class="agent-message"
            :class="`agent-message--${message.role.toLowerCase()}`"
          >
            <div class="agent-message__role">{{ roleText(message.role) }}</div>
            <pre>{{ message.content }}</pre>
            <el-tag v-if="message.streaming" size="small" type="success">实时生成中</el-tag>
          </div>

          <el-empty v-if="store.messages.length === 0" description="还没有消息，发送一个 Prompt 开始。" />

          <div v-if="store.latestChangeSet" class="change-card">
            <div>
              <strong>本轮修改 {{ store.latestChangeSet.changeSet.fileCount }} 个文件</strong>
              <span>+{{ store.latestChangeSet.changeSet.additions }} / -{{ store.latestChangeSet.changeSet.deletions }}</span>
            </div>
            <el-button type="primary" link @click="diffDrawer = true">查看变更</el-button>
          </div>
        </div>

        <footer class="agent-coding__composer">
          <el-input
            v-model="prompt"
            type="textarea"
            resize="none"
            :autosize="{ minRows: 2, maxRows: 5 }"
            :disabled="!store.canSend"
            placeholder="输入 Prompt。Enter 发送，Shift + Enter 换行。"
            @keydown="handlePromptKeydown"
          />
          <div class="agent-coding__composer-actions">
            <span>{{ sessionStatusText }}</span>
            <div>
              <el-button
                :disabled="!store.canStop || store.controlPending"
                :loading="store.controlPending"
                type="danger"
                plain
                @click="handleStop"
              >
                停止
              </el-button>
              <el-button :disabled="!store.canSend || !prompt.trim()" type="primary" @click="handleSend">
                发送
              </el-button>
            </div>
          </div>
        </footer>
      </section>
    </main>

    <el-drawer v-model="deviceDrawer" title="设备 / 项目" size="86%" direction="ltr">
      <DeviceProjectPicker />
    </el-drawer>

    <el-drawer v-model="diffDrawer" title="Change Set" size="72%">
      <DiffPanel />
    </el-drawer>

    <el-dialog v-model="pairingDialogVisible" title="连接开发机" width="560px">
      <div class="pairing-dialog">
        <div v-if="pairingStatus === 'GENERATING'" class="pairing-dialog__state">正在生成一次性配对码...</div>

        <template v-else-if="pairingCode">
          <p class="pairing-dialog__hint">
            在需要运行 Codex 的电脑上启动 Daemon，并使用下面的一次性配对码完成连接。
          </p>
          <div class="pairing-dialog__code">{{ pairingCode.pairingCode }}</div>
          <div class="pairing-dialog__meta">
            状态：{{ pairingStatusText }}
            <span v-if="pairingRemainingText"> · 有效期：{{ pairingRemainingText }}</span>
          </div>
          <pre class="pairing-dialog__command">{{ pairingCommand }}</pre>
          <div v-if="pairingStatus === 'EXPIRED'" class="pairing-dialog__error">
            该配对码已过期，请重新生成。
          </div>
          <div v-if="pairingStatus === 'SUCCESS'" class="pairing-dialog__success">
            开发机连接成功，正在刷新设备列表。
          </div>
        </template>

        <div v-if="pairingStatus === 'FAILED'" class="pairing-dialog__error">
          {{ pairingError || '配对码生成失败，请稍后重试。' }}
        </div>
      </div>
      <template #footer>
        <el-button :disabled="!pairingCode" @click="copyPairingCode">复制配对码</el-button>
        <el-button :loading="pairingLoading" type="primary" @click="generatePairingCode">
          {{ pairingCode ? '重新生成' : '生成配对码' }}
        </el-button>
      </template>
    </el-dialog>

    <PermissionDialog />
  </div>
</template>

<script setup lang="ts">
import { ElButton, ElDialog, ElMessage, ElMessageBox } from 'element-plus'
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as AgentApi from '@/api/agent'
import { useAgentCodingStore } from '@/store/modules/agentCoding'
import type { AgentPairingCode, FileChange } from '@/api/agent/types'

defineOptions({ name: 'AgentCodingWorkspace' })

type PairingStatus = 'IDLE' | 'GENERATING' | 'WAITING' | 'EXPIRED' | 'SUCCESS' | 'FAILED'

const PAIRING_POLL_INTERVAL = 2500
const PAIRING_SUCCESS_CLOSE_DELAY = 1200

const store = useAgentCodingStore()
const prompt = ref('')
const deviceDrawer = ref(false)
const diffDrawer = ref(false)
const messageContainer = ref<HTMLElement>()
const pairingDialogVisible = ref(false)
const pairingCode = ref<AgentPairingCode>()
const pairingLoading = ref(false)
const pairingStatus = ref<PairingStatus>('IDLE')
const pairingError = ref('')
const pairingRemainingText = ref('')
let pairingCountdownTimer: number | undefined
let pairingPollTimer: number | undefined
let pairingCloseTimer: number | undefined
let pairingGeneration = 0
let pairingPollInFlight = false

const realtimeText = computed(() => {
  const map: Record<string, string> = {
    CONNECTED: '实时已连接',
    CONNECTING: '实时连接中',
    RECONNECTING: '实时重连中',
    DISCONNECTED: '实时未连接'
  }
  return map[store.realtimeStatus] || store.realtimeStatus
})

const realtimeTagType = computed(() => {
  if (store.realtimeStatus === 'CONNECTED') return 'success'
  if (store.realtimeStatus === 'RECONNECTING') return 'warning'
  return 'info'
})

const sessionStatusText = computed(() => sessionText(store.currentSession?.sessionStatus))
const sessionTagType = computed(() => {
  const status = store.currentSession?.sessionStatus
  if (status === 'IDLE') return 'success'
  if (status === 'RUNNING' || status === 'WAITING_PERMISSION') return 'warning'
  if (status === 'CLOSED') return 'info'
  if (status === 'FAILED') return 'danger'
  return 'info'
})
const pairingCommand = computed(() =>
  pairingCode.value
    ? `java -jar DABIN-agent-daemon/target/DABIN-agent-daemon.jar --mode=pair --pairingCode=${pairingCode.value.pairingCode}`
    : ''
)
const pairingStatusText = computed(() => {
  const map: Record<PairingStatus, string> = {
    IDLE: '未生成',
    GENERATING: '生成中',
    WAITING: '等待开发机连接',
    EXPIRED: '已过期',
    SUCCESS: '配对成功',
    FAILED: '生成失败'
  }
  return map[pairingStatus.value]
})

onMounted(async () => {
  store.bindRealtime()
  await store.loadDevices()
})

onBeforeUnmount(() => {
  stopPairingTimers()
  store.disconnectRealtime()
})

watch(
  () => store.messages.length,
  async () => {
    await nextTick()
    messageContainer.value?.scrollTo({ top: messageContainer.value.scrollHeight, behavior: 'smooth' })
  }
)

watch(pairingDialogVisible, (visible) => {
  if (!visible) {
    pairingGeneration += 1
    pairingLoading.value = false
    pairingStatus.value = 'IDLE'
    pairingError.value = ''
    pairingRemainingText.value = ''
    stopPairingTimers()
  }
})

function sessionText(status?: string) {
  const map: Record<string, string> = {
    CREATED: '已创建',
    RUNNING: '执行中',
    WAITING_PERMISSION: '等待确认',
    IDLE: '空闲',
    CLOSED: '已关闭',
    FAILED: '失败'
  }
  return map[status || ''] || '未开始'
}

function roleText(role: string) {
  const map: Record<string, string> = {
    USER: '你',
    ASSISTANT: 'Agent',
    SYSTEM: '系统',
    TOOL: '工具'
  }
  return map[role] || role
}

async function handleCreateSession() {
  await store.createSession()
}

async function handleSend() {
  const content = prompt.value.trim()
  if (!content) {
    return
  }
  prompt.value = ''
  await store.sendPrompt(content)
}

function handlePromptKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void handleSend()
  }
}

async function handleStop() {
  await store.stopCurrentPrompt()
  ElMessage.info('正在停止任务，等待 Agent 返回终止结果。')
}

async function handleCloseSession() {
  await ElMessageBox.confirm('关闭后当前 Session 不能再发送 Prompt。确认关闭？', '关闭会话', {
    type: 'warning'
  })
  await store.closeCurrentSession()
}

function openPairingDialog() {
  pairingDialogVisible.value = true
  void generatePairingCode()
}

async function generatePairingCode() {
  if (pairingLoading.value) {
    return
  }
  const generation = ++pairingGeneration
  stopPairingTimers()
  pairingLoading.value = true
  pairingStatus.value = 'GENERATING'
  pairingError.value = ''
  pairingRemainingText.value = ''
  pairingCode.value = undefined
  const previousDeviceIds = new Set(store.devices.map((device) => device.id))
  try {
    const code = await AgentApi.createPairingCode()
    if (generation !== pairingGeneration) {
      return
    }
    pairingCode.value = code
    pairingStatus.value = 'WAITING'
    startPairingCountdown(code.expireAt, generation)
    startPairingPolling(previousDeviceIds, generation)
  } catch (error) {
    if (generation === pairingGeneration) {
      pairingStatus.value = 'FAILED'
      pairingError.value = extractErrorMessage(error)
    }
  } finally {
    if (generation === pairingGeneration) {
      pairingLoading.value = false
    }
  }
}

async function copyPairingCode() {
  if (!pairingCode.value?.pairingCode) {
    return
  }
  try {
    await navigator.clipboard.writeText(pairingCode.value.pairingCode)
    ElMessage.success('配对码已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制配对码')
  }
}

function startPairingCountdown(expireAt: string, generation: number) {
  if (!updatePairingRemaining(expireAt, generation)) {
    return
  }
  pairingCountdownTimer = window.setInterval(() => updatePairingRemaining(expireAt, generation), 1000)
}

function updatePairingRemaining(expireAt: string, generation: number) {
  if (generation !== pairingGeneration || pairingStatus.value !== 'WAITING') {
    return false
  }
  const expiresAt = new Date(expireAt).getTime()
  const remainingMillis = expiresAt - Date.now()
  if (!Number.isFinite(expiresAt) || remainingMillis <= 0) {
    pairingRemainingText.value = '00:00'
    pairingStatus.value = 'EXPIRED'
    stopPairingPolling()
    stopPairingCountdown()
    return false
  }
  pairingRemainingText.value = formatRemaining(remainingMillis)
  return true
}

function startPairingPolling(previousDeviceIds: Set<number>, generation: number) {
  stopPairingPolling()
  pairingPollTimer = window.setInterval(async () => {
    if (generation !== pairingGeneration || pairingStatus.value !== 'WAITING' || pairingPollInFlight) {
      return
    }
    pairingPollInFlight = true
    try {
      const newDevice = await store.refreshDevicesAndSelectNew(previousDeviceIds)
      if (generation !== pairingGeneration || !newDevice) {
        return
      }
      pairingStatus.value = 'SUCCESS'
      stopPairingTimers()
      ElMessage.success('开发机连接成功')
      pairingCloseTimer = window.setTimeout(() => {
        if (generation === pairingGeneration) {
          pairingDialogVisible.value = false
        }
      }, PAIRING_SUCCESS_CLOSE_DELAY)
    } catch (error) {
      pairingError.value = extractErrorMessage(error)
    } finally {
      pairingPollInFlight = false
    }
  }, PAIRING_POLL_INTERVAL)
}

function stopPairingTimers() {
  stopPairingCountdown()
  stopPairingPolling()
  if (pairingCloseTimer !== undefined) {
    window.clearTimeout(pairingCloseTimer)
    pairingCloseTimer = undefined
  }
}

function stopPairingCountdown() {
  if (pairingCountdownTimer !== undefined) {
    window.clearInterval(pairingCountdownTimer)
    pairingCountdownTimer = undefined
  }
}

function stopPairingPolling() {
  if (pairingPollTimer !== undefined) {
    window.clearInterval(pairingPollTimer)
    pairingPollTimer = undefined
  }
  pairingPollInFlight = false
}

function formatRemaining(milliseconds: number) {
  const totalSeconds = Math.max(0, Math.ceil(milliseconds / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function extractErrorMessage(error: unknown) {
  if (error && typeof error === 'object') {
    const record = error as { message?: string; response?: { data?: { msg?: string; message?: string } } }
    return record.response?.data?.msg || record.response?.data?.message || record.message || '请求失败'
  }
  return '请求失败'
}

const DeviceProjectPicker = defineComponent({
  name: 'DeviceProjectPicker',
  setup() {
    return () =>
      h('div', { class: 'picker' }, [
        h('div', { class: 'picker__section' }, [
          h('div', { class: 'picker__title-row' }, [
            h('div', { class: 'picker__title' }, '开发机'),
            h(
              ElButton,
              {
                size: 'small',
                type: 'primary',
                plain: true,
                loading: pairingLoading.value,
                onClick: openPairingDialog
              },
              () => '+ 添加开发机'
            )
          ]),
          store.devices.length === 0
            ? h('div', { class: 'picker-empty' }, [
                h('strong', '还没有连接开发机'),
                h('p', '在需要运行 Codex 的电脑上启动 Daemon，然后使用一次性配对码完成连接。'),
                h(
                  ElButton,
                  {
                    type: 'primary',
                    loading: pairingLoading.value,
                    onClick: openPairingDialog
                  },
                  () => '添加开发机'
                )
              ])
            : store.devices.map((device) =>
                h(
                  'button',
                  {
                    class: ['picker__item', store.currentDevice?.id === device.id ? 'is-active' : ''],
                    onClick: () => store.selectDevice(device)
                  },
                  [
                    h('span', { class: 'picker__name' }, device.deviceName || device.hostname || device.deviceId),
                    h('span', { class: device.online ? 'picker__online' : 'picker__offline' }, device.online ? '在线' : '离线')
                  ]
                )
              )
        ]),
        h('div', { class: 'picker__section' }, [
          h('div', { class: 'picker__title' }, '项目'),
          store.projects.length === 0
            ? h('div', { class: 'picker-empty picker-empty--compact' }, '当前开发机还没有注册项目')
            : store.projects.map((project) =>
                h(
                  'button',
                  {
                    class: ['picker__item', store.currentProject?.id === project.id ? 'is-active' : ''],
                    disabled: !store.currentDevice?.online || project.projectStatus !== 'ACTIVE',
                    onClick: () => store.selectProject(project)
                  },
                  [
                    h('span', { class: 'picker__name' }, project.projectName),
                    h('span', { class: 'picker__meta' }, `${project.agentType} / ${project.projectStatus}`)
                  ]
                )
              )
        ])
      ])
  }
})

const PermissionDialog = defineComponent({
  name: 'PermissionDialog',
  setup() {
    const deciding = ref(false)
    const decisions = computed(() => permissionDecisions(store.pendingPermission?.detail))
    const decide = async (decision: string) => {
      deciding.value = true
      try {
        await store.approvePermission(decision)
        ElMessage.info('正在提交授权，等待 Agent 确认。')
      } finally {
        deciding.value = false
      }
    }
    return () =>
      h(
        ElDialog,
        {
          modelValue: Boolean(store.pendingPermission),
          title: store.pendingPermission?.title || '需要确认操作',
          width: '560px',
          closeOnClickModal: false,
          showClose: false
        },
        {
          default: () =>
            h('div', { class: 'permission-box' }, [
              h('p', store.pendingPermission?.reason || 'Agent 请求执行需要确认的操作。'),
              h('pre', JSON.stringify(store.pendingPermission?.detail || {}, null, 2))
            ]),
          footer: () =>
            decisions.value.map((decision) =>
              h(
                ElButton,
                {
                  type: decision.type,
                  loading: deciding.value,
                  onClick: () => decide(decision.value)
                },
                () => decision.label
              )
            )
        }
      )
  }
})

function permissionDecisions(detail?: Record<string, unknown>) {
  const values = availableDecisionSet(detail)
  const allows = (value: string) => values.size === 0 || values.has(value)
  const decisions: Array<{ value: string; label: string; type?: 'primary' | 'success' }> = []
  if (allows('REJECTED')) {
    decisions.push({ value: 'REJECTED', label: '拒绝' })
  }
  if (allows('APPROVED')) {
    decisions.push({ value: 'APPROVED', label: '允许本次', type: 'primary' })
  }
  if (allows('APPROVED_FOR_SESSION')) {
    decisions.push({ value: 'APPROVED_FOR_SESSION', label: '允许本 Session', type: 'success' })
  }
  return decisions.length > 0 ? decisions : [{ value: 'REJECTED', label: '拒绝' }]
}

function availableDecisionSet(detail?: Record<string, unknown>) {
  const raw = detail?.availableDecisions
  if (!Array.isArray(raw)) {
    return new Set<string>()
  }
  return new Set(raw.map((item) => String(item)))
}

const DiffPanel = defineComponent({
  name: 'DiffPanel',
  setup() {
    const selectedFile = ref<FileChange>()
    const displayDiff = computed(() => selectedFile.value?.patchText || store.diffText || '')
    const diffLines = computed(() => displayDiff.value.split('\n'))
    const requestArtifact = async (file: FileChange) => {
      await store.requestArtifact(file)
      ElMessage.info('正在获取文件，完成后可下载。')
    }
    return () =>
      h('div', { class: 'diff-panel' }, [
        h(
          'aside',
          { class: 'diff-panel__files' },
          (store.latestChangeSet?.files || []).map((file) => {
            const artifact = store.artifacts[file.fileChangeId]
            const canArtifact = file.changeType !== 'DELETED' && !file.redacted
            return h('div', { class: 'diff-file' }, [
              h(
                'button',
                {
                  class: ['diff-file__main', selectedFile.value?.fileChangeId === file.fileChangeId ? 'is-active' : ''],
                  onClick: async () => {
                    selectedFile.value = await AgentApi.getFileChange(file.fileChangeId)
                  }
                },
                [
                  h('span', `${changeTypeText(file.changeType)} ${file.relativePath}`),
                  h('small', `+${file.additions || 0} / -${file.deletions || 0}`)
                ]
              ),
              canArtifact
                ? h(
                    ElButton,
                    {
                      size: 'small',
                      type: artifact?.status === 'READY' ? 'success' : 'primary',
                      loading: artifact && !['READY', 'FAILED', 'EXPIRED'].includes(artifact.status),
                      onClick: () =>
                        artifact?.status === 'READY'
                          ? store.downloadArtifact(artifact)
                          : requestArtifact(file)
                    },
                    () => (artifact?.status === 'READY' ? '下载' : '获取文件')
                  )
                : h('small', { class: 'diff-file__disabled' }, file.redacted ? '敏感文件不可下载' : '已删除')
            ])
          })
        ),
        h(
          'pre',
          { class: 'diff-panel__diff' },
          diffLines.value.map((line, index) =>
            h('code', { key: index, class: diffLineClass(line) }, line || ' ')
          )
        )
      ])
  }
})

function diffLineClass(line: string) {
  if (line.startsWith('+') && !line.startsWith('+++')) return 'line-add'
  if (line.startsWith('-') && !line.startsWith('---')) return 'line-del'
  if (line.startsWith('@@')) return 'line-hunk'
  return ''
}

function changeTypeText(type: string) {
  const map: Record<string, string> = {
    ADDED: 'A',
    MODIFIED: 'M',
    DELETED: 'D',
    RENAMED: 'R',
    UNKNOWN: '?'
  }
  return map[type] || type
}
</script>

<style scoped lang="scss">
.agent-coding {
  min-height: calc(100vh - 120px);
  color: #1f2937;
}

.agent-coding__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  h1 {
    margin: 0;
    font-size: 28px;
    font-weight: 750;
  }
}

.agent-coding__eyebrow {
  margin: 0 0 4px;
  font-size: 13px;
  letter-spacing: 0.08em;
  color: #64748b;
  text-transform: uppercase;
}

.agent-coding__status,
.agent-coding__actions,
.agent-coding__composer-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.agent-coding__mobile-picker {
  display: none;
}

.agent-coding__grid {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
}

.agent-coding__sidebar,
.agent-coding__workspace {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 18px;
  box-shadow: 0 14px 40px rgb(15 23 42 / 8%);
}

.agent-coding__workspace {
  display: flex;
  min-height: 680px;
  flex-direction: column;
  overflow: hidden;
}

.agent-coding__workspace-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid #e5e7eb;

  h2,
  p {
    margin: 0;
  }

  p {
    margin-top: 6px;
    color: #64748b;
  }
}

.agent-coding__messages {
  flex: 1;
  padding: 20px;
  overflow: auto;
  background:
    radial-gradient(circle at top right, rgb(14 165 233 / 8%), transparent 30%),
    linear-gradient(180deg, #f8fafc 0%, #fff 100%);
}

.agent-message {
  max-width: 78%;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 16px;

  pre {
    margin: 8px 0 0;
    overflow-x: auto;
    overflow-wrap: anywhere;
    font-family: 'JetBrains Mono', 'Cascadia Code', monospace;
    white-space: pre-wrap;
  }
}

.agent-message--user {
  margin-left: auto;
  color: #f8fafc;
  background: #0f172a;
}

.agent-message--system {
  background: #fff7ed;
  border-color: #fed7aa;
}

.agent-message__role {
  font-size: 12px;
  font-weight: 700;
  opacity: 0.72;
}

.agent-coding__composer {
  padding: 16px;
  border-top: 1px solid #e5e7eb;
}

.agent-coding__composer-actions {
  justify-content: space-between;
  margin-top: 10px;
  color: #64748b;
}

.picker {
  padding: 16px;
}

.picker__section + .picker__section {
  margin-top: 18px;
}

.picker__title,
.picker__title-row {
  margin-bottom: 8px;
}

.picker__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.picker__title {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #64748b;
  text-transform: uppercase;
}

.picker__item {
  width: 100%;
  padding: 12px;
  margin-bottom: 8px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;

  &.is-active {
    border-color: #2563eb;
    box-shadow: 0 0 0 3px rgb(37 99 235 / 10%);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
}

.picker__name,
.picker__meta {
  display: block;
}

.picker__online,
.picker__offline,
.picker__meta {
  font-size: 12px;
}

.picker__online {
  color: #16a34a;
}

.picker__offline {
  color: #dc2626;
}

.picker-empty {
  padding: 18px;
  color: #64748b;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 14px;

  strong {
    display: block;
    margin-bottom: 6px;
    color: #0f172a;
  }

  p {
    margin: 0 0 12px;
    line-height: 1.6;
  }
}

.picker-empty--compact {
  padding: 12px;
  font-size: 13px;
}

.change-card {
  display: flex;
  justify-content: space-between;
  max-width: 520px;
  padding: 14px 16px;
  margin-top: 18px;
  background: #ecfeff;
  border: 1px solid #a5f3fc;
  border-radius: 16px;
}

.pairing-dialog__hint {
  margin: 0 0 14px;
  line-height: 1.6;
  color: #475569;
}

.pairing-dialog__code {
  padding: 18px;
  margin-bottom: 12px;
  font-family: 'JetBrains Mono', 'Cascadia Code', monospace;
  font-size: 32px;
  font-weight: 800;
  letter-spacing: 0.18em;
  color: #0f172a;
  text-align: center;
  background: #f8fafc;
  border: 1px solid #dbeafe;
  border-radius: 16px;
}

.pairing-dialog__meta {
  margin-bottom: 12px;
  color: #64748b;
}

.pairing-dialog__command {
  padding: 12px;
  overflow-x: auto;
  color: #e2e8f0;
  background: #0f172a;
  border-radius: 12px;
}

.pairing-dialog__error {
  color: #dc2626;
}

.pairing-dialog__success {
  color: #16a34a;
}

.pairing-dialog__state {
  color: #475569;
}

.permission-box pre {
  max-height: 260px;
  padding: 12px;
  overflow: auto;
  color: #e2e8f0;
  background: #0f172a;
  border-radius: 10px;
}

.diff-panel {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  height: 100%;
}

.diff-panel__files {
  overflow: auto;
  border-right: 1px solid #e5e7eb;
}

.diff-file {
  padding: 10px 10px 10px 0;
  border-bottom: 1px solid #eef2f7;
}

.diff-file__main {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding: 8px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 8px;

  &.is-active,
  &:hover {
    background: #eff6ff;
  }
}

.diff-file__disabled {
  color: #94a3b8;
}

.diff-panel__diff {
  min-height: 480px;
  padding: 14px;
  margin: 0;
  overflow: auto;
  background: #0b1120;
  border-radius: 14px;

  code {
    display: block;
    min-height: 18px;
    color: #cbd5e1;
    white-space: pre;
  }

  .line-add {
    color: #86efac;
  }

  .line-del {
    color: #fca5a5;
  }

  .line-hunk {
    color: #93c5fd;
  }
}

@media (width <= 900px) {
  .agent-coding__mobile-picker {
    display: inline-flex;
  }

  .agent-coding__grid {
    grid-template-columns: 1fr;
  }

  .agent-coding__sidebar {
    display: none;
  }

  .agent-coding__workspace {
    min-height: calc(100vh - 180px);
  }

  .agent-coding__workspace-head,
  .agent-coding__header {
    flex-direction: column;
    align-items: flex-start;
  }

  .agent-message {
    max-width: 100%;
  }

  .diff-panel {
    grid-template-columns: 1fr;
  }
}
</style>
