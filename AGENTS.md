# AI Coding Remote Development Rules

## 1. 基本原则

本项目名称：`AI Coding Remote`

基础包名：`com.wangbin.ai`

项目基于：

* Java 21
* Spring Boot 3.5.x
* RuoYi-Vue-Pro / 芋道开发框架
* MyBatis-Plus
* MySQL
* Redis
* Redisson
* Vue3
* TypeScript
* Element Plus

所有新增代码必须优先遵循当前仓库已有成熟实现和 RuoYi-Vue-Pro 的项目开发规范。

禁止为了个人习惯重新发明一套 Controller、Service、Mapper、VO、DO、异常、权限、分页、租户或字典体系。

如果本 `AGENTS.md` 与仓库历史代码风格存在冲突：

* 对新增代码，以 `AGENTS.md` 为准；
* 对旧代码，不允许为了统一风格而进行无关的大规模重构；
* 修改旧代码时，只修改完成当前需求真正需要修改的部分。

## 2. Java 开发规范

所有 Java 代码必须遵循：

**《阿里巴巴 Java 开发手册（嵩山版）》**

同时遵循当前 RuoYi-Vue-Pro 项目已有开发规范。

要求：

* 命名清晰
* 职责单一
* 避免超大类和超大方法
* 避免重复代码
* 避免无意义封装
* 避免过度设计
* 尽量复用现有公共能力
* 不创建作用高度重叠的 DTO、VO、Util、Service
* 不写无实际意义的空接口和空实现

## 3. 编码规范

项目中的 Java、XML、YAML、Properties、SQL、JSON、Markdown、TypeScript、Vue、Shell、配置文件统一使用 `UTF-8`。

除非原文件明确有其他格式要求，否则使用 UTF-8，不主动加入 BOM。

禁止产生：

* GBK
* ANSI
* UTF-16

等编码文件。

中文注释、中文数据库 COMMENT 必须保证 UTF-8 正常显示。

## 4. 注释规范

重要的类、接口、核心方法、核心领域模型、状态机、协议转换、安全逻辑、并发逻辑、复杂业务逻辑必须添加必要注释。

公共接口和重要核心类优先使用 Javadoc。

注释应解释：

* 为什么这么做
* 业务约束
* 安全边界
* 非明显行为

不要大量编写：

```java
// 获取用户
getUser();
```

这类重复代码本身含义的无价值注释。

## 5. RuoYi 分层结构

所有普通业务模块必须遵循当前项目分层结构：

```text
Controller
    ↓
Service
    ↓
ServiceImpl
    ↓
Mapper
    ↓
Database
```

严格要求：

Controller：

* 只负责 HTTP 接口
* 参数接收
* 参数校验
* 权限校验
* 调用 Service
* 返回 CommonResult
* 不直接调用 Mapper
* 不直接写数据库逻辑
* 不编写复杂业务逻辑

Service：

* 定义业务接口
* 不直接承担 HTTP 职责

ServiceImpl：

* 实现业务逻辑
* 业务规则
* 事务边界
* 调用 Mapper
* 调用其他领域 Service

Mapper：

* 只负责数据库访问
* 优先继承项目已有 `BaseMapperX`
* 优先使用项目已有 LambdaQueryWrapperX 等能力
* 不在 Controller 中直接使用 Mapper

禁止出现：

```text
Controller -> Mapper
```

这种跨层调用。

## 6. RuoYi 包结构

新增普通业务领域优先遵循：

```text
controller/admin/<domain>
controller/admin/<domain>/vo

service/<domain>

dal/dataobject/<domain>
dal/mysql/<domain>

convert/<domain>

enums
```

例如：

```text
controller/admin/device
service/device
dal/dataobject/device
dal/mysql/device
convert/device
```

不要随意创造与当前项目完全不同的目录结构。

## 7. Service 注入规则

新增 Java 代码统一使用：

```java
@RequiredArgsConstructor
```

配合：

```java
private final XxxService xxxService;
```

进行构造器注入。

禁止新增：

```java
@Autowired
private XxxService xxxService;
```

以及：

```java
@Resource
private XxxService xxxService;
```

形式的字段注入。

历史代码已有字段注入时，不需要为了本规则进行无关的大范围修改。

## 8. 配置类规则

配置统一优先使用：

```java
@ConfigurationProperties
@Validated
```

例如：

```java
@ConfigurationProperties(prefix = "agent.codex")
```

禁止大量散落：

```java
@Value("${xxx}")
```

配置。

以下内容不得散落硬编码：

* timeout
* retry count
* executable path
* queue capacity
* aggregation window
* URL
* endpoint
* protocol version
* 默认路径
* 限制值

应该进入：

* ConfigurationProperties
* Constants
* Enum

中的合适位置。

## 9. 禁止硬编码

业务代码中尽量禁止硬编码：

```java
"running"
"codex"
"permission"
1000
5000
"/xxx/xxx"
```

等具有业务意义或协议意义的 magic value。

根据实际类型分别使用：

* 常量类
* 枚举
* ConfigurationProperties
* 字典
* ErrorCodeConstants

处理。

协议名称、Codex App Server method 名等固定协议字符串，应集中放入专门的协议常量类，禁止散落在各个 Adapter、Mapper、Service 中。

## 10. 错误码规则

所有对外业务错误禁止直接写：

```java
throw new RuntimeException("用户不存在");
```

禁止业务层硬编码错误码和错误信息。

必须遵循当前 RuoYi 项目已有 ErrorCode 机制。

每个业务模块统一维护类似：

```java
ErrorCodeConstants
```

例如：

```java
ErrorCode DEVICE_NOT_EXISTS =
        new ErrorCode(..., "设备不存在");
```

Service 中使用当前项目已有：

```java
ServiceExceptionUtil.exception(...)
```

等框架异常机制。

新增错误码前必须先搜索现有 ErrorCodeConstants，避免重复。

错误码必须保证模块范围内唯一。

## 11. DTO / VO 规则

稳定的数据结构禁止大量使用：

```java
Map<String, Object>
```

应建立明确：

* Request VO
* Response VO
* DTO
* Event Payload DTO

只有真正动态、无法稳定定义的扩展数据才允许使用 `extensions` Map。

Controller 禁止直接返回 DO。

Controller 对外使用 VO。

RuoYi 普通 CRUD 推荐命名：

```text
XxxCreateReqVO
XxxUpdateReqVO
XxxPageReqVO
XxxRespVO
```

根据当前仓库已有风格保持一致。

## 12. 参数校验

Controller 使用：

```java
@Validated
```

Request Body 根据需要使用：

```java
@Valid
```

VO 使用：

* `@NotNull`
* `@NotBlank`
* `@Size`
* `@Min`
* `@Max`
* `@InDict`
* 其他 Jakarta Validation

进行参数校验。

不要把所有参数合法性检查全部写在 ServiceImpl 中。

业务规则校验仍由 ServiceImpl 负责。

## 13. OpenAPI / Knife4j

所有新增业务接口必须提供完整 Knife4j / OpenAPI 信息。

Controller 类必须有：

```java
@Tag
```

接口方法必须有：

```java
@Operation
```

重要参数必须根据情况使用：

```java
@Parameter
```

Request / Response VO 字段必须使用：

```java
@Schema
```

说明字段业务含义。

不能只写接口代码而没有接口文档注解。

## 14. API 地址命名

所有新增 API URL 必须使用驼峰命名。

正确示例：

```text
/agentDevice/get
/agentSession/startSession
/agentPermission/approvePermission
```

禁止新接口使用短横线：

```text
/agent-device
/start-session
/approve-permission
```

历史 RuoYi 接口已经存在短横线时，不要为了这个规则主动大规模重构。

本规则约束新建或当前需求明确修改的业务接口。

## 15. 返回值规范

Controller 统一遵循当前 RuoYi：

```java
CommonResult<T>
```

分页：

```java
PageResult<T>
```

不要自定义另一套：

```text
Result
ApiResult
Response
ResponseData
```

除非该模块属于 Agent Protocol / Relay 内部协议而不是普通 HTTP 业务接口。

## 16. 权限规范

后台业务接口继续使用当前项目已有：

```java
@PreAuthorize("@ss.hasPermission('xxx')")
```

权限体系。

Agent 业务统一规划类似：

```text
agent:device:query
agent:device:create
agent:device:update
agent:device:delete

agent:session:query
agent:session:create
agent:session:cancel
```

不要自行实现另一套 RBAC。

## 17. 事务规范

涉及多表写操作、多步骤状态更新、需要原子一致性的业务，在 ServiceImpl 业务边界使用：

```java
@Transactional(rollbackFor = Exception.class)
```

不要在 Controller 开事务。

只读单表查询不滥用事务。

## 18. DO 规范

普通数据库实体按照当前项目代码生成器风格。

默认：

```java
XxxDO extends BaseDO
```

DO 不直接暴露给 Controller。

如果业务确实需要在 Java 代码中显式读取或写入 `tenantId`，则按照当前项目已有模式：

* 使用 TenantBaseDO
* 或增加对应 tenantId 字段

不得为了省事使用：

```java
@TenantIgnore
```

绕开租户机制。

## 19. 租户规则

所有 AI Coding Remote 业务数据默认属于租户数据。

只有真正的全局系统配置、系统字典基础数据、全局文件基础设施、其他明确属于平台全局基础设施的数据，才允许按照现有框架模式使用：

```java
@TenantIgnore
```

普通 Agent 业务：

* Device
* Project
* Runtime
* Session
* Message
* Command
* Permission
* Task
* Artifact
* Audit

禁止为了查询方便随意 TenantIgnore。

## 20. 数据库表命名

AI Coding Remote 新创建的所有业务表必须使用：

```text
ai_code_
```

前缀。

例如：

```text
ai_code_device
ai_code_project
ai_code_runtime
ai_code_session
ai_code_message
ai_code_command
ai_code_permission
ai_code_task
ai_code_artifact
ai_code_audit_log
```

但是 Java 代码中不需要出现：

```text
AiCodeDeviceDO
```

这种冗余命名。

在 `module-agent` 范围内直接根据业务领域命名即可，例如：

```text
DeviceDO
ProjectDO
SessionDO
```

或者根据上下文确有必要使用：

```text
AgentDeviceDO
AgentSessionDO
```

禁止简单把数据库前缀机械复制到所有 Java 类名。

## 21. 默认表公共字段

所有新增 `ai_code_` 业务表默认必须包含：

```sql
id bigint NOT NULL AUTO_INCREMENT COMMENT '编号',

creator varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '创建者',

create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

updater varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT '' COMMENT '更新者',

update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

deleted bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',

tenant_id bigint NOT NULL DEFAULT 0 COMMENT '租户编号'
```

并提供必要：

```sql
PRIMARY KEY (id)
```

除非用户针对某张表明确提出不同要求。

## 22. 逻辑删除

所有业务表：

```text
deleted = 0
```

代表有效。

```text
deleted = 1
```

代表已删除。

统一使用 MyBatis-Plus / 当前框架逻辑删除机制。

业务代码不要手工：

```sql
DELETE FROM ...
```

进行普通业务删除。

## 23. creator / updater

`creator`：创建数据时记录创建人用户编号。

创建后普通更新不得修改 creator。

`updater`：每次数据更新时记录当前操作用户编号。

优先复用当前 RuoYi BaseDO / MyBatis 自动填充机制。

禁止各 ServiceImpl 重复手写相同审计字段填充代码。

## 24. SQL 索引规则

除非用户明确提出需要索引，不要主动创建普通索引。

默认建表 SQL 只提供：

* 表结构
* 字段
* 必要 PRIMARY KEY

不要基于“以后可能查询快一些”自行增加：

```sql
KEY
INDEX
UNIQUE KEY
```

等二级索引。

如果代码实现确实认为某个索引属于正确性或性能的强制要求，先向用户说明原因，等待用户决定。

不得静默创建。

## 25. 数据库操作规则

只要需求涉及新建表、修改表、新增字段、修改字段、初始化数据、字典数据、菜单数据，都必须向用户提供对应 SQL。

但是禁止直接连接用户数据库执行 SQL。

禁止：

* 使用数据库客户端直接执行
* JDBC 手工执行 schema SQL
* MySQL CLI 执行
* 自动修改数据库结构

只允许：

```text
生成 SQL 文件 / SQL 文本
```

然后由用户自行确认和执行。

除非用户后续明确要求你实际执行数据库操作。

## 26. MySQL 表规范

默认使用：

```text
utf8mb4
utf8mb4_unicode_ci
```

字符串字段必须根据业务场景选择合理长度。

禁止所有字符串无脑：

```text
varchar(255)
```

长文本根据实际选择：

```text
text
mediumtext
```

金额使用：

```text
decimal
```

不得使用：

```text
float / double
```

保存精确金额。

所有字段必须写有意义的：

```sql
COMMENT
```

## 27. 字典和下拉框统一规则

所有业务下拉框选项统一使用系统字典管理。

业务表只保存：

```text
dict value
```

禁止同时保存：

```text
dict label
```

例如：

正确：

```text
project_type = "software"
```

禁止：

```text
project_type = "software"
project_type_label = "软件项目"
```

字典 label 必须由后台字典管理维护。

禁止使用 Java Enum 维护字典 label。

Java Enum 可以用于真正稳定的内部状态机，但不能取代后台业务字典。

## 28. 下拉框请求校验

后端 Request VO 对系统字典字段使用当前框架：

```java
@InDict
```

进行校验。

例如：

```java
@InDict(type = "contract_project_type")
```

具体写法以当前项目已有实现为准。

不得自己重复编写查询字典校验逻辑。

## 29. Excel 字典规则

Excel 导入导出涉及字典字段时，使用当前项目已有：

```java
@DictFormat
```

进行 value / label 转换。

不要在 Excel Service 中手工写：

```java
if ("1".equals(...)) {
    return "xxx";
}
```

## 30. 创建下拉字段规则

如果用户在需求中说明“某字段是下拉框”，则数据库字段默认按照：

```text
保存字典 value
```

设计。

不要额外新增：

```text
xxx_label
```

字段。

## 31. Knife4j 字典说明

所有来自系统字典的接口字段，Knife4j / OpenAPI 参数说明必须明确写：

* 字段业务含义
* 字典类型
* labelName
* 实际保存 value

例如：

```java
@Schema(
    description =
        "项目类型，字典类型 contract_project_type，labelName=项目类型，实际保存 value"
)
```

不能只写：

```java
@Schema(description = "项目类型")
```

## 32. 枚举使用规则

适合 Java Enum 的内容：

* 内部状态机
* 协议固定类型
* AgentType
* AgentEventType
* CommandStatus
* SessionStatus
* 不允许运营人员动态修改的系统固定值

不适合 Java Enum：

* 后台可维护业务下拉
* 项目类型
* 分类
* 标签
* 用户可配置选项

后者统一系统字典。

## 33. 禁止复杂内部类

尽量不要编写内部类。

尤其禁止把 DTO、Domain Model、Buffer、Context、State、Request、Response、复杂 Configuration Model 全部塞进一个 Java 文件成为内部类。

复杂模型应该独立文件。

只有非常简单、生命周期完全属于外部类且不会被复用的小型实现细节，才允许使用 private static 内部类。

如果一个内部类已经有独立业务含义，应立即拆成顶级类。

## 34. Mapper 规则

Mapper 优先：

```java
extends BaseMapperX<XxxDO>
```

查询优先复用：

```text
LambdaQueryWrapperX
LambdaUpdateWrapper
MyBatis-Plus
```

等现有框架能力。

禁止：

* Controller 写 SQL
* ServiceImpl 拼接 SQL 字符串
* 无必要 Native SQL
* 无必要 `.last(...)`
* 无必要 N+1 查询

复杂 SQL 确有必要时，放在 Mapper 或 XML 数据访问层。

## 35. Agent 系统总体架构规则

AI Coding Remote 固定划分：

```text
Control Plane
Realtime Data Plane
Local Execution Plane
Shared Contract
```

分别为：

```text
DABIN-server
DABIN-module-agent

DABIN-agent-relay

DABIN-agent-daemon

DABIN-agent-contract
```

职责必须保持清晰。

## 36. Control Plane

`DABIN-module-agent` 负责：

```text
谁可以做什么
```

包括未来：

* Device
* Credential
* Project
* Runtime
* Session
* Message
* Command
* Permission
* Task
* Artifact
* Audit

它是普通 RuoYi 业务模块。

必须遵循：

```text
Controller
Service
ServiceImpl
Mapper
```

分层规范。

## 37. Relay

`DABIN-agent-relay` 只负责：

```text
消息如何实时到达
```

包括：

* WebSocket
* Connection
* Route
* Presence
* Heartbeat
* Event Fanout
* Backpressure

禁止 Relay 逐渐演变为第二个业务后台。

禁止：

```text
DABIN-agent-relay
    ↓
DABIN-module-agent
```

Maven 直接依赖。

Relay 不直接访问业务 Mapper。

## 38. Daemon

`DABIN-agent-daemon` 运行在用户开发电脑。

负责：

```text
本地实际发生什么
```

包括：

* Coding Agent Process
* Runtime Discovery
* Workspace
* Session
* Native Agent Adapter
* Event Normalization
* Event Aggregation
* Local Policy
* Cloud Transport

Daemon 默认不提供公网入站 Server。

Daemon 主动通过安全出站连接访问 Cloud。

## 39. Contract

`DABIN-agent-contract` 是纯共享协议模块。

禁止依赖：

* Spring MVC
* Spring WebFlux
* MyBatis
* Redis
* RuoYi Service
* Codex SDK
* ACP SDK
* 数据库实现

Native Agent 数据结构禁止直接放入 Contract。

Contract 只表达 AI Coding Remote 自己的平台协议。

## 40. Anti-Corruption Layer

任何：

```text
Codex Native JSON
ACP Schema
Claude Schema
Gemini Schema
```

必须经过：

```text
Native Protocol
    ↓
Adapter
    ↓
Mapper / Normalizer
    ↓
AgentEvent / AgentCommand
```

才能进入平台。

禁止：

```text
Codex Native JSON
    ↓
Controller
```

禁止：

```text
ACP Schema
    ↓
数据库 DO
```

禁止：

```text
Codex Native JSON
    ↓
Vue
```

## 41. CodingAgentAdapter

不同 Agent 必须通过统一：

```java
CodingAgentAdapter
```

抽象。

业务层禁止大量出现：

```java
if (agentType == CODEX) {
    ...
}
```

Agent 差异必须尽量收敛在 adapter 层。

## 42. Capability 规则

`AgentCapabilities` 只能声明当前 Adapter 真实已经实现并验证过的能力。

禁止因为 Codex、Claude 理论上支持某功能，就提前声明：

```text
true
```

例如，如果：

```java
resolvePermission(...)
```

当前仍未真正实现，那么：

```text
permission = false
```

必须保持真实。

前端和 Cloud 会以 Capability 为可信依据。

## 43. Session ID 规则

平台：

```text
platformSessionId
```

与 Native Agent：

```text
threadId
nativeSessionId
```

必须分离。

Cloud 业务主要使用：

```text
platformSessionId
```

Agent Native ID 只能存在：

* Adapter
* Daemon Session Context
* 必要的映射数据

不得让整个业务层绑定 Codex threadId。

## 44. Event Sequence

`AgentEvent.seq` 必须每个 Session 独立、单调递增。

正确：

```text
Session A:
1
2
3
4

Session B:
1
2
3
```

禁止使用所有 Session 共用一个全局 AtomicLong。

任何聚合后新生成的 Event 都必须获得新的 seq。

禁止多个不同 Event 使用相同 seq。

## 45. Critical Event 不得猜测路由

以下数据属于高可靠数据：

* Permission Request
* Remote Command
* Command ACK
* Task Completed
* Session Completed
* Security Decision

如果无法明确解析这个事件属于哪个 Session / Device / User，必须：

```text
拒绝
记录 protocol error
报警
```

禁止：

```java
sessions.values().stream().findFirst()
```

随便拿第一个 Session 作为 fallback。

安全相关路由永远不能猜。

## 46. WebSocket Backpressure

Relay 的 outbound queue 必须 per connection。

不能所有 WebSocket 共用一个全局 Queue。

每个 connection 应拥有独立：

```text
ConnectionContext
OutboundQueue
```

所有队列必须 bounded。

禁止无界队列。

压力大时：

```text
TRANSIENT
→ merge / drop

NORMAL
→ aggregate

IMPORTANT
→ preserve

CRITICAL
→ must preserve / explicit failure
```

关键数据不得静默丢失。

## 47. Reactor / Netty 规则

Reactor / Netty EventLoop 禁止执行长时间阻塞操作。

以下阻塞操作不得直接跑在 EventLoop：

* Process.waitFor
* Blocking file IO
* JDBC
* 长时间计算
* Thread.sleep

真正阻塞的子进程 IO 使用独立命名 Executor。

Java 21 优先评估 named virtual-thread executor 处理 Process stdout / stderr / wait 等阻塞任务。

## 48. CompletableFuture 规则

禁止使用默认：

```java
CompletableFuture.runAsync(...)
CompletableFuture.supplyAsync(...)
```

而不显式指定 Executor。

禁止使用公共 ForkJoinPool.commonPool。

业务异步任务必须使用：

* Spring 管理的命名线程池
* 项目定义的命名 Executor
* 合理使用 Java 21 Virtual Threads

## 49. 线程池规则

禁止随意：

```java
Executors.newFixedThreadPool(...)
```

散落在业务类中。

禁止无界：

```text
Task Queue
Message Queue
Outbound Queue
```

线程池和队列容量必须：

* 集中配置
* 有合理默认值
* 可通过 ConfigurationProperties 调整

## 50. Codex App Server

Codex 接入统一通过：

```text
codex app-server
```

结构化协议。

禁止：

* 正则解析 Codex TUI
* ANSI 去色后猜状态
* 根据人类 CLI 文本判断生命周期

当前阶段优先使用稳定：

```text
stdio / JSONL
```

协议。

Codex 原生 method name 必须集中定义协议常量。

## 51. JSON-RPC

JSON-RPC Client 必须正确区分：

* Request
* Response
* Notification
* Server Request

需要：

```text
requestId -> pending request
```

关联。

Server Request 尤其是 Permission，不得使用可能静默丢事件的机制。

收到无法识别的 Server Request，不能直接当作 Permission。

必须按 method 白名单正确分类。

未知请求应：

* protocol warning
* explicit error
* 或安全拒绝

不能猜测。

## 52. Jackson

项目当前使用 Jackson 作为主要 JSON 序列化体系。

不要为了普通业务再引入：

* Fastjson
* Fastjson2
* Gson

形成多套 JSON 栈。

Java 8+ Date/Time 使用官方：

```text
jackson-datatype-jsr310
JavaTimeModule
```

在需要确定协议序列化行为的地方，优先显式注册：

```java
new JavaTimeModule()
```

而不是依赖不可控的 classpath module 自动发现。

## 53. Local Security

Cloud 不是用户电脑最终安全边界。

最终权限必须同时满足：

```text
Cloud Authorization
+
Local Policy
```

Local Policy 拒绝，Cloud 不得绕过。

Workspace 必须进行：

* absolute path
* normalize
* real path / canonical path
* allowed root

校验。

需要防御：

* `../`
* symlink
* junction
* path traversal

禁止仅通过字符串 startsWith 作为最终安全边界。

## 54. 日志安全

禁止日志输出：

* Access Token
* Refresh Token
* API Key
* Device Secret
* Device Credential
* Cookie
* 完整 Authorization Header
* 敏感环境变量

Prompt 内容和源码内容默认不得在 INFO 日志无限打印。

需要调试时使用：

* DEBUG
* 可配置敏感日志开关
* 脱敏

## 55. 前端开发规则

前端基于当前：

```text
DABIN-ui/DABIN-ui-admin-vue3
```

继续开发。

优先复用：

* Vue3
* Element Plus
* Pinia
* Router
* Axios
* RuoYi 权限体系
* 当前公共 Components / Hooks / Utils

不要恢复用户已经删除的：

* CRM
* ERP
* BPM
* IoT
* Mall
* IM
* 原 AI 平台

等无关业务。

Coding Agent 新业务统一使用：

```text
agent
```

命名。

例如：

```text
src/views/agent
src/api/agent
```

不要重新使用原：

```text
src/views/ai
```

语义。

## 56. 禁止运行前端

除非用户在当前任务明确要求，禁止 Codex 主动运行任何前端命令。

包括但不限于：

```text
pnpm install
pnpm dev
pnpm build
pnpm build:prod
pnpm ts:check
pnpm lint
npm install
npm run
yarn
npx
vite
vue-tsc
eslint
```

前端任务只负责修改代码。

完成后告诉用户：

```text
建议用户手动执行哪些命令验证
```

但 Codex 自己不要执行。

## 57. 后端验证

后端 Java 修改允许且应该根据影响范围执行最小必要范围的 Maven：

```text
test
compile
package
```

验证。

不要每次小改动都无脑执行整个超大仓库所有测试。

优先：

```text
受影响 module
+
依赖 module
```

进行验证。

如果测试需要用户数据库、外部生产服务、有风险的远程资源，则不得擅自连接。

## 58. SQL 执行限制

即使为了测试，也禁止 Codex 自行连接数据库执行本任务生成的：

```text
CREATE
ALTER
DROP
INSERT
UPDATE
DELETE
```

SQL。

数据库变更始终：

```text
生成 SQL
↓
交给用户
↓
用户执行
```

除非用户在当前任务明确授权。

## 59. Git 安全规则

开始任务前先检查：

```text
git status
```

禁止：

* git reset --hard
* 强制 checkout 用户修改
* force push
* 修改 Git remote
* 删除用户未提交修改
* 创建新的 Git 仓库

没有用户明确要求，不要主动 commit。

没有用户明确要求，不要主动 push。

## 60. 修改范围

不要看到历史代码存在问题就顺便全部重构。

严格遵循：

```text
完成当前需求
+
修复与当前需求直接相关问题
```

原则。

如果发现额外技术债，在最终结果中列出。

不要擅自扩大修改范围。

## 61. 完成任务后的汇报

每次较大的代码任务完成后需要报告：

* 修改了什么
* 为什么修改
* 新增文件
* 修改文件
* 删除文件
* 核心设计
* 后端测试结果
* 未验证内容
* 风险
* 后续建议

前端如果未运行，明确写：

```text
按照 AGENTS.md 规则，本次未运行前端命令，需要用户手动验证。
```

不要虚构验证结果。

## 62. 优先级

代码设计发生冲突时，按以下优先级执行：

```text
1. 用户当前明确要求
2. AGENTS.md
3. 当前仓库成熟实现和架构边界
4. RuoYi-Vue-Pro 开发规范
5. 阿里巴巴 Java 开发手册（嵩山版）
6. 一般 Java / Spring 最佳实践
```

任何情况下：

安全、数据隔离、权限、租户隔离不得为了“方便”而绕开。
