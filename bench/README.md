# FlashMart 压测 / 实验工具（bench）

纯 JDK 的单文件压测工具，用来把简历里那些数字（"500 并发不超卖"、"2000 请求 P99 xx ms"、
"缓存击穿只回源 1 次"……）在本机**真实跑出来**。工具只做两件事：发请求、把结果算成给人看的表格；
所有结论都来自服务端返回的真实数据，测不到就打印"未获取到"，绝不编数字。

文件：

| 文件 | 说明 |
| --- | --- |
| `Bench.java` | 单文件、无第三方依赖，JDK 17+ 可直接运行（JDK 21 上自动用虚拟线程） |
| `README.md` | 本文件：怎么跑、每个实验验证什么、输出怎么读 |
| `tokens.txt` | `--exp prepare` 生成的压测账号 token，一行一个（运行后才有） |

---

## 1. 前置条件

1. **后端已启动**：默认 `http://localhost:18090`（可用 `--base` 覆盖）。
2. **必须用 bench profile 启动**：压测接口 `/api/bench/**` 只在 bench profile 下注册。
   ```bash
   cd server && mvn spring-boot:run -Dspring-boot.run.profiles=bench
   # 或 java -jar flashmart-server.jar --spring.profiles.active=bench
   ```
3. **依赖组件**：MySQL / Redis / RocketMQ 已就绪（仓库根目录 `docker-compose.yml`）。
   抢购接口是"Redis 扣减 + MQ 异步下单"，**RocketMQ 没起来的话 orderCount 永远不会涨**。
4. **JDK 17+**（本机验证环境是 JDK 21）。

> **鉴权（容易踩的坑）**：后端拦截器覆盖了 `/api/**`，所以 **`/api/activity/**`、`/api/bench/**` 也都要带 token**。
> 工具会自动登录演示买家 `13911110001 / 123456` 作为压测调用方，不需要手工准备。
> 另外后端对登录接口按 IP 限流 20 次/秒，`prepare` 默认每次登录间隔 60ms 来避开它。

---

## 2. 怎么跑

```bash
cd flashmart/bench

# 编译（可选，直接 java Bench.java 也行）
javac Bench.java

java Bench.java --help                              # 看用法
java Bench.java --exp prepare --users 60            # 1. 批量注册/登录，写 tokens.txt
java Bench.java --exp oversell --stock 100 --users 500   # 2. 500 并发抢 100 件，验证不超卖
java Bench.java --exp oneuser --times 20            # 3. 同一用户并发 20 次
java Bench.java --exp throughput --mode async --concurrency 50 --requests 2000
java Bench.java --exp throughput --mode sync  --concurrency 50 --requests 2000
java Bench.java --exp cache-miss --concurrency 100  # 5. 缓存击穿
java Bench.java --exp cache-penetrate --activity 99999 --times 200   # 6. 缓存穿透
java Bench.java --exp ratelimit                     # 7. 限流
java Bench.java --exp timeout                       # 8. 超时关单 + 库存回滚
java Bench.java --exp pay                           # 9. 支付 + 重复支付幂等
```

### 参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--base <url>` | `http://localhost:18090` | 服务地址 |
| `--exp <name>` | 无（必填） | 实验名，见上 |
| `--activity <id>` | `9001` | 活动 id（9003 未开始 / 9004 已结束） |
| `--users <n>` | prepare 200 / oversell 500 | 账号数 |
| `--stock <n>` | 超卖/一人多单/关单/支付 100；吞吐 1000000；击穿 9999 | 压测前 reset 的库存 |
| `--times <n>` | oneuser 20 / 穿透 200 | 次数 |
| `--mode <async\|sync>` | `async` | 吞吐实验走的接口 |
| `--concurrency <n>` | `50` | 并发数（吞吐是闭环工作线程数） |
| `--requests <n>` | `2000` | 吞吐实验的总请求数（闭环，不会一次性全丢出去） |
| `--phone / --password` | `13911110001 / 123456` | 真实链路实验用的演示买家 |
| `--start-phone` | `13900010000` | prepare 的起始手机号，递增 |
| `--login-interval-ms` | `60` | prepare 每次登录间隔（避开登录 IP 限流） |
| `--no-wait` / `--wait-ttl` | 关 / 31 秒 | 穿透实验是否等待空值标记（`__NULL__`，TTL 30 秒）过期 |
| `--utf8` | 关 | 输出强制 UTF-8 |

### 输出编码

工具默认沿用控制台编码（中文 Windows 是 GBK），所以在自己的 cmd/PowerShell 里直接跑中文是正常的。
如果把输出重定向到文件、或者控制台是 UTF-8（Git Bash / VS Code），加 `--utf8`：

```bash
java Bench.java --exp oversell --utf8 | tee oversell.log
```

表格按"中文占 2 列"对齐，所以中英混排也不会歪。

---

## 3. 每个实验在验证什么

### 实验 1 `prepare` —— 压测账号池
批量 `POST /api/auth/register`（手机号 `13900010000` 起递增，密码 123456，角色 BUYER），
返回"该手机号已注册"就改成登录；每个账号拿到 token 后写入 `tokens.txt`（一行一个）。
**验证**：账号池可用、token 可复用。**读法**：`拿到 token` 数应等于 `--users`。
后续 oversell 需要 `tokens.txt` 里的 token 数 ≥ `--users`，不够时会直接提示先跑 prepare。

### 实验 2 `oversell` —— 不超卖（核心实验）
`reset` 把库存设成 100 并清空该活动订单，然后用 **500 个不同用户**的 token 在栅栏上一起放行，
每人只抢 1 次。抢购接口只做"Redis Lua 扣减 + 投递 MQ"，订单是异步落库的，
所以工具会轮询 `/api/bench/stats/{id}` 直到 `orderCount` 收敛，然后打印：

```
totalStock / dbAvailableStock / redisAvailableStock / orderCount / ordersByStatus
```

**读法（三条 PASS）**：`orderCount == 成功数`（Redis 扣多少就落多少单）、
`orderCount <= totalStock`（没超卖）、两个库存都非负。另外"被抢光"应该正好等于 `users - stock`。

### 实验 3 `oneuser` —— 一人一单 + 限流
同一用户（演示买家）并发 `--times` 次抢购。**注意后端有两条拦截**：
限流（每人 1 秒 5 次，超出 429）在 Lua 限购校验**之前**，所以 20 并发时：
1 次成功、4 次 `每人限购 1 件，你已经抢到啦`、15 次 `HTTP 429 手速太快啦，歇一秒再抢`。
工具会额外做一段**每 1.2 秒 1 次**的匀速补充验证（绕开 1 秒窗口），
这时应该 0 次 429、5 次全部被"限购"拦下 —— 这才是"一人一单"本身的效果。

### 实验 4 `throughput` —— 闭环压测（async vs sync）
`async` 打 `/api/bench/seckill-async/{id}`（Lua 扣减 + MQ 异步下单），
`sync` 打 `/api/bench/seckill-sync/{id}`（同步扣库存 + 同步写订单，对照组）。
两种模式跑之前都先 `reset(stock=1000000, clearOrders=true)`，条件一致；
跑之前有 50 次预热（不计入统计）。**闭环模型**：`--concurrency` 个工作线程，
每个线程串行地领请求号发请求，直到总数达到 `--requests`，不是一次性把 2000 个请求全丢出去。
**读法**：成功率、平均/P50/P90/P95/P99（微秒+毫秒）、吞吐（全部/仅成功 req/s），
最后再打印一次 stats 看高压下有没有超卖。

### 实验 5 `cache-miss` —— 缓存击穿
① 先读一次详情让缓存生效 → ② 调 `POST /api/bench/reset/{id}?stock=9999&clearOrders=false`
（服务端会同时**删掉详情缓存**并**清零回源计数**，工具连不上 Redis，只能借这个接口清场）
→ ③ 100 个并发请求详情 → ④ 读 `/api/bench/cache-load-count?key=activity:detail:9001`。
**读法**：回源次数应远小于并发数（正常 1 次，"数据库压力下降 100 倍"）。
如果回源次数恰好等于并发数，工具会打印诊断提示（见下面"实测发现的坑"第 4 条）。

### 实验 6 `cache-penetrate` —— 缓存穿透
重置 `activity:detail:99999` 的回源计数，先探测一次（这一次会把"不存在"写进空值标记 `__NULL__`），
再并发查 200 次不存在的活动。**读法**：全部返回 `HTTP 200 + code=400 活动不存在`，
累计回源次数应为 **1**（第二次以后都命中空值标记，不再打数据库）。
空值标记 TTL 30 秒：如果 Redis 里已经有标记（上一次实验刚跑过），
工具会自动等待其过期后重来，保证"第一次查询回源 1 次"这件事真的被观察到（`--no-wait` 可跳过等待）。

### 实验 7 `ratelimit` —— 滑动窗口限流
第 1 阶段：同一用户 1 秒内并发 10 次 → 期望 `HTTP 200 × 5` + `HTTP 429 × 5`
（服务端配置 `maxRequests=5`），并通过服务端 message 看到 200 里面是 1 成功 + 4 限购。
第 2 阶段：每 1.2 秒 1 次共 5 次 → 期望 0 次 429，**限流只拦突发、不拦匀速**。

### 实验 8 `timeout` —— 超时关单 + 库存回滚
reset → 真实 `POST /api/seckill/{id}` 抢 1 次 → 轮询 `/api/seckill/result/{orderNo}` 到 `PENDING_PAY`
→ **不支付** → `POST /api/bench/force-timeout/{orderNo}`（延迟 1 秒投递关单消息）→ 等 3 秒
→ 打印订单状态与 stats 的库存变化。**读法**：状态 `PENDING_PAY → TIMEOUT`，
关单后 `redisAvailableStock` 和 `dbAvailableStock` 各 **+1**（库存退回池子）。
补充验证：关单后同一用户再抢会被"限购"拦下 —— 服务端的设计是**只回滚库存、不回滚已购记录**
（名额用掉就作废），工具如实打印这次结果。

### 实验 9 `pay` —— 支付链路与幂等
reset → 抢购 → 轮询到 `PENDING_PAY` → 支付 → **再支付一次**。
**读法**：第一次 `data.status=PAID / data.message=支付成功`；
第二次仍然 `PAID`，`data.message=订单已支付，无需重复支付`（幂等，不重复扣款）；
最终订单状态 `PAID`，库存不会被超时关单回滚。

---

## 4. 实测结果（本机真实跑出来的数字）

环境：后端 `http://localhost:18090`（bench profile）、JDK 21、Windows + Docker 组件。

| 实验 | 命令 | 结果 |
| --- | --- | --- |
| prepare | `--exp prepare --users 60` | 60/60 拿到 token；首次 59 新注册 + 1 已存在（14.07 s），复跑 60 个全部"已注册→改登录"（9.54 s） |
| oneuser | `--exp oneuser --times 20` | 成功 1 + 限购 4 + 429 15；匀速 5 次：0 个 429、5 个限购 |
| cache-penetrate | `--exp cache-penetrate --activity 99999 --times 200` | 200 次全部 `code=400 活动不存在`，**累计回源 1 次** |
| ratelimit | `--exp ratelimit` | 1 秒内 10 并发 → HTTP 200 × 5 / HTTP 429 × 5；1.2 秒匀速 5 次 → 全部通过 |
| oversell | `--exp oversell --stock 30 --users 60` | 成功 30 + 抢光 30；`orderCount=30 == 成功数`，`dbAvailableStock=0`、`redisAvailableStock=0`，无超卖 |
| throughput async | `--mode async --concurrency 50 --requests 2000` | 2000/2000 成功，152 req/s，P50 314 ms，P99 546 ms |
| throughput sync | `--mode sync --concurrency 50 --requests 2000` | 2000/2000 成功，38 req/s，P50 1291 ms，P99 1776 ms |
| cache-miss | `--exp cache-miss --concurrency 100` | 后端空载：100 并发 → 回源 **1** 次；后端被压时：回源 100 次（见下） |
| timeout | `--exp timeout` | `PENDING_PAY → TIMEOUT`，DB/Redis 库存各 +1（100→99→100） |
| pay | `--exp pay` | 两次支付都 `PAID`，第二次 message=`订单已支付，无需重复支付` |

**async vs sync（同样 50 并发 / 2000 请求 / stock=1000000）**：异步版吞吐约 4 倍、P50 约 1/4，
这就是"流量削峰"要证明的东西。

---

## 5. 实测发现的坑 / 与预期的偏差（都没改后端，如实记录）

1. **业务失败也是 HTTP 200**。`{code:400,message:"手慢了，已被抢光"}` 走的是 HTTP 200；
   只有限流是真正的 `HTTP 429`，未登录是 `HTTP 401`。工具按 `code + message` 分类，
   所以表格里既有"HTTP 状态"也有"业务结果"，读的时候别只看 HTTP 码。
2. **抢购限流是"每人 1 秒 5 次"，不是"每人 1 次"**。所以"同一用户并发 20 次"得到的是
   1 成功 + 4 限购 + 15 个 429，而不是 1 成功 + 19 限购。想看纯粹的"一人一单"，
   得用工具里那段 1.2 秒匀速的补充验证。
3. **`/api/bench/**` 和 `/api/activity/**` 都需要 token**（拦截器覆盖 `/api/**`，
   只放行了 `/api/auth/**`）。用 curl 直接打压测接口会得到 401。
4. **缓存击穿的互斥是"看运气"的**。服务端冷启动路径是 `SETNX 抢锁 + 没抢到锁的线程 sleep 60ms 后重读缓存`；
   如果抢到锁那次回源超过 60ms（后端正被 MQ 消费压着的时候很容易超过），
   其余线程会走"自己兜底回源"分支（这条分支**不加锁**），100 并发就变成 100 次回源。
   实测：后端空载 → 回源 1 次；后端被压 → 回源 100 次。这不是工具的问题，是可复现的服务端行为。
5. **长时间压测会让 `redisAvailableStock` 失真**。`SeckillStockService.prepare()` 用
   `SETNX(seckill:stock:init:{id}, TTL=10s)` 做"只预热一次"，但异步压测接口
   **每个请求都会调一次 prepare**；10 秒后锁过期，下一个请求就会拿**数据库的库存**
   把 Redis 计数器覆盖掉（数据库比 Redis 慢半拍）。另外，如果在 MQ 还有积压消息时调 `reset`，
   积压消息继续落库/关单回滚，会看到 `redisAvailableStock > totalStock`（实测出现过 9999 → 11679）。
   **跑统计类实验前，先等 `/api/bench/stats` 的 orderCount 不再增长**。
6. **跑完 async 压测要等 MQ 排空**。`reset` 只删数据库里的订单，不会撤回已经在 MQ 里的消息，
   所以紧接着跑下一个实验时可能冒出"多出来的订单"。工具在 `oversell / timeout / pay`
   里会在 reset 后盯 1 秒，发现又落库订单就打印提示。
7. **sync 模式在高并发下会打满数据库连接池**。第一次跑 `sync`（当时上一轮 async 的 MQ 积压还在消费，
   消费者 32 线程 + Hikari 池 20）出现 768 次 `HTTP 200 code=500 系统繁忙，请稍后重试`（成功率 61.6%）；
   等 MQ 排空后重跑就是 2000/2000 全成功。做 async/sync 对比时务必让两种模式的"背景噪音"一致。

---

## 6. 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `[错误] 无法连接后端 ...` | 后端没起、端口不对（`--base`）、或者没开 bench profile |
| 压测接口 401 | 演示账号登录失败（Redis 会话被清过）→ 用 `--exp prepare` 重建 token |
| `tokens.txt 里只有 N 个 token` | 先 `--exp prepare --users <需要数>` |
| `orderCount` 一直不收敛 | RocketMQ 没起 / 消费者积压，看后端日志；工具会打印等待了多少毫秒 |
| 穿透实验回源 0 次 | Redis 里已有 `__NULL__` 空值标记（TTL 30 秒），等它过期或加 `--no-wait` 直接看现状 |
| 中文乱码 | 加 `--utf8`（或 `chcp 65001`） |
| 数字不好看 | 不美化、不补数据；请在后端空载、MQ 排空的状态下重跑，并注明环境 |
