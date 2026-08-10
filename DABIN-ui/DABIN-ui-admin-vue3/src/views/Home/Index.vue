<template>
  <div>
    <el-card shadow="never">
      <el-skeleton :loading="loading" animated>
        <el-row :gutter="16" justify="space-between">
          <el-col :xl="12" :lg="12" :md="12" :sm="24" :xs="24">
            <div class="flex items-center">
              <el-avatar :src="avatar" :size="70" class="mr-16px">
                <img src="@/assets/imgs/avatar.gif" alt="" />
              </el-avatar>
              <div>
                <div class="text-20px">
                  {{ t('workplace.welcome') }} {{ username }} {{ t('workplace.happyDay') }}
                </div>
                <div class="mt-10px text-14px text-gray-500">
                  {{ t('workplace.toady') }}，20℃ - 32℃！
                </div>
              </div>
            </div>
          </el-col>
          <el-col :xl="12" :lg="12" :md="12" :sm="24" :xs="24">
            <div class="h-70px flex items-center justify-end lt-sm:mt-10px">
              <div class="px-8px text-right">
                <div class="mb-16px text-14px text-gray-400">{{ t('workplace.project') }}</div>
                <CountTo
                  class="text-20px"
                  :start-val="0"
                  :end-val="totalSate.project"
                  :duration="2600"
                />
              </div>
              <el-divider direction="vertical" />
              <div class="px-8px text-right">
                <div class="mb-16px text-14px text-gray-400">{{ t('workplace.toDo') }}</div>
                <CountTo
                  class="text-20px"
                  :start-val="0"
                  :end-val="totalSate.todo"
                  :duration="2600"
                />
              </div>
              <el-divider direction="vertical" border-style="dashed" />
              <div class="px-8px text-right">
                <div class="mb-16px text-14px text-gray-400">{{ t('workplace.access') }}</div>
                <CountTo
                  class="text-20px"
                  :start-val="0"
                  :end-val="totalSate.access"
                  :duration="2600"
                />
              </div>
            </div>
          </el-col>
        </el-row>
      </el-skeleton>
    </el-card>
  </div>

  <el-row class="mt-8px" :gutter="8" justify="space-between">
    <el-col :xl="16" :lg="16" :md="24" :sm="24" :xs="24" class="mb-8px">
      <el-card shadow="never">
        <template #header>
          <div class="h-3 flex justify-between">
            <span>{{ t('workplace.project') }}</span>
            <el-link
              type="primary"
              :underline="false"
              href="https://github.com/yudaocode"
              target="_blank"
            >
              {{ t('action.more') }}
            </el-link>
          </div>
        </template>
        <el-skeleton :loading="loading" animated>
          <el-row :gutter="8" class="gap-y-8px">
            <el-col
              v-for="(item, index) in projects"
              :key="`card-${index}`"
              :xl="8"
              :lg="8"
              :md="8"
              :sm="24"
              :xs="24"
              class="!flex"
            >
              <el-card
                shadow="hover"
                class="flex-1 cursor-pointer"
                body-class="flex h-full flex-col"
                @click="handleProjectClick(item.message)"
              >
                <div class="flex items-center">
                  <Icon
                    :icon="item.icon"
                    :size="25"
                    class="mr-8px flex-none"
                    :style="{ color: item.color }"
                  />
                  <span class="min-w-0 line-clamp-2 text-16px" :title="item.name">{{
                    item.name
                  }}</span>
                </div>
                <div
                  class="mt-12px break-all line-clamp-2 text-12px text-gray-400"
                  :title="t(item.message)"
                >
                  {{ t(item.message) }}
                </div>
                <div
                  class="mt-auto flex items-center justify-between gap-8px pt-12px text-12px text-gray-400"
                >
                  <span class="min-w-0 truncate" :title="item.personal">{{ item.personal }}</span>
                  <span class="shrink-0 whitespace-nowrap">
                    {{ formatTime(item.time, 'yyyy-MM-dd') }}
                  </span>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </el-skeleton>
      </el-card>

      <el-card shadow="never" class="mt-8px">
        <el-skeleton :loading="loading" animated>
          <el-row :gutter="20" justify="space-between">
            <el-col :xl="10" :lg="10" :md="24" :sm="24" :xs="24">
              <el-card shadow="hover" class="mb-8px">
                <el-skeleton :loading="loading" animated>
                  <Echart :options="pieOptionsData" :height="280" />
                </el-skeleton>
              </el-card>
            </el-col>
            <el-col :xl="14" :lg="14" :md="24" :sm="24" :xs="24">
              <el-card shadow="hover" class="mb-8px">
                <el-skeleton :loading="loading" animated>
                  <Echart :options="barOptionsData" :height="280" />
                </el-skeleton>
              </el-card>
            </el-col>
          </el-row>
        </el-skeleton>
      </el-card>
    </el-col>
    <el-col :xl="8" :lg="8" :md="24" :sm="24" :xs="24" class="mb-8px">
      <el-card shadow="never">
        <template #header>
          <div class="h-3 flex justify-between">
            <span>{{ t('workplace.shortcutOperation') }}</span>
          </div>
        </template>
        <el-skeleton :loading="loading" animated>
          <el-row>
            <el-col v-for="item in shortcut" :key="`team-${item.name}`" :span="8" class="mb-8px">
              <div class="flex items-center">
                <Icon :icon="item.icon" class="mr-8px" :style="{ color: item.color }" />
                <el-link type="default" :underline="false" @click="handleShortcutClick(item.url)">
                  {{ item.name }}
                </el-link>
              </div>
            </el-col>
          </el-row>
        </el-skeleton>
      </el-card>
      <el-card shadow="never" class="mt-8px">
        <template #header>
          <div class="h-3 flex justify-between">
            <span>{{ t('workplace.notice') }}</span>
            <el-link type="primary" :underline="false">{{ t('action.more') }}</el-link>
          </div>
        </template>
        <el-skeleton :loading="loading" animated>
          <div v-for="(item, index) in notice" :key="`dynamics-${index}`">
            <div class="flex items-center">
              <el-avatar :src="avatar" :size="35" class="mr-16px">
                <img src="@/assets/imgs/avatar.gif" alt="" />
              </el-avatar>
              <div>
                <div class="text-14px">
                  <Highlight :keys="item.keys.map((v) => t(v))">
                    {{ item.type }} : {{ item.title }}
                  </Highlight>
                </div>
                <div class="mt-16px text-12px text-gray-400">
                  {{ formatTime(item.date, 'yyyy-MM-dd') }}
                </div>
              </div>
            </div>
            <el-divider />
          </div>
        </el-skeleton>
      </el-card>
    </el-col>
  </el-row>
</template>
<script lang="ts" setup>
import { set } from 'lodash-es'
import { EChartsOption } from 'echarts'
import { formatTime } from '@/utils'

import { useUserStore } from '@/store/modules/user'
// import { useWatermark } from '@/hooks/web/useWatermark'
import type { WorkplaceTotal, Project, Notice, Shortcut } from './types'
import { pieOptions, barOptions } from './echarts-data'
import { useRouter } from 'vue-router'

defineOptions({ name: 'Index' })

const { t } = useI18n()
const router = useRouter()
const userStore = useUserStore()
// const { setWatermark } = useWatermark()
const loading = ref(true)
const avatar = userStore.getUser.avatar
const username = userStore.getUser.nickname
const pieOptionsData = reactive<EChartsOption>(pieOptions) as EChartsOption
// 获取统计数
let totalSate = reactive<WorkplaceTotal>({
  project: 0,
  access: 0,
  todo: 0
})

const getCount = async () => {
  const data = {
    project: 3,
    access: 1280,
    todo: 6
  }
  totalSate = Object.assign(totalSate, data)
}

// 获取项目数
let projects = reactive<Project[]>([])
const getProject = async () => {
  const data = [
    {
      name: 'System 系统管理',
      icon: 'ep:setting',
      message: '用户、角色、菜单、权限与审计日志等后台基础能力',
      personal: '平台基础',
      time: new Date('2025-01-02'),
      color: '#6DB33F'
    },
    {
      name: 'Infra 基础设施',
      icon: 'ep:cpu',
      message: '文件、配置、代码生成、任务调度、Redis 与服务监控',
      personal: '通用基础设施',
      time: new Date('2025-02-03'),
      color: '#409EFF'
    },
    {
      name: 'Pay 支付模块',
      icon: 'ep:money',
      message: '支付应用、渠道、订单、退款、回调与钱包能力',
      personal: '商业化基础',
      time: new Date('2025-03-04'),
      color: '#ff4d4f'
    },
    {
      name: '用户认证',
      icon: 'ep:lock',
      message: '登录、退出登录、Token 管理和 OAuth2 相关能力',
      personal: '访问控制',
      time: new Date('2025-04-05'),
      color: '#1890ff'
    },
    {
      name: '动态菜单',
      icon: 'ep:menu',
      message: '根据后端权限生成动态菜单与动态路由',
      personal: 'RBAC',
      time: new Date('2025-05-06'),
      color: '#e18525'
    },
    {
      name: '前端基础工程',
      icon: 'ep:monitor',
      message: 'Vue3、TypeScript、Element Plus、Pinia、Router 与通用组件',
      personal: '长期开发底座',
      time: new Date('2025-06-01'),
      color: '#2979ff'
    }
  ]
  projects = Object.assign(projects, data)
}

// 获取通知公告
let notice = reactive<Notice[]>([])
const getNotice = async () => {
  const data = [
    {
      title: '前端基础工程保留登录、权限、动态菜单和基础 Layout',
      type: '基础框架',
      keys: ['登录', '权限'],
      date: new Date()
    },
    {
      title: 'System、Infra、Pay 将作为后续 SaaS 平台基础模块',
      type: '保留模块',
      keys: ['System', 'Pay'],
      date: new Date()
    },
    {
      title: '无关业务页面已移出',
      type: '业务瘦身',
      keys: ['业务瘦身'],
      date: new Date()
    },
    {
      title: '后续 Coding Agent 业务将独立使用 agent 领域命名',
      type: '领域边界',
      keys: ['agent'],
      date: new Date()
    }
  ]
  notice = Object.assign(notice, data)
}

// 获取快捷入口
let shortcut = reactive<Shortcut[]>([])

const getShortcut = async () => {
  const data = [
    {
      name: '首页',
      icon: 'ion:home-outline',
      url: '/',
      color: '#1fdaca'
    },
    {
      name: '用户管理',
      icon: 'ep:user',
      url: '/system/user',
      color: '#ff6b6b'
    },
    {
      name: '角色权限',
      icon: 'ep:key',
      url: '/system/role',
      color: '#7c3aed'
    },
    {
      name: '菜单管理',
      icon: 'ep:menu',
      url: '/system/menu',
      color: '#3fb27f'
    },
    {
      name: '文件管理',
      icon: 'ep:folder',
      url: '/infra/file',
      color: '#4daf1bc9'
    },
    {
      name: '支付应用',
      icon: 'ep:wallet',
      url: '/pay/app',
      color: '#1a73e8'
    }
  ]
  shortcut = Object.assign(shortcut, data)
}

// 用户来源
const getUserAccessSource = async () => {
  const data = [
    { value: 335, name: 'analysis.directAccess' },
    { value: 310, name: 'analysis.mailMarketing' },
    { value: 234, name: 'analysis.allianceAdvertising' },
    { value: 135, name: 'analysis.videoAdvertising' },
    { value: 1548, name: 'analysis.searchEngines' }
  ]
  set(
    pieOptionsData,
    'legend.data',
    data.map((v) => t(v.name))
  )
  pieOptionsData!.series![0].data = data.map((v) => {
    return {
      name: t(v.name),
      value: v.value
    }
  })
}
const barOptionsData = reactive<EChartsOption>(barOptions) as EChartsOption

// 周活跃量
const getWeeklyUserActivity = async () => {
  const data = [
    { value: 13253, name: 'analysis.monday' },
    { value: 34235, name: 'analysis.tuesday' },
    { value: 26321, name: 'analysis.wednesday' },
    { value: 12340, name: 'analysis.thursday' },
    { value: 24643, name: 'analysis.friday' },
    { value: 1322, name: 'analysis.saturday' },
    { value: 1324, name: 'analysis.sunday' }
  ]
  set(
    barOptionsData,
    'xAxis.data',
    data.map((v) => t(v.name))
  )
  set(barOptionsData, 'series', [
    {
      name: t('analysis.activeQuantity'),
      data: data.map((v) => v.value),
      type: 'bar'
    }
  ])
}

const getAllApi = async () => {
  await Promise.all([
    getCount(),
    getProject(),
    getNotice(),
    getShortcut(),
    getUserAccessSource(),
    getWeeklyUserActivity()
  ])
  loading.value = false
}

const handleProjectClick = (message: string) => {
  if (message.startsWith('http')) {
    window.open(message, '_blank')
  }
}

const handleShortcutClick = (url: string) => {
  router.push(url)
}

getAllApi()
</script>
