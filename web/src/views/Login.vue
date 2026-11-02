<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request, setAuth } from '../api'

const route = useRoute()
const router = useRouter()

const mode = ref('login') // login 登录 / register 注册
const loading = ref(false)
const error = ref('')
const tip = ref('')

const form = reactive({
  phone: '',
  password: '',
  nickname: ''
})

function switchMode(next) {
  mode.value = next
  error.value = ''
  tip.value = ''
}

async function submit() {
  error.value = ''
  tip.value = ''

  if (!/^1\d{10}$/.test(form.phone)) {
    error.value = '请输入 11 位手机号'
    return
  }
  if (!form.password || form.password.length < 6) {
    error.value = '请输入至少 6 位密码'
    return
  }

  loading.value = true
  try {
    if (mode.value === 'register') {
      if (!form.nickname.trim()) {
        error.value = '请输入昵称'
        return
      }
      await request('/auth/register', {
        method: 'POST',
        body: {
          phone: form.phone,
          password: form.password,
          nickname: form.nickname.trim()
        }
      })
      tip.value = '注册成功，请使用该手机号登录'
      mode.value = 'login'
    } else {
      const data = await request('/auth/login', {
        method: 'POST',
        body: { phone: form.phone, password: form.password }
      })
      setAuth(data.token, {
        userId: data.userId,
        nickname: data.nickname,
        role: data.role
      })
      // 登录成功：买家去抢购专区，商家去商家后台
      const redirect = route.query.redirect
      const fallback = data.role === 'MERCHANT' ? '/merchant' : '/activities'
      router.replace(typeof redirect === 'string' && redirect ? redirect : fallback)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-brand">
        <h1>FlashMart 限时抢购</h1>
        <p>Redis 原子扣减 · 消息队列削峰 · 限时秒杀</p>
      </div>

      <div class="login-tabs">
        <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
      </div>

      <div v-if="error" class="alert alert-error">{{ error }}</div>
      <div v-if="tip" class="alert alert-ok">{{ tip }}</div>

      <form @submit.prevent="submit">
        <div class="field">
          <label>手机号</label>
          <input v-model.trim="form.phone" class="input" type="text" maxlength="11" placeholder="请输入 11 位手机号" />
        </div>

        <div class="field">
          <label>密码</label>
          <input v-model="form.password" class="input" type="password" placeholder="请输入密码" />
        </div>

        <template v-if="mode === 'register'">
          <div class="field">
            <label>昵称</label>
            <input v-model.trim="form.nickname" class="input" type="text" placeholder="请输入昵称" />
          </div>
        </template>

        <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
          {{ loading ? '处理中…' : mode === 'login' ? '登录' : '注册' }}
        </button>
      </form>

      <div class="login-foot">
        注册后即为买家账号（参与抢购）；商家账号由演示数据预置，用 13811110001 / 123456 登录可发布商品与活动
      </div>
    </div>
  </div>
</template>
