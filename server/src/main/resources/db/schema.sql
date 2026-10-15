-- FlashMart 数据库初始化脚本
CREATE DATABASE IF NOT EXISTS flashmart DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE flashmart;

-- 用户表（买家 BUYER / 商家 MERCHANT）
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码',
    nickname    VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '昵称',
    role        VARCHAR(20)  NOT NULL DEFAULT 'BUYER' COMMENT 'BUYER/MERCHANT',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB COMMENT '用户表';

-- 商品表（金额统一用「分」存整数，避免浮点误差）
CREATE TABLE IF NOT EXISTS product (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id  BIGINT       NOT NULL COMMENT '所属商家',
    title        VARCHAR(100) NOT NULL COMMENT '商品名',
    description  VARCHAR(500) NOT NULL DEFAULT '' COMMENT '商品描述',
    origin_price INT          NOT NULL DEFAULT 0 COMMENT '原价（分）',
    cover_color  VARCHAR(20)  NOT NULL DEFAULT '#4f7cff' COMMENT '演示用：前端据此渲染商品色块',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    deleted      TINYINT      NOT NULL DEFAULT 0,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_merchant (merchant_id)
) ENGINE = InnoDB COMMENT '商品表';

-- 抢购活动表
CREATE TABLE IF NOT EXISTS flash_activity (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    product_id      BIGINT       NOT NULL,
    merchant_id     BIGINT       NOT NULL,
    title           VARCHAR(100) NOT NULL COMMENT '活动标题',
    flash_price     INT          NOT NULL COMMENT '抢购价（分）',
    total_stock     INT          NOT NULL COMMENT '活动总库存',
    available_stock INT          NOT NULL COMMENT '数据库侧剩余库存（Redis 扣减成功后由消费者异步回写）',
    limit_per_user  INT          NOT NULL DEFAULT 1 COMMENT '每人限购件数，默认 1（一人一单）',
    start_time      DATETIME     NOT NULL,
    end_time        DATETIME     NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1有效 0已下线',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    deleted         TINYINT      NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_product (product_id),
    KEY idx_merchant (merchant_id),
    KEY idx_time (start_time, end_time)
) ENGINE = InnoDB COMMENT '限时抢购活动表';

-- 抢购订单表
CREATE TABLE IF NOT EXISTS seckill_order (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(40) NOT NULL COMMENT '订单号（对外暴露的业务主键）',
    user_id       BIGINT      NOT NULL,
    activity_id   BIGINT      NOT NULL,
    product_id    BIGINT      NOT NULL,
    flash_price   INT         NOT NULL COMMENT '成交价（分）',
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAY' COMMENT 'PENDING_PAY/PAID/CANCELLED/TIMEOUT',
    pay_trade_no  VARCHAR(64) NULL COMMENT '支付网关流水号',
    pay_time      DATETIME    NULL,
    cancel_time   DATETIME    NULL,
    cancel_reason VARCHAR(50) NULL COMMENT 'TIMEOUT/CANCELLED',
    version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁：支付回调与超时关单并发时只有一方生效',
    deleted       TINYINT     NOT NULL DEFAULT 0,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- 数据库层兜底「一人一单」：即使 Redis 判断失效，也不可能落下两单
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    KEY idx_user_status (user_id, status),
    KEY idx_activity (activity_id)
) ENGINE = InnoDB COMMENT '抢购订单表';

-- MQ 消费幂等表：msg_id 唯一索引，插入成功 = 首次消费
CREATE TABLE IF NOT EXISTS mq_consume_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    msg_id      VARCHAR(64)  NOT NULL COMMENT 'RocketMQ 消息唯一 ID',
    topic       VARCHAR(100) NOT NULL,
    biz_key     VARCHAR(100) NOT NULL DEFAULT '' COMMENT '业务幂等键',
    status      VARCHAR(20)  NOT NULL DEFAULT 'CONSUMING' COMMENT 'CONSUMING/SUCCESS/FAILED',
    retry_count INT          NOT NULL DEFAULT 0,
    error_msg   VARCHAR(500) NOT NULL DEFAULT '',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT 'MQ 消费幂等表';

-- 缓存补偿表：删缓存失败时落一条待补偿记录，由 MQ 消费者重试删除（TTL 兜底）
CREATE TABLE IF NOT EXISTS cache_sync_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    cache_key    VARCHAR(200) NOT NULL COMMENT '需要删除的缓存 key',
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    retry_count  INT          NOT NULL DEFAULT 0,
    error_msg    VARCHAR(500) NOT NULL DEFAULT '',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT '缓存删除补偿任务表';
