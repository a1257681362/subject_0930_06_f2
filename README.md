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

## F2 无人机叶片巡检航线

在 F1 台账上新增无人机航线规划与放飞管控（schema 版本 `f2-1`）。航线按**风机 + 叶片方位 + 拍摄距离**配置航点，发布前校验**临时禁飞区、风速条件、电量余量**，发布/临时改线走**两级审批**，版本全程留痕。

### 数据表

| 表 | 说明 |
| --- | --- |
| `t_flight_route` | 航线：按风机配置，携带最大风速、返航电量余量、预估耗电阈值 |
| `t_flight_route_version` | 航线版本：航点 JSON 快照 + 阈值，不可变；DRAFT/PUBLISHED/ARCHIVED |
| `t_route_waypoint` | 航点：叶片方位（A/B/C）、拍摄距离（米）、经纬度、高度、云台角 |
| `t_route_version_diff` | 版本差异：新增/删除/修改航点与阈值的逐行 old→new |
| `t_no_fly_zone` | 临时禁飞区：圆心经纬度+半径，生效窗口左闭右开，可撤销留档 |
| `t_route_publish_check` | 发布前校验记录：是否通过、风速/电量、禁飞区冲突坐标与原因 |
| `t_route_approval` | 两级审批单：PENDING / PENDING_HALF / APPROVED / REJECTED |
| `t_flight_mission` | 飞行任务：PENDING/FLYING/FINISHED/CANCELLED，起飞冻结版本 |
| `t_inspection_order` | 增补 `route_version_id` / `route_mission_id` / `route_change_apply_id` |

### 业务规则

- **配置与版本分离**：航点是“待发布配置”，发布时冻结成不可变版本快照；已发布版本永不改写。
- **发布前三校验**（提交即落 `t_route_publish_check`，可追溯）：
  - 临时禁飞区：航点落入生效窗口内的圆形区域（含边界）即拦截，冲突明细给出**禁飞区圆心坐标+半径、航点坐标、距离和设立原因**；窗口与归属窗口同一口径 `[start,end)` 左闭右开，撤销/过期自动放行。
  - 风速：实测/预报风速 ≤ 航线允许最大风速。
  - 电量：起飞电量 − 预估耗电 ≥ 返航保留电量余量。
  - 校验不通过时 DRAFT 版本与校验记录都保留，错误响应体直接带回校验记录（含冲突坐标原因）。
- **两级审批、半程停留**：一级通过后状态为 `PENDING_HALF`，系统停留待处理，版本仍是 DRAFT、不能建飞行任务、不能再次提交；终审通过才激活版本。任一级驳回即终结为 REJECTED。
- **新版本只作用于未起飞任务**：终审通过后旧版本归档，仅把 PENDING 任务重绑新版本；FLYING/FINISHED 任务不动。起飞瞬间按绑定版本再做一次三校验，通过后版本冻结。
- **历史影像引用原航线**：巡检工单关联已起飞任务时继承其冻结的 `route_version_id`（临时改线还记 `route_change_apply_id`），之后航线再发新版本不改变历史工单；版本快照随时可还原。
- **临时改线必须审批并留差异**：`TEMPORARY_CHANGE` 版本同样过三校验和两级审批，逐行记录相对基线版本的航点/阈值差异。

### 接口一览

```
# 航线与配置
POST   /api/flight-routes                          航线建档（风机+航点：方位/拍摄距离）
PUT    /api/flight-routes/{id}/config              调整待发布配置（不影响已发布版本）
GET    /api/flight-routes/{id}                     航线详情（含航点、当前版本）
POST   /api/flight-routes/{id}/publish-submit      发布提交（带风速/电量，过三校验）
POST   /api/flight-routes/{id}/temporary-change    临时改线申请（校验+差异+两级审批）

# 两级审批
POST   /api/flight-routes/approvals/{id}/first-approve   一级审批（通过→PENDING_HALF 待处理）
POST   /api/flight-routes/approvals/{id}/final-approve   终审（通过→版本激活、重绑未起飞任务）
GET    /api/flight-routes/approvals/pending              待处理审批单

# 版本追溯
GET    /api/flight-routes/{id}/versions            版本列表
GET    /api/flight-routes/versions/{versionId}     版本快照航点 + 版本差异
GET    /api/flight-routes/{id}/approvals           航线审批记录

# 临时禁飞区
POST   /api/no-fly-zones                           建档（坐标+半径+生效窗口+原因）
POST   /api/no-fly-zones/{id}/revoke               撤销（档案保留）
GET    /api/no-fly-zones?time=                     某时刻生效中的禁飞区

# 飞行任务
POST   /api/flight-missions                        创建（绑定当前发布版本）
POST   /api/flight-missions/{id}/depart            起飞（再校验禁飞区/风速/电量，通过后冻结版本）
POST   /api/flight-missions/{id}/finish            完成
POST   /api/flight-missions/{id}/cancel            取消（仅未起飞）
GET    /api/flight-missions?routeId=               航线下任务
```

创建巡检工单（`POST /api/inspection-orders`）新增可选 `missionId`：关联已起飞任务后，工单继承冻结的航线版本与改线审批单号。

### 验证

- `mvn test` 在原有用例基础上新增 `FlightRouteIntegrationTest`：三校验拦截（禁飞区冲突含坐标原因、风速、电量）、两级审批半程停留与驳回、新版本只重绑未起飞任务、历史影像引用原版本且快照可还原、起飞前再校验、临时改线审批与逐行版本差异、禁飞区左闭右开窗口与撤销留档。
