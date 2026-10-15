-- FlashMart 演示数据（可选）：schema.sql 之后执行
-- 账号密码统一 123456；id 从 9001 起，避开库里可能已有的数据
USE flashmart;

INSERT IGNORE INTO sys_user (id, phone, password, nickname, role, status) VALUES
  (9001, '13811110001', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '星野数码（商家）', 'MERCHANT', 1),
  (9002, '13811110002', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '云栖家电（商家）', 'MERCHANT', 1),
  (9003, '13911110001', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '小张（买家）', 'BUYER', 1),
  (9004, '13911110002', '$2a$10$ImrToUAzwWh7c0cRBJJNYu49DzGnqWMLntxncarxoct63s6A6HTmm', '小李（买家）', 'BUYER', 1);

INSERT IGNORE INTO product (id, merchant_id, title, description, origin_price, cover_color, status) VALUES
  (9001, 9001, '星野 X1 无线降噪耳机', '主动降噪 / 40 小时续航 / 蓝牙 5.3', 89900, '#4f7cff', 1),
  (9002, 9001, '星野机械键盘 K87', '87 键 / 热插拔轴体 / 三模连接', 49900, '#7c5cff', 1),
  (9003, 9001, '星野 65W 氮化镓充电器', '双 C 口 / 折叠插脚 / 兼容笔记本', 15900, '#2fa8a0', 1),
  (9004, 9002, '云栖扫地机器人 S5', '激光导航 / 自动集尘 / 扫拖一体', 199900, '#e08c2f', 1),
  (9005, 9002, '云栖空气循环扇', '直流变频 / 12 档风力 / 静音', 39900, '#d95f7a', 1),
  (9006, 9002, '云栖智能电饭煲 4L', 'IH 加热 / 24 小时预约 / 柴火饭', 59900, '#5a8f3c', 1);

-- 活动 9001：正在进行中（用来演示抢购、缓存、限流）
INSERT IGNORE INTO flash_activity
  (id, product_id, merchant_id, title, flash_price, total_stock, available_stock, limit_per_user, start_time, end_time, status)
VALUES
  (9001, 9001, 9001, '星野 X1 耳机 限时抢购', 39900, 100, 100, 1,
   DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 1),
  (9002, 9002, 9002, '云栖扫地机器人 限时抢购', 99900, 50, 50, 1,
   DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 1);

-- 活动 9003：还有 2 分钟开始（用来演示「未开始不能抢」）
INSERT IGNORE INTO flash_activity
  (id, product_id, merchant_id, title, flash_price, total_stock, available_stock, limit_per_user, start_time, end_time, status)
VALUES
  (9003, 9002, 9001, '星野机械键盘 限时抢购', 19900, 30, 30, 1,
   DATE_ADD(NOW(), INTERVAL 2 MINUTE), DATE_ADD(NOW(), INTERVAL 7 DAY), 1);

-- 活动 9004：已经结束（用来演示活动状态）
INSERT IGNORE INTO flash_activity
  (id, product_id, merchant_id, title, flash_price, total_stock, available_stock, limit_per_user, start_time, end_time, status)
VALUES
  (9004, 9003, 9001, '星野 65W 充电器 限时抢购', 9900, 20, 0, 1,
   DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 1);
