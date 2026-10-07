# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

根目录的 `..\..\docs\schema\evops.sql` 是交付副本，应与工作区 `src/main/resources/schema.sql` 保持一致。

## F1 风电叶片巡检台账

在骨架上新增叶片运维台账（schema 版本 `f1-1`），已跑通：建档 → 巡检 → 缺陷登记 → 维修 → 验收。电量结算后续再做。

### 数据表

| 表 | 说明 |
| --- | --- |
| `t_turbine` | 风机档案：场址、机型、并网时间、退役时间 |
| `t_blade` | 叶片档案：序列号、方位（A/B/C）、材料、装上/拆下时刻 |
| `t_blade_replacement` | 换件记录：旧件 → 新件前后关系 |
| `t_inspection_order` | 巡检工单：无人机影像、巡检时刻、状态机 |
| `t_defect` | 缺陷登记：缺陷等级、维修结果、验收结论 |

### 业务规则

- **编号互斥**：风机编号与叶片编号各自唯一，且两类编号之间也不得重复（`AssetNoGuard` 跨表校验 + 数据库唯一约束）；叶片序列号唯一。
- **归属口径（同一口径）**：风机服役窗口 `[grid_connected_at, decommissioned_at)` 与叶片归属窗口 `[installed_at, removed_at)` 统一为**左闭右开**。叶片未指定装上时刻时默认取所属风机并网时间。任意时刻同一风机同一方位至多一片在役叶片。
- **换件**：旧叶片下架（`removed_at` = 换件时刻）与新叶片上架（`installed_at` = 换件时刻）同事务完成，`t_blade_replacement` 保留旧件与新件的前后关系。
- **工单状态机**：`PENDING → INSPECTED → DEFECT_REGISTERED → REPAIRED → ACCEPTED`；无缺陷时 `INSPECTED → CLOSED`。缺陷状态机：`OPEN → REPAIRED → ACCEPTED`，验收驳回退回 `OPEN` 重新维修；工单下全部缺陷验收通过后工单才闭环。

### 接口一览

```
POST   /api/turbines                          建档（可同时录入首批叶片）
PUT    /api/turbines/{id}                     档案调整（如把并网时间调到临界时间）
GET    /api/turbines/{id}/blades              当前在役叶片
GET    /api/turbines/{id}/blades/at?time=     某时刻归属叶片（口径验证）
GET    /api/turbines/{id}/blades/history      叶片全量档案 + 换件前后关系
POST   /api/turbines/{id}/blades              补录叶片
POST   /api/turbines/{id}/replacements        换件
POST   /api/inspection-orders                 创建巡检工单
POST   /api/inspection-orders/{id}/inspect    提交无人机影像与巡检时刻
POST   /api/inspection-orders/{id}/defects    缺陷登记
POST   /api/inspection-orders/{id}/close      无缺陷闭环
POST   /api/defects/{id}/repair               维修结果回填
POST   /api/defects/{id}/accept               验收（pass=true 闭环 / false 驳回返工）
```

联调期上述业务接口在 Shiro 中放行（`anon`），登录体系接入后收紧。

### 验证

- `mvn test`：`BladeLedgerFlowTest`（全流程、编号互斥、换件前后关系、状态机）+ `BoundaryAttributionTest`（把风机档案调到临界时间后，叶片档案按同一左闭右开口径归属）。
- 测试使用 H2 内存库（`src/test/resources/application.yml`），不污染 `data/` 本地文件库。
