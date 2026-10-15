
const BASE = 'http://localhost:5274/api'
async function req(path, opts = {}) {
  const method = opts.method || 'GET'
  const headers = {}
  if (opts.token) headers.Authorization = 'Bearer ' + opts.token
  if (opts.body) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + path, { method, headers, body: opts.body ? JSON.stringify(opts.body) : undefined })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch (e) {}
  return { status: res.status, json }
}
const p2 = n => String(n).padStart(2, '0')
const isoLocal = ms => { const d = new Date(ms); return d.getFullYear()+'-'+p2(d.getMonth()+1)+'-'+p2(d.getDate())+'T'+p2(d.getHours())+':'+p2(d.getMinutes())+':'+p2(d.getSeconds()) }
const rnd = () => '139' + String(Math.floor(Math.random() * 1e8)).padStart(8, '0')
const log = (...a) => console.log(...a)

const mPhone = rnd(), bPhone = rnd()
log('商家手机号', mPhone, '买家手机号', bPhone)

log('[1] 注册商家', (await req('/auth/register', { method: 'POST', body: { phone: mPhone, password: '123456', nickname: '演示商家', role: 'MERCHANT' } })))
log('[2] 注册买家', (await req('/auth/register', { method: 'POST', body: { phone: bPhone, password: '123456', nickname: '演示买家', role: 'BUYER' } })))

const mLogin = await req('/auth/login', { method: 'POST', body: { phone: mPhone, password: '123456' } })
const bLogin = await req('/auth/login', { method: 'POST', body: { phone: bPhone, password: '123456' } })
log('[3] 商家登录', mLogin.json && mLogin.json.data)
const mToken = mLogin.json.data.token, bToken = bLogin.json.data.token

const pRes = await req('/merchant/product', { method: 'POST', token: mToken, body: { title: '无线蓝牙耳机 Pro', description: '主动降噪 · 40 小时续航', originPrice: 29900, coverColor: '#4f7cff' } })
log('[4] 创建商品', pRes.json)
const productId = pRes.json.data

log('[5] 我的商品', JSON.stringify((await req('/merchant/product/mine', { token: mToken })).json.data))

const now = Date.now()
const aRes = await req('/merchant/activity', { method: 'POST', token: mToken, body: { productId, title: '耳机狂欢场 · 限时抢购', flashPrice: 990, totalStock: 50, limitPerUser: 1, startTime: isoLocal(now - 600000), endTime: isoLocal(now + 7200000) } })
log('[6] 创建活动', aRes.json, aRes.status)
const activityId = aRes.json.data

const listRes = await req('/activity/list?keyword=' + encodeURIComponent('耳机'), { token: bToken })
log('[7] 活动列表首条', JSON.stringify(listRes.json.data && listRes.json.data[0]))

const dRes = await req('/activity/' + activityId, { token: bToken })
log('[8] 活动详情', JSON.stringify(dRes.json.data))

const sRes = await req('/seckill/' + activityId, { method: 'POST', token: bToken })
log('[9] 抢购', sRes.status, JSON.stringify(sRes.json))
const orderNo = sRes.json.data.orderNo

let final = null
for (let i = 0; i < 25; i++) {
  await new Promise(r => setTimeout(r, 400))
  const r = await req('/seckill/result/' + orderNo, { token: bToken })
  final = r.json.data
  if (final.state !== 'QUEUING') break
}
log('[10] 轮询结果', JSON.stringify(final))

const dupRes = await req('/seckill/' + activityId, { method: 'POST', token: bToken })
log('[11] 重复抢购（限购）', dupRes.status, JSON.stringify(dupRes.json))

let rl = null
for (let i = 0; i < 8; i++) { rl = await req('/seckill/' + activityId, { method: 'POST', token: bToken }) ; if (rl.status === 429) break }
log('[12] 限流', rl.status, JSON.stringify(rl.json))

const payRes = await req('/order/' + orderNo + '/pay', { method: 'POST', token: bToken })
log('[13] 支付', payRes.status, JSON.stringify(payRes.json))

log('[14] 我的订单', JSON.stringify((await req('/order/mine', { token: bToken })).json.data))
log('[15] 订单详情', JSON.stringify((await req('/order/' + orderNo, { token: bToken })).json.data))
log('[16] 我的活动', JSON.stringify((await req('/merchant/activity/mine', { token: mToken })).json.data))
log('[17] 活动订单', JSON.stringify((await req('/merchant/activity/' + activityId + '/orders', { token: mToken })).json.data))
const upRes = await req('/merchant/activity/' + activityId, { method: 'PUT', token: mToken, body: { totalStock: 80, startTime: isoLocal(now - 600000), endTime: isoLocal(now + 10800000) } })
log('[18] 修改活动', upRes.status, JSON.stringify(upRes.json))
