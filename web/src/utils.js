/**
 * 通用工具函数：金额换算、时间解析、倒计时格式化、状态文案
 * 说明：后端时间字段是没有时区后缀的 ISO 本地时间字符串（如 2026-10-15T22:13:04），
 *      这里手动解析，避免个别浏览器把它当成 UTC 处理导致倒计时偏差。
 */

/** 分 → 元（字符串，保留两位小数），例如 1999 → "19.99" */
export function fen2yuan(fen) {
  const n = Number(fen)
  if (!isFinite(n)) {
    return '0.00'
  }
  return (n / 100).toFixed(2)
}

/** 元 → 分（整数），例如 "19.99" → 1999 */
export function yuan2fen(yuan) {
  const n = Number(yuan)
  if (!isFinite(n) || n < 0) {
    return 0
  }
  return Math.round(n * 100)
}

/** 解析后端时间字符串 → 毫秒时间戳 */
export function parseTime(str) {
  if (!str) {
    return NaN
  }
  const m = String(str).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/)
  if (m) {
    return new Date(
      Number(m[1]),
      Number(m[2]) - 1,
      Number(m[3]),
      Number(m[4]),
      Number(m[5]),
      Number(m[6] || 0)
    ).getTime()
  }
  return new Date(str).getTime()
}

/** 毫秒 → "HH:mm:ss"（倒计时展示用，负数按 0 处理） */
export function formatHMS(ms) {
  let total = Math.floor((isFinite(ms) ? ms : 0) / 1000)
  if (total < 0) {
    total = 0
  }
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return pad(h) + ':' + pad(m) + ':' + pad(s)
}

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 时间戳 → "YYYY-MM-DD HH:mm:ss" */
export function formatDateTime(ms) {
  if (!isFinite(ms)) {
    return '-'
  }
  const d = new Date(ms)
  return (
    d.getFullYear() +
    '-' +
    pad(d.getMonth() + 1) +
    '-' +
    pad(d.getDate()) +
    ' ' +
    pad(d.getHours()) +
    ':' +
    pad(d.getMinutes()) +
    ':' +
    pad(d.getSeconds())
  )
}

/** 时间戳 → datetime-local 输入框需要的 "YYYY-MM-DDTHH:mm" */
export function toDatetimeLocal(ms) {
  if (!isFinite(ms)) {
    return ''
  }
  const d = new Date(ms)
  return (
    d.getFullYear() +
    '-' +
    pad(d.getMonth() + 1) +
    '-' +
    pad(d.getDate()) +
    'T' +
    pad(d.getHours()) +
    ':' +
    pad(d.getMinutes())
  )
}

/** datetime-local 的值（2026-10-15T22:00）补全成后端要的 ISO 本地时间字符串 */
export function fromDatetimeLocal(value) {
  if (!value) {
    return ''
  }
  return value.length === 16 ? value + ':00' : value
}

/**
 * 活动所处阶段
 * @returns {'NOT_START' | 'RUNNING' | 'ENDED'}
 */
export function activityPhase(activity, nowMs) {
  const start = parseTime(activity.startTime)
  const end = parseTime(activity.endTime)
  if (isFinite(start) && nowMs < start) {
    return 'NOT_START'
  }
  if (isFinite(end) && nowMs > end) {
    return 'ENDED'
  }
  return 'RUNNING'
}

export const PHASE_TEXT = {
  NOT_START: '未开始',
  RUNNING: '抢购中',
  ENDED: '已结束'
}

/** 活动阶段对应的标签样式 */
export const PHASE_BADGE = {
  NOT_START: 'badge-orange',
  RUNNING: 'badge-red',
  ENDED: 'badge-gray'
}

/** 订单状态文案 */
export const ORDER_STATUS_TEXT = {
  PENDING_PAY: '待支付',
  PAID: '已支付',
  TIMEOUT: '已超时关闭',
  CANCELLED: '已取消',
  QUEUING: '排队中'
}

/** 订单状态对应的徽章样式 */
export const ORDER_STATUS_BADGE = {
  PENDING_PAY: 'badge-orange',
  PAID: 'badge-green',
  TIMEOUT: 'badge-gray',
  CANCELLED: 'badge-gray',
  QUEUING: 'badge-blue'
}

export function orderStatusText(status) {
  return ORDER_STATUS_TEXT[status] || status || '-'
}

export function orderStatusBadge(status) {
  return ORDER_STATUS_BADGE[status] || 'badge-gray'
}

/** 角色文案 */
export function roleText(role) {
  return role === 'MERCHANT' ? '商家' : '买家'
}

/** 剩余库存百分比（0 ~ 100），用于进度条宽度 */
export function stockPercent(remaining, total) {
  const t = Number(total)
  const r = Number(remaining)
  if (!isFinite(t) || t <= 0) {
    return 0
  }
  if (!isFinite(r) || r <= 0) {
    return 0
  }
  return Math.min(100, Math.round((r / t) * 100))
}
