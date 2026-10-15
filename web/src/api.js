/**
 * 统一请求封装
 * - 自动拼接 /api 前缀（开发环境由 Vite 代理到后端 18090）
 * - 自动携带 Authorization: Bearer <token>
 * - 自动 JSON 序列化 / 反序列化，成功时直接返回响应体里的 data
 * - HTTP 非 2xx 或 body.code !== 0 时抛出 Error（message 为后端原文）
 * - 401 清除本地登录态并跳转登录页；429（限流）同样抛出后端提示语
 */
import { ref } from 'vue'

const API_BASE = '/api'
const TOKEN_KEY = 'flashmart_token'
const USER_KEY = 'flashmart_user'

/** 当前登录用户（响应式，供导航栏等组件使用） */
export const currentUser = ref(readUser())
/** 当前 token（响应式） */
export const currentToken = ref(readToken())

function readToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

function readUser() {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

export function getToken() {
  return readToken()
}

export function getUser() {
  return readUser()
}

/** 登录成功后写入本地登录态 */
export function setAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token || '')
  localStorage.setItem(USER_KEY, JSON.stringify(user || null))
  currentToken.value = token || ''
  currentUser.value = user || null
}

/** 退出登录 / 登录失效时清除本地登录态 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  currentToken.value = ''
  currentUser.value = null
}

function makeError(message, status, code) {
  const err = new Error(message)
  err.status = status
  err.code = code
  return err
}

/**
 * 发起请求
 * @param {string} path   接口路径，如 '/activity/list'
 * @param {object} options { method, body, query }
 * @returns {Promise<any>} 响应体中的 data 字段
 */
export async function request(path, options = {}) {
  const method = options.method || 'GET'
  const query = options.query
  const body = options.body

  let url = API_BASE + path
  if (query) {
    const params = new URLSearchParams()
    Object.keys(query).forEach(function (key) {
      const value = query[key]
      if (value !== undefined && value !== null && value !== '') {
        params.append(key, value)
      }
    })
    const qs = params.toString()
    if (qs) {
      url += (url.indexOf('?') >= 0 ? '&' : '?') + qs
    }
  }

  const headers = { Accept: 'application/json' }
  const token = getToken()
  if (token) {
    headers.Authorization = 'Bearer ' + token
  }

  const init = { method: method, headers: headers }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }

  let res
  try {
    res = await fetch(url, init)
  } catch (e) {
    throw makeError('网络异常，请确认后端服务（18090 端口）已启动', 0)
  }

  // 后端异常时可能返回空 body，这里做一次容错解析
  let payload = null
  const text = await res.text()
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch (e) {
      payload = null
    }
  }

  // 未登录 / 登录过期：清掉本地 token 并回到登录页
  if (res.status === 401) {
    clearAuth()
    if (window.location.hash.indexOf('#/login') !== 0) {
      window.location.hash = '#/login'
    }
    throw makeError((payload && payload.message) || '登录已过期，请重新登录', 401)
  }

  // 限流（429）等非 2xx：直接把后端提示语抛给页面
  if (!res.ok) {
    const fallback =
      res.status >= 500 ? '服务暂时不可用，请稍后重试' : '请求失败（HTTP ' + res.status + '）'
    throw makeError(
      (payload && payload.message) || fallback,
      res.status,
      payload ? payload.code : undefined
    )
  }

  if (!payload) {
    return null
  }
  if (payload.code !== 0) {
    throw makeError(payload.message || '请求失败', res.status, payload.code)
  }
  return payload.data
}
