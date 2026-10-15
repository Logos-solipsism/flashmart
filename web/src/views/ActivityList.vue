<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { request } from '../api'
import {
  fen2yuan,
  parseTime,
  formatHMS,
  activityPhase,
  stockPercent,
  PHASE_TEXT,
  PHASE_BADGE
} from '../utils'

const router = useRouter()

const list = ref([])
const keyword = ref('')
const loading = ref(false)
const error = ref('')

// 每秒刷新一次，用于卡片上的倒计时
const now = ref(Date.now())
let timer = null

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await request('/activity/list', { query: { keyword: keyword.value } })
    list.value = data || []
  } catch (e) {
    error.value = e.message
    list.value = []
  } finally {
    loading.value = false
  }
}

function reset() {
  keyword.value = ''
  load()
}

onMounted(function () {
  load()
  timer = setInterval(function () {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(function () {
  if (timer) {
    clearInterval(timer)
  }
})

function phaseOf(activity) {
  if (activity.status === 0) {
    return 'ENDED'
  }
  return activityPhase(activity, now.value)
}

function phaseText(activity) {
  if (activity.status === 0) {
    return '已下线'
  }
  if (activity.remainingStock <= 0 && phaseOf(activity) === 'RUNNING') {
    return '已抢光'
  }
  return PHASE_TEXT[phaseOf(activity)]
}

function phaseBadge(activity) {
  if (activity.status === 0 || (activity.remainingStock <= 0 && phaseOf(activity) === 'RUNNING')) {
    return 'badge-gray'
  }
  return PHASE_BADGE[phaseOf(activity)]
}

function countdownText(activity) {
  const phase = phaseOf(activity)
  if (phase === 'NOT_START') {
    return '距开始 ' + formatHMS(parseTime(activity.startTime) - now.value)
  }
  if (phase === 'RUNNING') {
    return '距结束 ' + formatHMS(parseTime(activity.endTime) - now.value)
  }
  return '本场已结束'
}

function percentOf(activity) {
  return stockPercent(activity.remainingStock, activity.totalStock)
}

function goDetail(activity) {
  router.push('/activity/' + activity.id)
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">抢购专区</h1>
        <p class="page-sub">限时秒杀，每人限购，售完即止</p>
      </div>
    </div>

    <div class="toolbar">
      <input
        v-model.trim="keyword"
        class="input"
        type="text"
        placeholder="搜索活动或商品名称"
        @keyup.enter="load"
      />
      <button class="btn btn-primary" type="button" :disabled="loading" @click="load">搜索</button>
      <button class="btn" type="button" :disabled="loading" @click="reset">重置</button>
      <span class="fs-13 text-3">共 {{ list.length }} 场活动</span>
    </div>

    <div v-if="error" class="alert alert-error">{{ error }}</div>

    <div v-if="loading && list.length === 0" class="loading-line">
      <span class="spinner spinner-dark"></span> 正在加载活动…
    </div>

    <div v-else-if="list.length === 0" class="card">
      <div class="empty">
        <span class="empty-icon">🔍</span>
        没有找到相关活动，换个关键词试试
      </div>
    </div>

    <div v-else class="grid-activities">
      <article
        v-for="item in list"
        :key="item.id"
        class="card activity-card"
        @click="goDetail(item)"
      >
        <div class="cover" :style="{ backgroundColor: item.coverColor || '#4f7cff' }">
          <span class="cover-badge">{{ phaseText(item) }}</span>
          <span class="cover-text">{{ item.productTitle }}</span>
        </div>

        <div class="card-pad">
          <h3 class="activity-name">{{ item.title }}</h3>

          <div class="price-row">
            <span class="price price-md"><span class="price-symbol">￥</span>{{ fen2yuan(item.flashPrice) }}</span>
            <span class="price-origin">￥{{ fen2yuan(item.originPrice) }}</span>
          </div>

          <div>
            <div class="stock-line">
              <span>剩余库存</span>
              <span>
                <b :class="item.remainingStock <= 0 ? 'text-3' : ''">{{ item.remainingStock }}</b>
                / {{ item.totalStock }}
              </span>
            </div>
            <div class="progress">
              <div
                class="progress-bar"
                :class="{ low: percentOf(item) <= 20 }"
                :style="{ width: percentOf(item) + '%' }"
              ></div>
            </div>
          </div>

          <div class="card-foot">
            <span class="countdown">{{ countdownText(item) }}</span>
            <span class="badge" :class="phaseBadge(item)">{{ phaseText(item) }}</span>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>
