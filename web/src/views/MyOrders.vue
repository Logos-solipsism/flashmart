<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { request } from '../api'
import {
  fen2yuan,
  parseTime,
  formatHMS,
  orderStatusText,
  orderStatusBadge
} from '../utils'

const orders = ref([])
const loading = ref(false)
const error = ref('')
const tip = ref('')
const payingNo = ref('')
const filter = ref('ALL')

const now = ref(Date.now())
let timer = null

const tabs = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_PAY', label: '待支付' },
  { key: 'PAID', label: '已支付' },
  { key: 'CLOSED', label: '已关闭' }
]

const filtered = computed(function () {
  if (filter.value === 'ALL') {
    return orders.value
  }
  if (filter.value === 'CLOSED') {
    return orders.value.filter(function (o) {
      return o.status === 'TIMEOUT' || o.status === 'CANCELLED'
    })
  }
  return orders.value.filter(function (o) {
    return o.status === filter.value
  })
})

const pendingCount = computed(function () {
  return orders.value.filter(function (o) {
    return o.status === 'PENDING_PAY'
  }).length
})

function countOf(key) {
  if (key === 'ALL') {
    return orders.value.length
  }
  if (key === 'CLOSED') {
    return orders.value.filter(function (o) {
      return o.status === 'TIMEOUT' || o.status === 'CANCELLED'
    }).length
  }
  return orders.value.filter(function (o) {
    return o.status === key
  }).length
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await request('/order/mine')
    orders.value = data || []
  } catch (e) {
    error.value = e.message
    orders.value = []
  } finally {
    loading.value = false
  }
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

/** 待支付剩余时间；<= 0 表示已超时 */
function remainMs(order) {
  if (order.status !== 'PENDING_PAY') {
    return 0
  }
  return parseTime(order.payDeadline) - now.value
}

function isExpired(order) {
  return order.status === 'PENDING_PAY' && remainMs(order) <= 0
}

async function pay(order) {
  if (payingNo.value) {
    return
  }
  error.value = ''
  tip.value = ''
  payingNo.value = order.orderNo
  try {
    const data = await request('/order/' + order.orderNo + '/pay', { method: 'POST' })
    order.status = data.status
    order.payTime = data.payTime || order.payTime
    tip.value = '订单 ' + order.orderNo + ' ' + (data.message || '支付成功')
  } catch (e) {
    error.value = e.message
    // 订单状态可能已经变化，刷新一次拿到最新状态
    load()
  } finally {
    payingNo.value = ''
  }
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">我的订单</h1>
        <p class="page-sub">
          共 {{ orders.length }} 笔订单，其中待支付 {{ pendingCount }} 笔
        </p>
      </div>
      <button class="btn" type="button" :disabled="loading" @click="load">
        {{ loading ? '刷新中…' : '刷新订单' }}
      </button>
    </div>

    <div class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        :class="{ active: filter === tab.key }"
        @click="filter = tab.key"
      >
        {{ tab.label }}（{{ countOf(tab.key) }}）
      </button>
    </div>

    <div v-if="error" class="alert alert-error">{{ error }}</div>
    <div v-if="tip" class="alert alert-ok">{{ tip }}</div>

    <div v-if="loading && orders.length === 0" class="loading-line">
      <span class="spinner spinner-dark"></span> 正在加载订单…
    </div>

    <div v-else-if="filtered.length === 0" class="card">
      <div class="empty">
        <span class="empty-icon">📦</span>
        暂无订单，去抢购专区看看有什么好货
      </div>
    </div>

    <div v-else class="card">
      <div v-for="order in filtered" :key="order.orderNo" class="order-item">
        <div class="cover order-cover" :style="{ backgroundColor: order.coverColor || '#4f7cff' }">
          <span>{{ order.productTitle }}</span>
        </div>

        <div class="order-main">
          <div class="order-title">{{ order.activityTitle || order.productTitle }}</div>
          <div class="flex items-center gap-12">
            <span class="price price-md"><span class="price-symbol">￥</span>{{ fen2yuan(order.flashPrice) }}</span>
            <span class="badge" :class="orderStatusBadge(order.status)">{{ orderStatusText(order.status) }}</span>
          </div>
          <div class="order-meta">
            <span>订单号：<span class="mono">{{ order.orderNo }}</span></span>
            <span>下单时间：{{ order.createTime || '-' }}</span>
            <span v-if="order.payTime">支付时间：{{ order.payTime }}</span>
            <span v-if="order.payTradeNo">交易流水：<span class="mono">{{ order.payTradeNo }}</span></span>
          </div>
        </div>

        <div class="order-side">
          <template v-if="order.status === 'PENDING_PAY'">
            <template v-if="isExpired(order)">
              <span class="fs-13 text-2">订单已超时关闭</span>
              <button class="btn btn-sm" type="button" @click="load">刷新状态</button>
            </template>
            <template v-else>
              <span class="fs-13 text-2">
                支付剩余 <b class="price">{{ formatHMS(remainMs(order)) }}</b>
              </span>
              <button
                class="btn btn-primary"
                type="button"
                :disabled="payingNo === order.orderNo"
                @click="pay(order)"
              >
                {{ payingNo === order.orderNo ? '支付中…' : '支付 ￥' + fen2yuan(order.flashPrice) }}
              </button>
            </template>
          </template>
          <template v-else-if="order.status === 'PAID'">
            <span class="fs-13" style="color: var(--ok)">支付成功</span>
          </template>
          <template v-else>
            <span class="fs-13 text-3">{{ orderStatusText(order.status) }}</span>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
