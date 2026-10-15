# FlashMart 限时抢购 · 前端

基于 Vue 3 + Vite 的抢购前台与商家后台，纯手写 CSS，不依赖任何 UI 组件库。
接口统一走 `/api` 前缀，开发环境由 Vite 代理到本机后端 `http://localhost:18090`。

## 环境要求
- Node.js 18 及以上
- 后端服务已在本机 18090 端口启动

## 启动
```bash
cd web
npm install          # 网络慢可加 --registry=https://registry.npmmirror.com
npm run dev          # 打开 http://localhost:5274
```

## 构建
```bash
npm run build        # 产物输出到 dist/
npm run preview      # 本地预览构建产物
```

## 页面
- `/login` 登录 / 注册（买家或商家）
- `/activities` 抢购专区：活动卡片、倒计时、剩余库存
- `/activity/:id` 活动详情：抢购、轮询下单结果、支付倒计时
- `/orders` 我的订单：状态徽章与待支付倒计时
- `/merchant` 商家后台：商品与活动管理（仅商家角色可见）

金额一律以「分」传输，展示时除以 100 转为元；登录态存 localStorage（`flashmart_token` / `flashmart_user`）。
