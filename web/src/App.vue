<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentToken, currentUser, clearAuth } from './api'
import { roleText } from './utils'

const route = useRoute()
const router = useRouter()

// 未登录（或停留在登录页）时不显示顶部导航
const showNav = computed(function () {
  return !!currentToken.value && route.name !== 'login'
})

const isMerchant = computed(function () {
  return !!currentUser.value && currentUser.value.role === 'MERCHANT'
})

function logout() {
  clearAuth()
  router.replace('/login')
}
</script>

<template>
  <div class="app">
    <header v-if="showNav" class="topbar">
      <div class="topbar-inner">
        <router-link class="logo" to="/activities">
          FlashMart<span class="logo-sub">限时抢购</span>
        </router-link>

        <nav class="nav">
          <router-link to="/activities">抢购专区</router-link>
          <router-link to="/orders">我的订单</router-link>
          <router-link v-if="isMerchant" to="/merchant">商家后台</router-link>
        </nav>

        <div class="topbar-user">
          <span class="name">{{ currentUser ? currentUser.nickname : '未登录' }}</span>
          <span class="role-chip">{{ currentUser ? roleText(currentUser.role) : '' }}</span>
          <button class="btn btn-sm" type="button" @click="logout">退出登录</button>
        </div>
      </div>
    </header>

    <main>
      <router-view />
    </main>
  </div>
</template>
