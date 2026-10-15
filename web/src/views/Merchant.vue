<script setup>
import { onMounted, reactive, ref } from 'vue'
import { request } from '../api'
import {
  fen2yuan,
  yuan2fen,
  parseTime,
  formatHMS,
  toDatetimeLocal,
  fromDatetimeLocal,
  activityPhase,
  stockPercent,
  orderStatusText,
  orderStatusBadge,
  PHASE_TEXT,
  PHASE_BADGE
} from '../utils'

const error = ref('')
const tip = ref('')

// ---------------- 商品管理 ----------------
const products = ref([])
const creatingProduct = ref(false)
const productForm = reactive({
  title: '',
  description: '',
  originPriceYuan: '',
  coverColor: '#4f7cff'
})

// ---------------- 活动管理 ----------------
const activities = ref([])
const creatingActivity = ref(false)
const activityForm = reactive({
  productId: '',
  title: '',
  flashPriceYuan: '',
  totalStock: 100,
  limitPerUser: 1,
  startTime: '',
  endTime: ''
})

// 展开的活动订单
const expandedId = ref(null)
const activityOrders = ref([])
const loadingOrders = ref(false)

// 修改活动（库存 / 时间）
const editingId = ref(null)
const editForm = reactive({ totalStock: 0, startTime: '', endTime: '' })
const savingEdit = ref(false)

const now = ref(Date.now())

function resetProductForm() {
  productForm.title = ''
  productForm.description = ''
  productForm.originPriceYuan = ''
  productForm.coverColor = '#4f7cff'
}

function resetActivityForm() {
  activityForm.productId = ''
  activityForm.title = ''
  activityForm.flashPriceYuan = ''
  activityForm.totalStock = 100
  activityForm.limitPerUser = 1
  // 默认：现在开始，一天后结束
  activityForm.startTime = toDatetimeLocal(Date.now())
  activityForm.endTime = toDatetimeLocal(Date.now() + 24 * 3600 * 1000)
}

async function loadProducts() {
  try {
    const data = await request('/merchant/product/mine')
    products.value = data || []
  } catch (e) {
    error.value = e.message
  }
}

async function loadActivities() {
  try {
    const data = await request('/merchant/activity/mine')
    activities.value = data || []
  } catch (e) {
    error.value = e.message
  }
}

onMounted(function () {
  resetActivityForm()
  loadProducts()
  loadActivities()
  setInterval(function () {
    now.value = Date.now()
  }, 1000)
})

/** 创建商品 */
async function createProduct() {
  error.value = ''
  tip.value = ''
  if (!productForm.title.trim()) {
    error.value = '请填写商品标题'
    return
  }
  if (!productForm.originPriceYuan || Number(productForm.originPriceYuan) <= 0) {
    error.value = '请填写正确的商品原价'
    return
  }
  creatingProduct.value = true
  try {
    await request('/merchant/product', {
      method: 'POST',
      body: {
        title: productForm.title.trim(),
        description: productForm.description.trim(),
        originPrice: yuan2fen(productForm.originPriceYuan),
        coverColor: productForm.coverColor
      }
    })
    tip.value = '商品创建成功'
    resetProductForm()
    loadProducts()
  } catch (e) {
    error.value = e.message
  } finally {
    creatingProduct.value = false
  }
}

/** 创建抢购活动 */
async function createActivity() {
  error.value = ''
  tip.value = ''
  if (!activityForm.productId) {
    error.value = '请选择要参加抢购的商品'
    return
  }
  if (!activityForm.title.trim()) {
    error.value = '请填写活动标题'
    return
  }
  if (!activityForm.flashPriceYuan || Number(activityForm.flashPriceYuan) <= 0) {
    error.value = '请填写正确的抢购价'
    return
  }
  if (!activityForm.totalStock || Number(activityForm.totalStock) <= 0) {
    error.value = '请填写正确的活动总库存'
    return
  }
  if (!activityForm.startTime || !activityForm.endTime) {
    error.value = '请选择活动开始与结束时间'
    return
  }
  if (parseTime(fromDatetimeLocal(activityForm.endTime)) <= parseTime(fromDatetimeLocal(activityForm.startTime))) {
    error.value = '结束时间必须晚于开始时间'
    return
  }

  creatingActivity.value = true
  try {
    await request('/merchant/activity', {
      method: 'POST',
      body: {
        productId: Number(activityForm.productId),
        title: activityForm.title.trim(),
        flashPrice: yuan2fen(activityForm.flashPriceYuan),
        totalStock: Number(activityForm.totalStock),
        limitPerUser: Number(activityForm.limitPerUser) || 1,
        startTime: fromDatetimeLocal(activityForm.startTime),
        endTime: fromDatetimeLocal(activityForm.endTime)
      }
    })
    tip.value = '活动创建成功，库存已预热到 Redis'
    resetActivityForm()
    loadActivities()
  } catch (e) {
    error.value = e.message
  } finally {
    creatingActivity.value = false
  }
}

/** 展开 / 收起某个活动的订单列表 */
async function toggleOrders(activity) {
  if (expandedId.value === activity.id) {
    expandedId.value = null
    return
  }
  expandedId.value = activity.id
  activityOrders.value = []
  loadingOrders.value = true
  try {
    const data = await request('/merchant/activity/' + activity.id + '/orders')
    activityOrders.value = data || []
  } catch (e) {
    error.value = e.message
  } finally {
    loadingOrders.value = false
  }
}

/** 打开修改表单 */
function startEdit(activity) {
  editingId.value = activity.id
  editForm.totalStock = activity.totalStock
  editForm.startTime = toDatetimeLocal(parseTime(activity.startTime))
  editForm.endTime = toDatetimeLocal(parseTime(activity.endTime))
  error.value = ''
  tip.value = ''
}

/** 保存修改（先改数据库，后端会重建缓存并重新预热库存） */
async function saveEdit(activity) {
  error.value = ''
  tip.value = ''
  if (!editForm.totalStock || Number(editForm.totalStock) <= 0) {
    error.value = '总库存必须大于 0'
    return
  }
  savingEdit.value = true
  try {
    await request('/merchant/activity/' + activity.id, {
      method: 'PUT',
      body: {
        totalStock: Number(editForm.totalStock),
        startTime: fromDatetimeLocal(editForm.startTime),
        endTime: fromDatetimeLocal(editForm.endTime)
      }
    })
    tip.value = '活动已更新'
    editingId.value = null
    loadActivities()
  } catch (e) {
    error.value = e.message
  } finally {
    savingEdit.value = false
  }
}

function phaseOf(activity) {
  if (activity.status === 0) {
    return 'ENDED'
  }
  return activityPhase(activity, now.value)
}

function phaseText(activity) {
  return activity.status === 0 ? '已下线' : PHASE_TEXT[phaseOf(activity)]
}

function phaseBadge(activity) {
  return activity.status === 0 ? 'badge-gray' : PHASE_BADGE[phaseOf(activity)]
}

function percentOf(activity) {
  return stockPercent(activity.remainingStock, activity.totalStock)
}

function remainText(activity) {
  const phase = phaseOf(activity)
  if (phase === 'NOT_START') {
    return '距开始 ' + formatHMS(parseTime(activity.startTime) - now.value)
  }
  if (phase === 'RUNNING') {
    return '距结束 ' + formatHMS(parseTime(activity.endTime) - now.value)
  }
  return '已结束'
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">商家后台</h1>
        <p class="page-sub">发布商品、设置限时抢购活动，并查看活动收到的订单</p>
      </div>
    </div>

    <div v-if="error" class="alert alert-error">{{ error }}</div>
    <div v-if="tip" class="alert alert-ok">{{ tip }}</div>

    <!-- ============ ① 商品管理 ============ -->
    <section class="merchant-section">
      <h2 class="section-title">商品管理</h2>
      <div class="merchant-grid">
        <div class="card card-pad">
          <h3 class="section-title">创建商品</h3>
          <div class="field">
            <label>商品标题</label>
            <input v-model="productForm.title" class="input" type="text" placeholder="例如：无线蓝牙耳机 Pro" />
          </div>
          <div class="field">
            <label>商品描述</label>
            <textarea v-model="productForm.description" class="textarea" placeholder="一句话描述卖点"></textarea>
          </div>
          <div class="field">
            <label>原价（元）</label>
            <input v-model="productForm.originPriceYuan" class="input" type="number" min="0" step="0.01" placeholder="例如：299.00" />
          </div>
          <div class="field">
            <label>卡片主题色</label>
            <div class="input-with-color">
              <input v-model="productForm.coverColor" class="input input-color" type="color" />
              <input v-model="productForm.coverColor" class="input" type="text" placeholder="#4f7cff" />
            </div>
            <p class="form-tip">该颜色会用作商品卡片的色块</p>
          </div>
          <button class="btn btn-primary btn-block" type="button" :disabled="creatingProduct" @click="createProduct">
            {{ creatingProduct ? '提交中…' : '创建商品' }}
          </button>
        </div>

        <div class="card">
          <div class="card-head">
            <span>我店铺的商品</span>
            <span class="fs-13 text-3">共 {{ products.length }} 件</span>
          </div>
          <div v-if="products.length === 0" class="empty">还没有商品，先在左侧创建一个</div>
          <div v-for="p in products" :key="p.id" class="list-row">
            <span class="swatch" :style="{ backgroundColor: p.coverColor || '#4f7cff' }"></span>
            <div class="list-row-main">
              <div class="list-row-title">{{ p.title }}</div>
              <div class="list-row-sub">{{ p.description || '暂无描述' }}</div>
            </div>
            <div class="list-row-side">
              <span class="price">￥{{ fen2yuan(p.originPrice) }}</span>
              <span class="badge badge-gray">#{{ p.id }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ ② 活动管理 ============ -->
    <section class="merchant-section">
      <h2 class="section-title">活动管理</h2>
      <div class="merchant-grid">
        <div class="card card-pad">
          <h3 class="section-title">创建抢购活动</h3>
          <div class="field">
            <label>选择商品</label>
            <select v-model="activityForm.productId" class="select">
              <option value="">请选择商品</option>
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.title }}</option>
            </select>
          </div>
          <div class="field">
            <label>活动标题</label>
            <input v-model="activityForm.title" class="input" type="text" placeholder="例如：耳机狂欢场 · 限时抢购" />
          </div>
          <div class="form-row">
            <div class="field">
              <label>抢购价（元）</label>
              <input v-model="activityForm.flashPriceYuan" class="input" type="number" min="0" step="0.01" placeholder="9.90" />
            </div>
            <div class="field">
              <label>活动总库存</label>
              <input v-model="activityForm.totalStock" class="input" type="number" min="1" step="1" />
            </div>
          </div>
          <div class="field">
            <label>每人限购（件）</label>
            <input v-model="activityForm.limitPerUser" class="input" type="number" min="1" step="1" />
          </div>
          <div class="form-row">
            <div class="field">
              <label>开始时间</label>
              <input v-model="activityForm.startTime" class="input" type="datetime-local" />
            </div>
            <div class="field">
              <label>结束时间</label>
              <input v-model="activityForm.endTime" class="input" type="datetime-local" />
            </div>
          </div>
          <button class="btn btn-primary btn-block" type="button" :disabled="creatingActivity" @click="createActivity">
            {{ creatingActivity ? '提交中…' : '发布活动' }}
          </button>
        </div>

        <div class="card">
          <div class="card-head">
            <span>我发布的活动</span>
            <span class="fs-13 text-3">共 {{ activities.length }} 场</span>
          </div>
          <div v-if="activities.length === 0" class="empty">还没有活动，先在左侧发布一场</div>

          <template v-for="a in activities" :key="a.id">
            <div class="list-row">
              <span class="swatch" :style="{ backgroundColor: a.coverColor || '#4f7cff' }"></span>
              <div class="list-row-main">
                <div class="list-row-title">
                  {{ a.title }}
                  <span class="badge" :class="phaseBadge(a)">{{ phaseText(a) }}</span>
                </div>
                <div class="list-row-sub">
                  {{ a.productTitle }} · 抢购价 ￥{{ fen2yuan(a.flashPrice) }} · 原价 ￥{{ fen2yuan(a.originPrice) }}
                </div>
                <div class="stock-line mt-8">
                  <span>剩余 {{ a.remainingStock }} / {{ a.totalStock }}</span>
                  <span class="countdown">{{ remainText(a) }}</span>
                </div>
                <div class="progress">
                  <div class="progress-bar" :class="{ low: percentOf(a) <= 20 }" :style="{ width: percentOf(a) + '%' }"></div>
                </div>
              </div>
              <div class="list-row-side">
                <button class="btn btn-sm" type="button" @click="toggleOrders(a)">
                  {{ expandedId === a.id ? '收起订单' : '查看订单' }}
                </button>
                <button class="btn btn-sm" type="button" @click="startEdit(a)">修改</button>
              </div>
            </div>

            <!-- 修改活动的内联表单 -->
            <div v-if="editingId === a.id" class="expand-box">
              <div class="form-row">
                <div class="field">
                  <label>活动总库存</label>
                  <input v-model="editForm.totalStock" class="input" type="number" min="1" step="1" />
                </div>
                <div class="field">
                  <label>开始时间</label>
                  <input v-model="editForm.startTime" class="input" type="datetime-local" />
                </div>
                <div class="field">
                  <label>结束时间</label>
                  <input v-model="editForm.endTime" class="input" type="datetime-local" />
                </div>
              </div>
              <div class="flex gap-8">
                <button class="btn btn-primary btn-sm" type="button" :disabled="savingEdit" @click="saveEdit(a)">
                  {{ savingEdit ? '保存中…' : '保存修改' }}
                </button>
                <button class="btn btn-sm" type="button" @click="editingId = null">取消</button>
              </div>
              <p class="form-tip">保存后后端会更新数据库并重新预热 Redis 库存</p>
            </div>

            <!-- 展开的订单列表 -->
            <div v-if="expandedId === a.id" class="expand-box">
              <div v-if="loadingOrders" class="fs-13 text-3">正在加载订单…</div>
              <div v-else-if="activityOrders.length === 0" class="fs-13 text-3">该活动还没有收到订单</div>
              <table v-else class="data-table">
                <thead>
                  <tr>
                    <th>订单号</th>
                    <th>用户下单时间</th>
                    <th>抢购价</th>
                    <th>状态</th>
                    <th>支付时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="o in activityOrders" :key="o.orderNo">
                    <td class="mono">{{ o.orderNo }}</td>
                    <td>{{ o.createTime || '-' }}</td>
                    <td class="price">￥{{ fen2yuan(o.flashPrice) }}</td>
                    <td>
                      <span class="badge" :class="orderStatusBadge(o.status)">{{ orderStatusText(o.status) }}</span>
                    </td>
                    <td>{{ o.payTime || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
      </div>
    </section>
  </div>
</template>
