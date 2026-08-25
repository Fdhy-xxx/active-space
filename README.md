# 跃动空间 (Active Space) — 智能健身与交流平台

一款专为同城健身爱好者打造的综合服务平台（后端独立开发），涵盖场馆/私教检索、稀缺器械及体验课预约、动态分享与平台统计分析。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot · Spring MVC |
| 持久层 | MyBatis-Plus · MySQL |
| 缓存与高并发 | Redis · Redisson · Lua 脚本 |
| 压测 | JMeter |
| 构建 | Maven |

## 核心功能

| 模块 | 说明 |
| --- | --- |
| 用户体系 | 手机验证码登录、Token 会话管理、双拦截器自动刷新 |
| 场馆/私教检索 | 附近商户 GEO 搜索、商户分类、Redis 缓存与热点防护 |
| 器械/体验课预约 | 秒杀下单、库存原子预扣减、基于 Stream 消息队列的异步下单 |
| 动态分享 | 健身动态/探店笔记、点赞（ZSet）、关注与好友 Feed 流（滚动分页） |
| 平台工具 | Redis 分布式 ID、用户签到、缓存工具封装 |

## 项目亮点

1. **缓存击穿规避与预热设计**
   针对热门场馆预约接口，采用"定时任务 + 分布式锁"提前预热缓存；引入双重检查锁定（Double-Checked Locking）配合互斥锁，避免热点 Key 突发失效瞬间大量请求穿透至数据库造成连接池打满，保障核心查询高可用。

2. **分布式锁与高并发抢课**
   针对稀缺器械预约场景，引入 Redisson 替换传统 JVM 锁解决集群超卖；采用 Redis + Lua 脚本实现库存原子预扣减，JMeter 压测下单机支撑峰值 QPS 600+ 且 0 超卖。

3. **冷热数据隔离与穿透防御**
   对场馆/教练主页设置差异化缓存过期策略与空值缓存，规避缓存穿透/雪崩；核心查询接口响应时间由 800ms 降至 150ms 内，命中率稳居 90%+。

## 快速开始

1. **初始化数据库**：执行 `src/main/resources/db/active_space.sql`
2. **修改配置**：在 `src/main/resources/application.yaml` 中配置 MySQL、Redis 连接信息及上传目录（`SystemConstants.IMAGE_UPLOAD_DIR`）
3. **启动项目**：

```bash
mvn spring-boot:run
```

主类为 `com.activespace.ActiveSpaceApplication`。

## 协议

仅用于学习交流。
