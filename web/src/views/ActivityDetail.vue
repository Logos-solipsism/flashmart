<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '../api'
import {
  fen2yuan,
  parseTime,
  formatHMS,
  activityPhase,
  stockPercent,
  orderStatusText,
  orderStatusBadge,
  PHASE_TEXT,
  PHASE_BADGE
} from '../utils'

const route = useRoute()
const router = useRouter()

const activityId = route.params.id

const detail = ref(null)
const loading = ref(true)
const loadError = ref('')

// 抢购态
const seckilling = ref(false) // 已发起抢购、还在等待结果
const queued = ref(false) // 已进入队列，正在轮询
const orderNo = ref('')
const order = ref(null) // 抢购成功后拿到的订单
const actionError = ref('') // 抢购/支付失败提示（后端原文）
const actionTip = ref('')
const paying = ref(false)
const pollTimedOut = ref(false) // 轮询超时（订单还在队列里）
const canRetry = ref(false) // 后端明确告知"本次失败、名额已退回"，可以再抢

// 倒计时：以服务端时间为准，避免前端时钟不准
const serverOffset = ref(0)
const now = ref(Date.now())
let tickTimer = null
let pollTimer = null
let polling = false
let pollFailures = 0 // 轮询连续失败次数，偶发抖动不打断流程

/** 校准后的服务端当前时间 */
const serverNow = computed(function () {
  return now.value + serverOffset.value
})

const phase = computed(function () {
  if (!detail.value) {
    return 'ENDED'
  }
  return activityPhase(detail.value, serverNow.value)
})

const phaseLabel = computed(function () {
  return PHASE_TEXT[phase.value]
})

const percent = computed(function () {
  if (!detail.value) {
    return 0
  }
  return stockPercent(detail.value.remainingStock, detail.value.totalStock)
})

/** 主按钮：未开始/已结束置灰，进行中可点 */
const buyDisabled = computed(function () {
  return !detail.value || phase.value !== 'RUNNING' || seckilling.value
})

const buyText = computed(function () {
  if (!detail.value) {
    return '加载中…'
  }
  if (phase.value === 'NOT_START') {
    return '距开始 ' + formatHMS(parseTime(detail.value.startTime) - serverNow.value)
  }
  if (phase.value === 'ENDED') {
    return '活动已结束'
  }
  if (seckilling.value) {
    return queued.value ? '正在生成订单…' : '抢购中…'
  }
  return '立即抢购'
})

/** 活动剩余时间文案 */
const activityCountdown = computed(function () {
  if (!detail.value) {
    return '-'
  }
  if (phase.value === 'NOT_START') {
    return '距开始 ' + formatHMS(parseTime(detail.value.startTime) - serverNow.value)
  }
  if (phase.value === 'RUNNING') {
    return '距结束 ' + formatHMS(parseTime(detail.value.endTime) - serverNow.value)
  }
  return '活动已结束'
})

/** 待支付剩余时间 */
const payRemainMs = computed(function () {
  if (!order.value || order.value.status !== 'PENDING_PAY') {
    return 0
  }
  return parseTime(order.value.payDeadline) - serverNow.value
})

const payExpired = computed(function () {
  return !!order.value && order.value.status === 'PENDING_PAY' && payRemainMs.value <= 0
})

async function loadDetail() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await request('/activity/' + activityId)
    detail.value = data
    // 用服务端时间校准本地时钟
    const server = parseTime(data.serverTime)
    if (isFinite(server)) {
      serverOffset.value = server - Date.now()
    }
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(function () {
  loadDetail()
  tickTimer = setInterval(function () {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(function () {
  if (tickTimer) {
    clearInterval(tickTimer)
  }
  stopPolling()
})

/** 发起抢购 */
async function doSeckill() {
  if (!detail.value || buyDisabled.value) {
    return
  }
  actionError.value = ''
  actionTip.value = ''
  order.value = null
  orderNo.value = ''
  queued.value = false
  pollTimedOut.value = false
  canRetry.value = false
  seckilling.value = true

  try {
    const data = await request('/seckill/' + detail.value.id, { method: 'POST' })
    orderNo.value = data.orderNo
    queued.value = true
    // 不做过早承诺：这时只是"库存扣到了"，订单还没落库
    actionTip.value = data.message || '抢购资格已锁定，正在为你生成订单'
    startPolling()
  } catch (e) {
    // 限流 / 限购 / 抢光 / 未开始 / 已结束：原样展示后端提示
    actionError.value = e.message
    seckilling.value = false
    loadDetail()
  }
}

/** 轮询抢购结果：每 400ms 一次，最多 15 秒 */
function startPolling() {
  stopPolling()
  pollFailures = 0
  const startedAt = Date.now()
  pollTimer = setInterval(async function () {
    if (Date.now() - startedAt > 15000) {
      stopPolling()
      seckilling.value = false
      queued.value = false
      pollTimedOut.value = true
      actionError.value = '订单生成较慢，请稍后到「我的订单」查看结果'
      return
    }
    if (polling) {
      return
    }
    polling = true
    try {
      const data = await request('/seckill/result/' + orderNo.value)
      if (data.state === 'QUEUING') {
        return
      }
      // 还在重试：保持"排队中"的界面，但换成更准确的提示，继续轮询
      if (data.state === 'RETRYING') {
        actionTip.value = data.message || '订单生成遇到波动，正在重试'
        return
      }
      // 明确失败：停轮询，告诉用户名额已经退回、可以再抢（不要让他干等到超时）
      if (data.state === 'FAILED') {
        stopPolling()
        seckilling.value = false
        queued.value = false
        canRetry.value = data.retryable !== false
        actionTip.value = ''
        actionError.value = data.message || '本次未能抢到，名额已退回，可以再试一次'
        loadDetail()
        return
      }
      // 已经落库，停止轮询
      stopPolling()
      seckilling.value = false
      queued.value = false
      if (data.order) {
        order.value = data.order
      }
      if (data.state === 'PENDING_PAY') {
        actionTip.value = '订单已生成，请尽快支付'
      } else if (data.state === 'PAID') {
        actionTip.value = '订单已支付'
      } else {
        actionError.value = '订单已关闭（' + orderStatusText(data.state) + '）'
      }
      loadDetail()
    } catch (e) {
      // 轮询期间的偶发失败（网络抖动 / 服务瞬时 5xx）先重试，连续 3 次才提示
      pollFailures = pollFailures + 1
      if (pollFailures >= 3) {
        stopPolling()
        seckilling.value = false
        queued.value = false
        actionError.value = e.message
      }
    } finally {
      polling = false
    }
  }, 400)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 支付（模拟支付网关回调） */
async function pay() {
  if (!order.value || paying.value) {
    return
  }
  actionError.value = ''
  actionTip.value = ''
  paying.value = true
  try {
    const data = await request('/order/' + order.value.orderNo + '/pay', { method: 'POST' })
    order.value = Object.assign({}, order.value, { status: data.status })
    actionTip.value = data.message || '支付成功'
  } catch (e) {
    actionError.value = e.message
  } finally {
    paying.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <router-link class="btn btn-sm" to="/activities">← 返回抢购专区</router-link>
      </div>
      <div v-if="detail" class="fs-13 text-3">活动编号 #{{ detail.id }}</div>
    </div>

    <div v-if="loadError" class="alert alert-error">{{ loadError }}</div>
    <div v-if="loading && !detail" class="loading-line">
      <span class="spinner spinner-dark"></span> 正在加载活动详情…
    </div>

    <div v-if="detail" class="detail-grid">
      <!-- 左侧：商品色块 -->
      <div class="card">
        <div class="cover detail-cover" :style="{ backgroundColor: detail.coverColor || '#4f7cff' }">
          <span class="cover-text">{{ detail.productTitle }}</span>
        </div>
        <div class="card-pad">
          <div class="stock-line">
            <span>剩余库存</span>
            <span>
              <b>{{ detail.remainingStock }}</b> / {{ detail.totalStock }}
            </span>
          </div>
          <div class="progress">
            <div
              class="progress-bar"
              :class="{ low: percent <= 20 }"
              :style="{ width: percent + '%' }"
            ></div>
          </div>
          <p class="fs-12 text-3 mt-8">库存由 Redis 原子扣减，页面显示为实时剩余量</p>
        </div>
      </div>

      <!-- 右侧：活动信息与抢购 -->
      <div class="card card-pad">
        <div class="flex between items-center gap-12">
          <h1 class="page-title">{{ detail.title }}</h1>
          <span class="badge" :class="PHASE_BADGE[phase]">{{ phaseLabel }}</span>
        </div>
        <p class="page-sub">{{ detail.description || '限时抢购，先到先得' }}</p>

        <div class="info-list">
          <span class="info-item">商品：<b>{{ detail.productTitle }}</b></span>
          <span class="info-item">每人限购：<b>{{ detail.limitPerUser }} 件</b></span>
          <span class="info-item">开始时间：<b>{{ detail.startTime }}</b></span>
          <span class="info-item">结束时间：<b>{{ detail.endTime }}</b></span>
        </div>

        <div class="buy-panel">
          <div class="buy-price">
            <span class="price price-lg"><span class="price-symbol">￥</span>{{ fen2yuan(detail.flashPrice) }}</span>
            <span class="price-origin">原价 ￥{{ fen2yuan(detail.originPrice) }}</span>
          </div>

          <div class="countdown-box">
            <span class="label">{{ phase === 'NOT_START' ? '距活动开始' : '距活动结束' }}</span>
            <span class="value" :class="{ urgent: phase === 'RUNNING' }">{{ activityCountdown }}</span>
          </div>

          <!-- 抢购失败 / 限流：醒目红条，展示后端原文 -->
          <div v-if="actionError" class="alert alert-error alert-strong">{{ actionError }}</div>
          <div v-if="actionTip" class="alert alert-ok">{{ actionTip }}</div>
          <div v-if="queued" class="alert alert-info">
            <span class="spinner spinner-dark"></span>
            已进入抢购队列（订单号 {{ orderNo }}），正在生成订单，请稍候…
          </div>

          <button
            class="btn btn-danger btn-lg btn-block"
            type="button"
            :disabled="buyDisabled"
            @click="doSeckill"
          >
            {{ buyText }}
          </button>

          <!-- 后端明确告知"本次失败、名额已退回"：给一个显而易见的重试入口，别让用户干等到超时 -->
          <div v-if="canRetry" class="mt-12 flex gap-12">
            <button class="btn btn-danger" type="button" :disabled="buyDisabled" @click="doSeckill">
              再抢一次
            </button>
            <button class="btn" type="button" @click="router.push('/orders')">查看我的订单</button>
          </div>

          <div v-if="pollTimedOut" class="mt-12">
            <button class="btn" type="button" @click="router.push('/orders')">查看我的订单</button>
          </div>

          <!-- 抢购成功后的订单卡片：支付倒计时 + 去支付 -->
          <div v-if="order" class="card mt-16">
            <div class="card-head">
              <span>订单已生成</span>
              <span class="badge" :class="orderStatusBadge(order.status)">{{ orderStatusText(order.status) }}</span>
            </div>
            <div class="card-pad">
              <div class="order-meta">
                <span>订单号：{{ order.orderNo }}</span>
                <span>抢购价：￥{{ fen2yuan(order.flashPrice) }}</span>
                <span>下单时间：{{ order.createTime }}</span>
              </div>

              <template v-if="order.status === 'PENDING_PAY'">
                <div class="countdown-box mt-12">
                  <span class="label">支付剩余时间</span>
                  <span class="value" :class="{ urgent: payRemainMs < 60000 }">
                    {{ payExpired ? '已超时' : formatHMS(payRemainMs) }}
                  </span>
                </div>
                <div v-if="payExpired" class="alert alert-warn">订单已超时关闭，库存已回滚</div>
                <div class="flex gap-12">
                  <button
                    class="btn btn-primary"
                    type="button"
                    :disabled="paying || payExpired"
                    @click="pay"
                  >
                    {{ paying ? '支付中…' : '去支付 ￥' + fen2yuan(order.flashPrice) }}
                  </button>
                  <button class="btn" type="button" @click="router.push('/orders')">查看我的订单</button>
                </div>
              </template>

              <div v-else-if="order.status === 'PAID'" class="alert alert-ok mt-12">
                支付成功，订单已完成
              </div>
              <div v-else class="alert alert-warn mt-12">
                订单已关闭，库存已回滚
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
