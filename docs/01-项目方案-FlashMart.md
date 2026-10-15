# FlashMart —— 限时抢购电商平台（项目方案）

> 本文只写代码里真实存在的东西：出现的每个类名、接口路径、字段名、Redis key 都能在 `server/src/main/java` 与 `server/src/main/resources` 里找到出处。
> 实测数字一律标注来源场景，没有测过的不写。

## 1. 一句话定位

面向买家与商家的限时抢购平台：**活动浏览 → Redis + Lua 原子扣库存与一人一单 → RocketMQ 异步落单削峰 → 模拟支付网关回调 → 延迟消息超时关单并回滚库存**，全链路围绕"高并发下不超卖、不重复下单、缓存不击穿、订单状态不打架"四件事设计。

## 2. 业务定位与角色

| 角色 | 能做什么 | 权限落点 |
| --- | --- | --- |
| 买家 BUYER | 浏览抢购活动列表与详情、抢购、支付、查看我的订单 | `SysUser.ROLE_BUYER`；订单查询按 `user_id` 过滤 |
| 商家 MERCHANT | 创建商品、创建/修改抢购活动、查看自己活动的订单 | `SysUser.ROLE_MERCHANT`；`ActivityService`、`ProductService` 校验归属 |

角色只有两个（`sys_user.role`：`BUYER` / `MERCHANT`），没有平台运营台，也没有店铺表 —— 商家就是 `sys_user` 里 role=MERCHANT 的一行，商品用 `product.merchant_id` 关联。

选这个业务的原因很简单：**抢购把"缓存、限流、消息队列、分布式一致性"四个问题压缩到了同一条主链路上**，而且每个问题都有可量化的验收标准（超卖数、回源次数、成功率、429 次数）。

## 3. 业务闭环（可演示的主线）

    商家登录 → 创建商品 → 创建抢购活动（同步预热 Redis 库存）
      → 买家登录 → 抢购专区看到活动卡片与倒计时 → 进详情页抢购
      → 服务端 Lua 扣减成功 → 返回 {orderNo, QUEUING} → 前端每 400ms 轮询
      → MQ 消费者落库 → 前端拿到 PENDING_PAY + 支付倒计时
      → 支付（模拟网关回调）→ 状态变 PAID
      → 另一条路径：不支付 → 延迟 30 秒消息到点 → 状态变 TIMEOUT + 库存回滚
      → 商家后台看到这个活动收到的订单与状态分布

整条线跑完，七个技术点全部被覆盖：抢购踩 Lua 扣减与限流，轮询踩异步落库，详情页踩逻辑过期缓存，商家改活动踩缓存一致性，超时关单踩延迟消息与乐观锁。

## 4. 技术栈

| 层 | 选型 | 出处 |
| --- | --- | --- |
| 后端 | SpringBoot 3.2.5 / JDK 17 / Spring AOP / Spring Validation | `server/pom.xml` |
| 持久层 | MyBatis-Plus 3.5.5（`mybatis-plus-spring-boot3-starter`）+ 手写注解 SQL | `pom.xml`、`SeckillOrderMapper` |
| 数据库 | MySQL 8（宿主机 13307 → 容器 3306） | `docker-compose.yml` |
| 缓存 | Redis 7.2（宿主机 6380，`requirepass redis123456`，AOF 开启） | `docker-compose.yml` |
| 消息 | RocketMQ 5.1.4（namesrv 9877，broker `listenPort=10912`），`rocketmq-spring-boot-starter` 2.3.0 | `docker-compose.yml`、`deploy/broker.conf` |
| 鉴权 | JJWT 0.12.5 + Redis 会话 + `AuthInterceptor` | `pom.xml`、`common/auth` |
| 密码 | `spring-security-crypto` 的 BCrypt（只引加密模块，不引整个 Spring Security） | `WebConfig#passwordEncoder` |
| 接口文档 | Knife4j 4.5.0（`knife4j-openapi3-jakarta-spring-boot-starter`），`http://localhost:18090/doc.html` | `pom.xml` |
| 前端 | Vue 3.5 + Vue Router 4 + Vite 6，手写 CSS，无 UI 组件库、无状态管理库，HTTP 用原生 `fetch` | `web/package.json`、`web/src/api.js` |
| 压测 | 纯 JDK 单文件 `bench/Bench.java`（无第三方依赖，含手写 JSON 解析器） | `bench/Bench.java` |

明确没有引入的：**MinIO、LangChain4j、Redisson**。本项目的加锁需求由"Lua 原子脚本 + 数据库唯一索引 + 条件更新"覆盖，不需要分布式锁组件；商品图片用 `product.cover_color` 色块代替，不需要对象存储。

## 5. 总体架构

    买家浏览器 (Vue3 + Vite, :5274)                商家浏览器 (同一个 SPA, /merchant 路由)
        │  /api 由 Vite dev server 代理到 :18090          │
        └──────────────────┬───────────────────────────────┘
                           ▼
    ┌─────────────────── SpringBoot 3.2.5 (:18090) ───────────────────────────┐
    │  TraceIdFilter ──► AuthInterceptor(JWT + Redis 会话) ──► UserContext     │
    │                                                                │        │
    │  AuthController      ─┐                                        │        │
    │  ActivityController  ─┤   SlidingWindowAspect ◄────────────────┤        │
    │  ProductController   ─┼──► Redis ZSet + Lua 滑动窗口限流        │        │
    │  SeckillController   ─┤    (GLOBAL / IP / USER 三维度)          │        │
    │  OrderController     ─┤                                        │        │
    │  MerchantController  ─┘                                        │        │
    │                                                                ▼        │
    │  SeckillService   ① 活动校验（逻辑过期缓存，不查库）                      │
    │                   ② Redis + Lua 原子扣库存 + 一人一单                    │
    │                   ③ 生成 orderNo  ④ 发 MQ 立刻返回（不写数据库）          │
    │                                                                         │
    │  ActivityService  读：逻辑过期缓存 + 空值标记；库存单独实时读 Redis       │
    │                   写：先更新数据库 → 再删缓存（CacheEvictService 补偿）   │
    │  OrderService     支付用乐观锁、关单用状态条件更新、库存双向回滚          │
    └─────────────────────────────────────────────────────────────────────────┘
             │ produce                                   ▲ consume
             ▼                                           │
    ┌───────────── RocketMQ 5.1.4 (:9877 / broker :10912) ────────────────────┐
    │ topic_order_create   cg_order_create   抢购下单：幂等 → 扣库 → 落单 → 投延迟消息 │
    │ topic_order_timeout  cg_order_timeout  延迟 30s 到点：关单 + 回滚库存     │
    │ topic_cache_evict    cg_cache_evict    删缓存补偿（UPDATE）与延迟双删     │
    └─────────────────────────────────────────────────────────────────────────┘
             │
             ▼
    MySQL 8 (:13307)                       Redis 7.2 (:6380)
    sys_user / product / flash_activity     activity:detail:{id}    逻辑过期 + 空值标记
    seckill_order / mq_consume_log          seckill:stock:{id}      库存真身（Lua 扣减）
    cache_sync_task                         seckill:bought:{id}     一人一单 Hash
                                            rate:{key}:{维度}:{身份} 滑动窗口 ZSet
                                            session:token:{userId}   登录会话

数据流一句话说明：**买家侧的写请求只到 Redis 与 MQ 就返回，数据库的写入发生在消费端；商家侧的写请求先落数据库，再删缓存。**

## 6. 七个技术点与代码落点

### 技术点 1：Redis + Lua 原子扣减库存与一人一单

- **痛点**：抢购接口是典型的"读-判断-写"三步操作。拆成三条 Redis 命令，并发下一定出两种事故：① 两个请求都读到库存 1 → 双双扣减 → 超卖；② 同一用户的两个请求都读到"未购买过" → 落下两单。
- **方案**：把"判断库存 + 判断限购 + 扣库存 + 记录已购"合成一次 `EVALSHA`，Redis 单线程执行天然互斥。
  - 脚本：`server/src/main/resources/lua/seckill_stock.lua`，KEYS = 库存 key + 已购 Hash key，ARGV = userId + 每人限购数；
  - 返回值语义：`>=0` 扣减后的剩余库存、`-1` 库存不足、`-2` 超过限购、`-3` 活动未预热，常量定义在 `SeckillStockService.SOLD_OUT / OVER_LIMIT / NOT_PREPARED`；
  - 库存预热是幂等的（`SeckillStockService.prepare` 用 `SETNX seckill:stock:init:{id}` + 10 秒 TTL 做互斥），避免并发把已扣减的库存覆盖回去；
  - **数据库侧兜底**：`seckill_order` 上的 `UNIQUE KEY uk_user_activity (user_id, activity_id)`。`OrderService.createOrder` 捕获 `DuplicateKeyException` 后把刚扣掉的数据库库存还回去并返回 `null`，消费端据此走 `stockService.rollbackAll`（库存与已购记录一起还）。
- **关键类**：`SeckillStockService`、`SeckillService`、`ActivityCacheKeys`、`lua/seckill_stock.lua`、`OrderService`、`schema.sql`。
- **实测口径**：同一用户对同一活动并发抢购，只有 1 次成功进入队列，其余被"每人限购 1 件"拦下（`bench/Bench.java --exp oneuser`；200 并发不同用户抢 100 件时 `orderCount == 成功数 <= totalStock`，Redis 与数据库剩余库存均非负）。

### 技术点 2：RocketMQ 异步下单削峰

- **痛点**：抢购的瞬时并发是平时的几十倍，如果每个请求都在事务里扣数据库库存 + 写订单，数据库连接池（`hikari.maximum-pool-size: 20`）会瞬间被打满，接口耗时从毫秒级涨到秒级，超时后用户重试又把压力放大一倍。
- **方案**：抢购主链路只做四件事 —— 校验活动（读缓存）、Lua 扣减、生成订单号、发 MQ，**全程不碰数据库写**；落库、扣数据库库存、投递延迟关单消息全部在 `OrderCreateConsumer` 里完成。
  - `MqProducer.sendAsync` 用 `rocketMQTemplate.asyncSend` + `SendCallback`，主线程不等 broker 回执；
  - 消息体 `MqEvents.OrderCreateEvent(orderNo, userId, activityId, productId, flashPrice)` —— 只带业务主键与下单必要字段，消费端不回查业务表格式；
  - 消费者 `@RocketMQMessageListener(consumeThreadMax = 32)`，并发度远低于用户点击并发，这就是"削峰"的实际含义：**数据库只面对 MQ 的消费速度**；
  - 消费端第一步是 `MqIdempotentRepository.tryAcquire(msgId, ...)`，靠 `mq_consume_log.uk_msg_id` 唯一索引做幂等，重复消息直接丢弃。
- **关键类**：`SeckillService`、`MqProducer`、`MqTopics`、`MqEvents`、`OrderCreateConsumer`、`MqIdempotentRepository`。
- **实测口径**：抢购接口返回耗时 **28ms**；接口返回后约 **157ms** 订单落库（前端轮询 `/api/seckill/result/{orderNo}` 从 `QUEUING` 变为 `PENDING_PAY`）。
- **对照组**：`BenchService.seckillSync`（接口 `POST /api/bench/seckill-sync/{activityId}`）走"同步扣库存 + 同步写订单"，与异步版共用同一套数据库操作，唯一差别是不发消息。异步/同步的 P99 与吞吐对比数字**待补**，采集入口已就绪：`java Bench.java --exp throughput --mode async|sync`。

### 技术点 3：逻辑过期 + 异步重建防缓存击穿

- **痛点**：活动详情是热点 key。用"过期即删除"的策略，key 一到期，N 个并发请求会同时发现未命中并一起打到数据库；用互斥锁方案，则所有请求要排队等重建完成，接口耗时被重建时间绑架。
- **方案**：`RedisCacheClient.getWithLogicExpire(key, type, logicTtlSeconds, loader)` —— 缓存 value 不是裸数据，而是一个包装体 `{"expireAt": 毫秒时间戳, "data": {...}}`：
  - 物理 TTL = 逻辑 TTL × 3（`writeWithLogicExpire` 里写死 `logicTtlSeconds * 3`），保证"逻辑已过期但数据还在"，旧值能继续兜住流量；
  - 逻辑过期时**不删 key**，当前请求直接返回旧值，同时用 `SETNX cache:rebuild:lock:{key}`（TTL 10 秒）挑一个线程提交到 `rebuildPool`（4 线程 daemon 池）异步重建；
  - 完全未命中（冷启动或被删）走另一条分支：同样用 SETNX 只放一个线程回源，其余线程等 60ms 后重读缓存，读不到才自己兜底回源。
- **关键类**：`RedisCacheClient`、`ActivityService.detail / detailForSeckill`、`ActivityCacheVO`。
- **实测口径**：缓存被删后 100 并发查详情，全部成功返回，且只触发 **1 次**异步重建、回源计数为 1；冷启动场景 100 并发请求只回源数据库 **1 次**（加互斥之前是 100 次）。
- **边界**：异步重建是"尽力而为"——重建失败只打日志，不影响本次请求返回旧值；重建期间返回的是旧数据，所以**活动详情里有"剩余库存"这种秒级变化的字段时不能放进这个缓存**（见第 7 节）。

### 技术点 4：空值缓存防穿透

- **痛点**：恶意脚本拿不存在的 activityId 反复打详情接口，每次请求都绕过缓存直达数据库。这类请求不占库存，成本却全部落在数据库上。
- **方案**：同一个类里，回源结果为 `null` 时不写数据、写一个常量标记 `NULL_MARK = "__NULL__"`，TTL 取 `flashmart.cache.null-value-ttl-seconds`（默认 **30 秒**）；下次命中这个标记直接返回 `null`，不再回源。TTL 之所以短，是因为"数据确实不存在"这件事本身可能变化（商家随时可能新建活动）。
- **关键类**：`RedisCacheClient`（`NULL_MARK` 常量、`getWithLogicExpire` 的第 1 个分支、未命中分支写标记、异步重建分支写标记）。
- **实测口径**：查询一个不存在的活动 200 次，累计回源数据库 **1 次**（`bench/Bench.java --exp cache-penetrate --activity 99999 --times 200`，校验 `loadCount <= 1`）。
- **边界**：空值缓存只挡"重复查同一个不存在的 key"。换成每次随机 id 的攻击，仍然会穿透 —— 真正的解法是布隆过滤器或参数格式校验，本项目没有做，属于第 10 节记录的边界。

### 技术点 5：Cache Aside + MQ 补偿重试 + 延迟双删 + TTL 兜底

- **痛点**："先更库再更缓存"在并发下会出现"后写的旧值覆盖先写的新值"；"先删缓存再更库"在读写并发下会出现"读请求把旧值回填进缓存"；删缓存这条网络调用本身还可能失败。
- **方案**：`CacheEvictService` 四层递进：
  1. **Cache Aside**：先更新数据库，再删缓存（`ActivityService.update` 调 `evictWithCompensation`）；删而不是更新，下一次读自然会回源拿到新值；
  2. **失败补偿**：`evict` 返回 false 时，往 `cache_sync_task` 落一条 `PENDING` 记录，并投递 `topic_cache_evict`（reason=`UPDATE`）让 `CacheEvictConsumer` 重试删除，成功后把任务置为 `SUCCESS`；
  3. **延迟双删**：无论第一次删除成功与否，都再投一条延迟消息（reason=`DOUBLE_DELETE`），延迟一段时间后再删一次，清掉"读请求在两次删除之间回填的旧值"；
  4. **TTL 兜底**：即使补偿和双删全部失败，缓存 key 的物理 TTL 最长也就是逻辑 TTL 的 3 倍（默认 60×3 = 180 秒），到期自动消失，最终仍然一致。
- **关键类**：`CacheEvictService`、`CacheEvictConsumer`、`CacheSyncTask` / `CacheSyncTaskMapper`、`RedisCacheClient.evict`、`MqTopics.CACHE_EVICT`。
- **实测口径**：商家修改活动（改库存/时间）后，活动详情接口立刻返回新的标题/时间；`cache_sync_task` 表在正常路径下不产生记录（删除成功即返回），只有 Redis 异常时才会落下 `PENDING`。

### 技术点 6：Redis ZSet + Lua + AOP 注解的滑动窗口限流

- **痛点**：抢购接口是最容易被脚本刷的入口，刷进来一次就消耗一份库存；登录接口被撞库也只需要换手机号重放。
- **方案**：注解 + 切面 + Lua 三层：
  - 注解 `@SlidingWindowLimit(key, windowSeconds, maxRequests, dimension, message)`，维度枚举 `Dimension.GLOBAL / IP / USER`；
  - 切面 `SlidingWindowAspect` 拼 key：`rate:{业务键}:{维度}:{身份}`，身份一律取自 `UserContext`（服务端上下文），**绝不用请求参数** —— 否则攻击者传别人的 userId 就能把别人的额度耗光；
  - `SlidingWindowLimiter` 执行 `lua/sliding_window.lua`：`ZREMRANGEBYSCORE` 清理窗口外成员 → `ZCARD` 统计 → 超限返回 0、否则 `ZADD` 并 `PEXPIRE`。三步必须原子，否则并发下会"都统计到没超限、于是都放行"；
  - 成员用 `traceId + 随机 UUID`，同一毫秒内的并发请求不会互相覆盖；
  - **降级**：Redis 抛异常时直接放行（`tryAcquire` catch 后 return true）——限流是保护手段，不能因为限流组件自己挂了把业务熔断。
- **使用点**：`SeckillController.seckill`（USER，1 秒 5 次）、`OrderController.pay`（USER，1 秒 3 次）、`AuthController.login`（IP，1 秒 20 次）。
- **关键类**：`SlidingWindowLimit`、`SlidingWindowAspect`、`SlidingWindowLimiter`、`lua/sliding_window.lua`。
- **实测口径**：同一用户 1 秒内并发 10 次抢购，正好放行 **5** 次（配置 `maxRequests=5`）、其余返回 HTTP 429；改成每 1.2 秒 1 次匀速请求 5 次，**5/5 全部通过** —— 滑动窗口只拦突发，不拦匀速。

### 技术点 7：RocketMQ 延迟消息超时关单 + 库存回滚 + 状态条件更新

- **痛点**：用户抢到名额却不付款，库存被占死；同时"支付回调"和"超时关单"可能在毫秒级同时发生，不控制的话会出现"已经支付了又被关单、库存被退回去"。
- **方案**：
  - 下单成功后，`OrderCreateConsumer` 立即投递 `topic_order_timeout`；延迟档位由 `MqProducer.nearestDelayLevel(orderService.timeoutSeconds())` 计算 —— RocketMQ 只支持固定档位（`DELAY_LEVEL_SECONDS`：1s/5s/10s/30s/1m…），30 秒对应 level 4；
  - `OrderTimeoutConsumer` 消费到点后调 `OrderService.timeoutOrder(orderNo)`；
  - 关单 SQL `SeckillOrderMapper.timeoutIfPending`：`WHERE order_no = ? AND status = 'PENDING_PAY'`，受影响行数为 0 说明订单已经支付或已被关过，**直接跳过、不回滚库存**；
  - 只有关单成功才做双端回滚：`FlashActivityMapper.increaseStock`（`WHERE available_stock < total_stock` 防止回滚超过总库存）+ `SeckillStockService.rollbackStock`（Redis `INCR`）；
  - 支付侧 `SeckillOrderMapper.payWithVersion`：`WHERE order_no = ? AND status = 'PENDING_PAY' AND version = ?` 且 `version = version + 1`，这就是"乐观锁"落点 —— 读到的 version 在写回时已经被别人改过，更新就会失败，避免"支付回调与关单互相覆盖"。
- **关键类**：`OrderCreateConsumer`、`OrderTimeoutConsumer`、`OrderService`、`SeckillOrderMapper`、`FlashActivityMapper`、`MqProducer`。
- **实测口径**：延迟 **30 秒**的消息到点后关单成功、Redis 与数据库库存各回滚 +1，订单状态分布为 `PAID:1 / TIMEOUT:1`（一笔支付成功、一笔超时关单，两条路径互不干扰）。关单后同一用户再抢会被"每人限购 1 件"拦下 —— 这是设计如此，见第 11 节 FAQ 第 9 条。

## 7. 数据模型（核心表与关键索引）

六张表，DDL 全部在 `server/src/main/resources/db/schema.sql`。金额一律用**分**存 `INT`，避免浮点误差。

| 表 | 关键字段 | 索引 / 约束 | 为什么这样设计 |
| --- | --- | --- | --- |
| `sys_user` | phone, password(BCrypt), nickname, role, status, deleted | `uk_phone` | 手机号即登录名，唯一索引把"重复注册"交给数据库而不是"先查再插"的应用逻辑 |
| `product` | merchant_id, title, description, origin_price(分), cover_color, status | `idx_merchant` | `cover_color` 是演示替代品：用色块代替商品图，省掉对象存储依赖；按商家查列表走索引 |
| `flash_activity` | product_id, merchant_id, flash_price, total_stock, available_stock, limit_per_user, start_time, end_time, status, version | `idx_product`、`idx_merchant`、`idx_time(start_time,end_time)` | `idx_time` 服务"活动列表按开始时间排序"；`available_stock` 是数据库侧剩余库存，由消费者异步回写；`version` 为活动编辑预留 |
| `seckill_order` | order_no, user_id, activity_id, product_id, flash_price, status, pay_trade_no, pay_time, cancel_time, cancel_reason, version | `uk_order_no`、**`uk_user_activity(user_id, activity_id)`**、`idx_user_status`、`idx_activity` | `uk_order_no` 是对外业务主键；**`uk_user_activity` 是"一人一单"的最后一道防线**——即使 Redis 判断全部失效，数据库也不可能落下同一用户的第二单；`idx_user_status` 服务"我的订单"，`idx_activity` 服务商家查活动订单 |
| `mq_consume_log` | msg_id, topic, biz_key, status, retry_count, error_msg | **`uk_msg_id`**、`idx_status` | 插入成功 = 首次消费，冲突 = 重复消息；`status` 区分 CONSUMING/SUCCESS/FAILED，FAILED 的消息允许重试 |
| `cache_sync_task` | cache_key, status, retry_count, error_msg | `idx_status` | 删缓存失败时的落库凭据；消费者按 `status = PENDING` 回写结果 |

三个容易踩的点，代码里都做了处理：

1. **逻辑删除与唯一索引会打架**。项目全局启用了逻辑删除（`mybatis-plus.global-config.db-config.logic-delete-field: deleted`），被标记 `deleted=1` 的订单行仍然占着 `uk_user_activity`，会让同一用户之后**再也下不了单**。所以压测重置走的是物理删除 `SeckillOrderMapper.deleteByActivityPhysically`，而不是 MP 的 `deleteById`。
2. **库存不能被扣成负数**。`FlashActivityMapper.decreaseStock` 的 `WHERE id = ? AND available_stock > 0` 是数据库层的最后一道闸。
3. **剩余库存不进缓存**。`ActivityCacheVO` 里刻意没有 `availableStock` 字段，库存一律实时读 Redis（`ActivityService.currentStock`），否则会出现"页面显示有货但抢不到"。

## 8. 关键流程

### 8.1 抢购下单（同步返回 + 异步落库）

    买家/Vue            SeckillController        Redis              RocketMQ      OrderCreateConsumer     MySQL
       │                       │                   │                   │                │                │
       │ POST /api/seckill/{id}│                   │                   │                │                │
       ├──────────────────────►│                   │                   │                │                │
       │                       │ ① 滑动窗口限流     │                   │                │                │
       │                       ├─ ZSet+Lua ───────►│                   │                │                │
       │                       │  超限 → HTTP 429  │                   │                │                │
       │                       │                   │                   │                │                │
       │                       │ ② 活动校验（逻辑过期缓存，不查库）         │                │                │
       │                       ├─ GET activity:detail:{id} ────────────►│                │                │
       │                       │   未命中 → SETNX 单线程回源               │                │                │
       │                       │   逻辑过期 → 返回旧值 + 异步重建           │                │                │
       │                       │   不存在 → 命中 __NULL__ → "活动不存在"    │                │                │
       │                       │ ③ 预热库存（幂等）+ Lua 原子扣减           │                │                │
       │                       ├─ EVALSHA seckill_stock.lua ───────────►│                │                │
       │                       │   GET stock / HGET bought / DECR / HINCRBY                │                │
       │                       │◄─ 剩余库存 | -1 抢光 | -2 限购 | -3 未预热 ─┤                │                │
       │                       │                   │                   │                │                │
       │                       │ ④ 生成 orderNo → 异步发消息（不等回执）    │                │                │
       │                       ├──────────────────────────────────────►│                │                │
       │◄── {orderNo, QUEUING} ┤                   │                   │                │                │
       │    (实测 28ms 返回)    │                   │                   ├─ topic_order_create ─►│                │
       │                       │                   │                   │                │ 幂等 tryAcquire │
       │                       │                   │                   │                ├───────────────►│ mq_consume_log
       │                       │                   │                   │                │ 扣 DB 库存      │
       │                       │                   │                   │                ├───────────────►│ flash_activity
       │                       │                   │                   │                │ INSERT 订单     │
       │                       │                   │                   │                ├───────────────►│ seckill_order
       │                       │                   │                   │◄─ 延迟消息 level4(30s) ─┤        │
       │ GET /api/seckill/result/{orderNo}（前端每 400ms 轮询一次，最多 15 秒）              │                │
       ├──────────────────────►│ SELECT ... WHERE order_no = ? ───────────────────────────────────────────►│
       │◄── PENDING_PAY / PAID / TIMEOUT ──────────────────────────────────────────────────────────────────┤
       │    (实测：接口返回后约 157ms 订单落库)   order 为 null 时返回 state=QUEUING                        │

失败分支：数据库库存不足（`decreaseStock` 返回 0）或落库异常 → 消费者 `catch` 里先 `stockService.rollbackAll` 把 Redis 库存与该用户的已购记录一起还回去，标记 `mq_consume_log` 为 FAILED，再抛异常交给 RocketMQ 阶梯重试。

### 8.2 超时关单（延迟消息 → 条件更新 → 双端回滚）

    RocketMQ(延迟 30s)   OrderTimeoutConsumer     OrderService      SeckillOrderMapper   FlashActivityMapper    Redis
          │                      │                     │                   │                   │              │
          │ topic_order_timeout  │                     │                   │                   │              │
          ├─────────────────────►│ 幂等 tryAcquire      │                   │                   │              │
          │                      ├────────────────────►│                   │                   │              │
          │                      │                     │ SELECT by order_no│                   │              │
          │                      │                     ├──────────────────►│                   │              │
          │                      │                     │                   │                   │              │
          │                      │                     │ UPDATE SET status='TIMEOUT', cancel_time=NOW(),         │
          │                      │                     │   cancel_reason='TIMEOUT', version=version+1            │
          │                      │                     │  WHERE order_no=? AND status='PENDING_PAY'              │
          │                      │                     ├──────────────────►│                   │              │
          │                      │                     │◄── affected ──────┤                   │              │
          │                      │                     │                   │                   │              │
          │                      │   affected == 0 ──► 已支付 / 已被关过，直接跳过，不回滚库存  │              │
          │                      │                     │                   │                   │              │
          │                      │   affected == 1 ──► 双端回滚库存                            │              │
          │                      │                     ├──────────────────────────────────────►│              │
          │                      │                     │  available_stock + 1                 │              │
          │                      │                     │  (WHERE available_stock < total_stock)│             │
          │                      │                     ├─────────────────────────────────────────────────────►│
          │                      │                     │  INCR seckill:stock:{id}             │              │
          │                      │                     │  × 不回滚 seckill:bought:{id} 的已购记录              │
          │                      │                     markSuccess(msgId)                     │              │

支付与关单的竞争：两者都以 `status = 'PENDING_PAY'` 为前置条件，支付额外校验 `version`。谁先更新成功，另一个 `affected = 0` 直接失败返回，库存只可能被回滚一次。

### 8.3 缓存一致性（先更库 → 删缓存 → 补偿 + 延迟双删 → TTL 兜底）

    商家/Vue        MerchantController    ActivityService    MySQL      CacheEvictService   RocketMQ   CacheEvictConsumer   Redis
       │                   │                  │              │               │              │              │              │
       │ PUT /api/merchant/activity/{id}       │              │               │              │              │              │
       ├──────────────────►│ 归属校验(merchant_id)│              │               │              │              │              │
       │                   ├─────────────────►│ ① UPDATE flash_activity      │              │              │              │
       │                   │                  ├─────────────►│               │              │              │              │
       │                   │                  │              │               │              │              │              │
       │                   │                  │ ② evictWithCompensation(key) │              │              │              │
       │                   │                  ├─────────────────────────────►│ DEL activity:detail:{id}    │              │
       │                   │                  │              │               ├──────────────────────────────────────────►│
       │                   │                  │              │               │              │              │              │
       │                   │                  │              │  删除失败 ────►│ INSERT cache_sync_task(PENDING)             │
       │                   │                  │              │               │ ──UPDATE 消息──►│              │              │
       │                   │                  │              │               │              ├─────────────►│ 重试 DEL ───►│
       │                   │                  │              │               │              │              │ 成功→任务置 SUCCESS
       │                   │                  │              │               │              │              │              │
       │                   │                  │              │  无论成败 ────►│ ──DOUBLE_DELETE 延迟消息(最小档 1s)──►│
       │                   │                  │              │               │              ├─────────────►│ 1 秒后再 DEL ►│
       │                   │                  │              │               │              │              │              │
       │                   │                  │ ③ prepareStock 重新预热库存   │              │              │              │
       │                   │                  ├─ SET seckill:stock:{id} ─────────────────────────────────────────────────►│
       │                   │                  │              │               │              │              │              │
       │◄── 200 OK ────────┤                  │              │               │              │              │              │

买家下次读活动详情：逻辑过期缓存未命中 → SETNX 单线程回源数据库 → 拿到新值 → 写回缓存并带上新的 `expireAt`。

## 9. 接口清单（逐条核对 Controller）

统一响应体 `R<T>`：成功 `{"code":0,"message":"success","data":{...}}`；业务失败 `code` 为 400/401/403/429/500，其中**只有 429 会把 HTTP 状态码也置为 429**（`GlobalExceptionHandler.handleBiz`），其余业务异常 HTTP 仍为 200、由前端按 `code != 0` 处理。登录拦截器拒绝时直接返回 HTTP 401。

鉴权规则见 `WebConfig#addInterceptors`：拦截 `/api/**`，放行 `/api/auth/**`、`/doc.html`、`/webjars/**`、`/v3/api-docs/**`。**除注册登录外的所有接口都需要请求头 `Authorization: Bearer <token>`**。

| 方法 | 路径 | 说明 | 限流 | 代码落点 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | 注册（role 默认 BUYER，可传 MERCHANT） | — | `AuthController.register` |
| POST | `/api/auth/login` | 登录：签发 JWT + 会话写 Redis（24h，拦截器滑动续期） | IP 维度 1 秒 20 次 | `AuthController.login` |
| GET | `/api/activity/list?keyword=` | 抢购活动列表（status=1，按 start_time 升序，limit 30） | — | `ActivityController.list` |
| GET | `/api/activity/{id}` | 活动详情（逻辑过期缓存 + 剩余库存实时读 Redis + serverTime 供前端校时） | — | `ActivityController.detail` |
| GET | `/api/product/list` | 在售商品列表（status=1，limit 50） | — | `ProductController.list` |
| POST | `/api/seckill/{activityId}` | 抢购：返回 `{orderNo, state:"QUEUING", message}` | USER 维度 1 秒 5 次 | `SeckillController.seckill` |
| GET | `/api/seckill/result/{orderNo}` | 轮询抢购结果（order 未落库时返回 `state=QUEUING`） | — | `SeckillController.result` |
| GET | `/api/order/mine` | 我的订单（按创建时间倒序，limit 50） | — | `OrderController.mine` |
| GET | `/api/order/{orderNo}` | 订单详情（含 `payDeadline` 支付截止时间） | — | `OrderController.detail` |
| POST | `/api/order/{orderNo}/pay` | 支付（模拟支付网关回调，乐观锁状态流转，重复支付幂等返回） | USER 维度 1 秒 3 次 | `OrderController.pay` |
| POST | `/api/merchant/product` | 创建商品 | — | `MerchantController.createProduct` |
| GET | `/api/merchant/product/mine` | 我店铺的商品 | — | `MerchantController.myProducts` |
| POST | `/api/merchant/activity` | 创建活动并同步预热 Redis 库存 | — | `MerchantController.createActivity` |
| PUT | `/api/merchant/activity/{id}` | 修改活动（改库存/时间 → 先更库再删缓存并重新预热） | — | `MerchantController.updateActivity` |
| GET | `/api/merchant/activity/mine` | 我发布的活动 | — | `MerchantController.myActivities` |
| GET | `/api/merchant/activity/{id}/orders` | 某活动收到的订单（非活动所属商家返回 403） | — | `MerchantController.activityOrders` |

压测专用接口（`BenchController` 与 `BenchService` 都标了 `@Profile("bench")`，**正常启动不注册**，需 `--spring.profiles.active=bench`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/bench/reset/{activityId}?stock=&clearOrders=` | 重置库存（DB + Redis）、清空已购记录、物理删订单、清零回源计数 |
| POST | `/api/bench/seckill-async/{activityId}?syntheticUserId=` | 异步版抢购（合成用户，跳过一人一单，走真实 Lua + MQ 链路） |
| POST | `/api/bench/seckill-sync/{activityId}?syntheticUserId=` | **同步对照组**：同步扣库存 + 同步写订单，不发消息 |
| GET | `/api/bench/stats/{activityId}` | 总库存 / DB 剩余 / Redis 剩余 / 订单数 / 状态分布 / 回源计数 |
| POST | `/api/bench/force-timeout/{orderNo}` | 立即投递关单消息（延迟 1 秒），用于快速验证超时关单 |
| GET | `/api/bench/cache-load-count?key=` | 查询某个缓存 key 的累计回源次数 |
| POST | `/api/bench/cache-load-count/reset?key=` | 重置回源计数 |

## 10. 项目边界（明确不做什么）

| 不做的事 | 原因 | 替代做法 |
| --- | --- | --- |
| 分布式事务（TCC / Seata / 事务消息） | 抢购链路可接受最终一致，且真正的强一致点在"库存不能扣成负数"，用更便宜的手段就能保证 | Redis Lua 原子扣减 + `uk_user_activity` 唯一索引 + `mq_consume_log` 幂等 + 消费者失败回滚（`rollbackAll`） |
| 分库分表 | 当前数据量与 QPS 不支撑，引入后只剩路由成本 | 索引设计（`uk_user_activity`、`idx_user_status`、`idx_activity`）+ `limit` 分页 |
| 推荐算法 / 个性化排序 | 与项目的技术目标无关，属于另一个技术栈 | 活动列表按 `start_time` 升序、标题关键词 `like` 过滤 |
| 真实支付 | 接三方支付需要资质与商户号，且会引入与本项目无关的外部不确定性 | 模拟支付网关回调：`OrderService.pay` 本地生成 `pay_trade_no` 后直接做状态流转，幂等与并发控制是真实的 |
| 退款、物流、购物车、优惠券、多商品订单 | 与"高并发抢购链路"这个核心问题无关 | 订单模型保持单商品（一条订单 = 一个 activityId + 一个 productId + 一份 flashPrice） |
| 布隆过滤器 | 空值缓存在本项目的数据规模下已经够用 | `NULL_MARK` 空值缓存（TTL 30 秒）；随机 id 攻击仍会穿透，属于已知边界 |
| 分布式锁 | 本项目的所有竞态都能用"原子脚本 + 唯一索引 + 条件更新"解决，引入 Redisson 只会多一个需要运维的组件 | Lua 原子脚本、`SETNX` 互斥（缓存重建）、数据库条件更新 |
| 对象存储（MinIO 等） | 商品图不是本项目的技术重点 | `product.cover_color` 色块，前端直接渲染颜色 |
| 死信监控与重放 | 当前只有 `%DLQ%cg_order_create` 这个常量（`MqTopics.DLQ_ORDER_CREATE`），没有消费者与后台接口 | 消费失败先走 RocketMQ 阶梯重试；超过重试次数后进死信，目前只能去 Dashboard 人工处理，属于待补项 |
| 管理后台 / 运营台 | 角色只有买家与商家 | 商家工作台（`/merchant` 路由 + `MerchantController`）已覆盖商品与活动维护 |

## 11. 设计决策与取舍记录（FAQ）

**1. 为什么用 Lua 脚本，不用分布式锁？**
结论：因为这里要保护的是"一次判断+一次扣减"的**原子性**，不是"一段临界区"。
理由：分布式锁解决的是"多个进程不能同时执行一段逻辑"，代价是加锁/解锁两次网络往返 + 锁超时与续期的复杂度（WatchDog、锁误删）。而"读库存 → 扣库存"需要的只是这几条命令之间不被穿插，Redis 单线程执行一段 Lua 天然满足，一次 RTT 就够，没有锁泄漏问题。
边界：Lua 只解决 Redis 内部原子性。跨 Redis 与数据库的原子性它管不了 —— 所以数据库侧还要 `uk_user_activity` 与 `available_stock > 0` 兜底。

**2. 为什么用乐观锁，不用悲观锁（`SELECT ... FOR UPDATE`）？**
结论：支付回调与超时关单是**低冲突**场景，乐观锁的失败成本远低于悲观锁的持有成本。
理由：同一笔订单被并发修改的概率极低，绝大多数请求一次就能成功。`payWithVersion` 只多带一个 `version = ?` 条件，失败方返回 0 行、抛"订单状态已变化，请刷新后查看"，用户重试即可；悲观锁则要在事务里持有行锁到提交，热点活动下会把连接池拖慢。
边界：乐观锁不适合高冲突场景（大量请求反复失败重试反而更慢）；本项目真正的高冲突点在库存，那里用的是 Redis 原子扣减，不是数据库乐观锁。
补充说明：支付用的是"状态 + version"双条件更新；**关单只用状态条件更新**（`timeoutIfPending` 不带 version）—— 因为关单是"从待支付出发的一次性动作"，用状态本身做 CAS 就足够，重复投递的关单消息不会造成第二次回滚。

**3. 为什么用滑动窗口，不用令牌桶？**
结论：这两个算法的适用场景不同，本项目要的是"精确限次"，不是"允许突发"。
理由：令牌桶允许桶容量范围内的突发（桶里攒了 10 个令牌就能瞬间放行 10 次），适合保护下游资源；滑动窗口统计的是"最近 N 秒内的真实请求数"，1 秒 5 次就是 1 秒 5 次，多一次都不行 —— 这正是"防脚本刷单、防接口被爬"需要的语义。注解 `SlidingWindowLimit` 的注释里也写明了这一点。
边界：滑动窗口要在 ZSet 里为每个请求存一个成员，内存开销随请求量线性增长，所以 key 必须带窗口长度的 `PEXPIRE`（脚本里已经写了），且维度不能拆得过细（本项目只做 GLOBAL / IP / USER 三个维度）。

**4. 为什么用逻辑过期，不直接给 key 设 TTL？**
结论：为了"过期的那一刻不阻塞任何一个请求"。
理由：物理过期 + 互斥锁的方案，缓存一失效，抢到锁的线程去查库，其余线程要么自旋等待、要么直接穿库，接口的 P99 被数据库查询时间绑架。逻辑过期则让旧值继续服务，重建在后台线程池里发生，实测 100 并发全部成功返回、只触发 1 次异步重建。
边界：**读到的可能是旧数据**，且需要额外存一个 `expireAt` 字段（本项目把 value 包成 `{expireAt, data}`）。所以只适合"能接受短暂旧值"的数据 —— 活动标题、价格、时间可以，剩余库存不行（`ActivityCacheVO` 里刻意不含库存字段）。另外物理 TTL 必须给得比逻辑 TTL 长（本项目取 3 倍），否则旧值在重建完成前就物理消失了，逻辑过期退化成"直接未命中"。

**5. 为什么删缓存，而不是更新缓存？**
结论：删除是幂等的、顺序无关的，更新不是。
理由：两个并发写请求 A（新值）和 B（旧值），如果都去"更新缓存"，网络抖动可能让 B 的写入晚于 A，缓存里就留下了旧值且**没有任何机制会再纠正它**。改成"删除"，无论谁先谁后，最终都是一次删除，下一次读自然回源拿到数据库里的最新值。
边界：删缓存会把一次"写"的成本转移到下一次"读"上（Cache Aside 的经典代价），如果这个 key 是热点，就会出现一次回源 —— 这正是技术点 3 的 SETNX 互斥要解决的问题。

**6. 为什么还要延迟双删？**
结论：为了清掉"读写并发时被回填的旧值"。
理由：Cache Aside 有一个已知的失效窗口：读请求在"数据库更新之后、缓存删除之前"读到旧数据，然后在"缓存删除之后"才把旧值写回缓存 —— 这条旧值会一直留在缓存里直到 TTL 到期。延迟一小段时间再删一次，就能把这个窗口关掉。
边界：延迟双删**降低**这个窗口的概率，并不严格消除，所以还叠加了 TTL 兜底（物理 TTL = 逻辑 TTL × 3）。另外延迟时间受 RocketMQ 延迟档位限制：RocketMQ 只支持固定档位，最小是 1 秒，所以配置项 `flashmart.cache.double-delete-delay-millis: 500` 经过 `Math.max(1, 500/1000)` 之后实际是 **1 秒**，配置里的毫秒值当前只有向下取整到秒的意义。要真正的亚秒级延迟，得改造成时间轮或本地延迟队列。

**7. 为什么用延迟消息，不用定时任务扫表？**
结论：定时任务扫全表在订单量大时是灾难，延迟消息是"一条订单一个精确闹钟"。
理由：定时任务要解决"扫哪些"，就得给 `status` 和 `create_time` 建索引并分批扫（`idx_user_status` 那种索引帮不上忙），扫的粒度还受任务执行频率限制（1 分钟跑一次就有 1 分钟误差）；延迟消息在下单成功时就为这一单定好了到点时间，不扫无关数据，误差只受 broker 投递影响，而且天然带重试。
边界：RocketMQ 只支持**固定档位**延迟（1s/5s/10s/30s/1m/2m… 共 18 档，见 `MqProducer.DELAY_LEVEL_SECONDS`），不支持任意秒数，所以 `nearestDelayLevel` 是"向上取最近档位"。要任意时刻的精确延迟，需要 RocketMQ 5.x 的定时消息（`deliverTimeMs`）或时间轮组件，本项目没有用。

**8. 为什么剩余库存不进活动详情缓存？**
结论：因为库存是秒级变化的字段，缓存它必然出现"页面显示有货但抢不到"。
理由：`ActivityCacheVO` 里只有标题、价格、时间、总库存、限购数这些"改了就是商家主动改"的字段；剩余库存由 `ActivityService.currentStock` 实时读 Redis（`seckill:stock:{id}`，未预热时回退数据库），详情接口和列表接口都走这一条路径。
边界：列表页一次查 30 个活动就会有 30 次 Redis `GET`，可用 `MGET` 或 pipeline 优化；本项目活动列表最多 30 条，暂时没做。

**9. 为什么超时关单只回滚库存，不回滚"已购记录"？**
结论：名额是一次性的机会，不付款就作废；库存退回池子给其他人抢。
理由：如果关单时把 `seckill:bought:{id}` 里该用户的记录也清掉，用户可以"抢了不付 → 关单 → 再抢"，把库存反复占住，这等于给恶意用户一个零成本锁库存的手段。真实秒杀业务也是这个口径：`OrderService.timeoutOrder` 只调 `rollbackStock`（Redis `INCR`）+ `activityMapper.increaseStock`（数据库 +1），**不调** `clearUserRecord`。
边界：用户确实会"莫名其妙被限购"，需要在产品上给出文案（本项目返回"每人限购 1 件，你已经抢到啦"）。`SeckillStockService.clearUserRecord` 这个方法已经写好但**当前没有任何调用方** —— 它是留给"客服手动释放名额"这类后台操作的。
对照：下单**失败**（数据库库存不足、落库异常）走的是 `rollbackAll`，库存与已购记录**一起**回滚 —— 因为用户什么都没拿到，得把机会还给他。

**10. 为什么 Redis 扣减在前、数据库扣减在消费端？**
结论：因为两道扣减的职责不同 —— Redis 决定"谁能进来"，数据库决定"最终账对不对"。
理由：Redis 扣减要快（抢购接口 28ms 返回），所以放在主链路；数据库扣减要准，所以放在消费者里用事务执行，带 `WHERE available_stock > 0`。两者之间用 MQ 解耦，可以用"最终一致"换"瞬时吞吐"。
边界：这意味着**在消息积压时，Redis 剩余库存会领先于数据库剩余库存**（Redis 已经扣完，数据库还没扣）。查询接口的权威口径是 Redis（`ActivityService.currentStock` 优先读 Redis），数据库侧是兜底与对账依据。

**11. Redis 挂了怎么办？**
结论：限流降级放行，库存没有降级 —— 这一点必须说清楚。
理由：`SlidingWindowLimiter.tryAcquire` 捕获异常后 `return true`，因为限流是保护手段，不该因为限流组件自己挂了把业务熔断；但**抢购链路的库存扣减没有降级路径**：`redis.execute` 抛异常会直接冒泡成系统异常，因为此时无法判断库存，宁可让用户看到失败也不能让超卖发生。缓存读路径同理，`RedisCacheClient` 里只有"写缓存失败"和"反序列化失败"打了 warn 并回源数据库，读缓存本身没有 try-catch 包裹降级。
边界：Redis 单节点部署，没有任何高可用方案（没有哨兵、没有集群），属于待补项。

**12. 消息发送失败了怎么办？**
结论：当前是"记日志 + 依赖 RocketMQ 客户端自身重试"，没有本地消息表。
理由：`MqProducer.sendAsync` 的 `onException` 只打了 `log.error`，`sendAsync` 外层还有一层 try-catch 打日志；生产者配了 `retry-times-when-send-failed: 3` 与 `send-message-timeout: 3000`。Broker 侧接收成功后，消费失败有阶梯重试兜底。
边界：**"消息最终没发出去"这个场景当前会丢单** —— 用户已经看到"抢到了"（Redis 库存已扣），但订单永远不会落库，也没有补偿。正确做法是本地消息表（业务事务里落一条待发消息，再由后台任务投递）或 RocketMQ 事务消息，本项目**明确未做**，记录在待补清单里。异步发送的选择本身也意味着主链路不等 broker 回执。

**13. 为什么数据库要有 `uk_user_activity`，Redis 不是已经拦住了吗？**
结论：唯一索引是"最后一道防线"，它保证的是不可能，而不是"概率很低"。
理由：Redis 判断依赖 `seckill:bought:{id}` 这个 Hash 的内容。它会因为多种原因失真：预热脚本被并发覆盖、运维手动 `DEL`、Redis 数据丢失后重建、`reset` 接口被调用。一旦失真，应用层再严密的判断也只是概率。把约束交给数据库，`OrderService.createOrder` 捕获 `DuplicateKeyException` 后回滚数据库库存并让消费端回滚 Redis，就把"不可能下两单"变成了硬保证。
边界：唯一索引的代价是**逻辑删除必须绕开**（见第 7 节第 1 点）。

**14. 前端为什么用 400ms 轮询，不用 WebSocket 或 SSE？**
结论：抢购结果的等待窗口只有百毫秒级（实测约 157ms），轮询的实现成本最低。
理由：`ActivityDetail.vue` 每 400ms 调一次 `/api/seckill/result/{orderNo}`，最多轮询 15 秒，拿到非 `QUEUING` 状态就停止；服务端这个接口只是一条按 `order_no` 的主键查询，成本极低，也不需要维护长连接。
边界：如果下单耗时变长或并发用户变多，轮询会成为纯粹的无效流量，届时应该换成 SSE 或让服务端在订单落库后推送。

**15. 为什么不用 Redisson？**
结论：本项目的所有并发控制点都能用更轻的手段解决，引入 Redisson 只增加一个需要理解与运维的组件。
理由：库存扣减用 Lua（不需要锁）；缓存重建互斥用 `SETNX + TTL`（锁定时间极短，不需要续期）；一人一单用 Redis Hash + 数据库唯一索引；订单状态流转用条件更新。这四处都不需要可重入、不需要 WatchDog 续期。
边界：如果将来出现"跨越多次 Redis 调用、耗时不可预测"的临界区，才需要真正的分布式锁 —— 那是引入 Redisson 的时机。
