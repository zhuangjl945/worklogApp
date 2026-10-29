# 医信运维系统 — 新增模块实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有工作日志系统基础上，新增「数据库表结构分析」和「凭据保管库」两个运维模块，并将项目整体更名为「医信运维系统」。

**Architecture:** 后端沿用 Spring Boot 3 + MyBatis + MySQL 分层架构（Controller → Mapper），新增两个独立 package（`dbdoc` 和 `credential`）。SQL 解析在后端用 JSqlParser 完成，前端用 ECharts + Element Plus 渲染表结构图和 ER 图。凭据模块使用 AES-256-GCM 对称加密，密钥由环境变量注入，数据库中只存密文。

**Tech Stack:** Spring Boot 3.5.10, Java 17, MyBatis, MySQL 8, JSqlParser 4.9, Vue 3.5, Element Plus 2.13, ECharts 6, Vite 7

## Global Constraints

- Java 17；Spring Boot 3.5.10；MyBatis 3.0.3
- 前端 Vue 3.5.24 + Element Plus 2.13.2 + ECharts 6.0.0
- 所有中文注释；提交信息 `feat/fix/docs/refactor/test/chore`
- 敏感凭据只走环境变量，不落代码或 `application.yml` 明文
- AES-256-GCM 密钥通过环境变量 `CREDENTIAL_AES_KEY` 注入，Base64 编码的 32 字节
- JSqlParser 仅做 SQL 语法解析，不执行任何数据库操作
- 所有新模块 Controller 统一使用 `ApiResponse` / `PageResponse` 响应格式

---

### Task 1: 项目更名为「医信运维系统」

**Files:**
- Modify: `pom.xml:6` — 修改 <name> 和 <description>
- Modify: `web/index.html:7` — 修改 <title>
- Modify: `web/src/views/LoginView.vue` — 修改登录页标题
- Modify: `web/src/views/HomeView.vue` — 修改侧边栏 Logo/系统名
- Modify: `README.md` — 修改项目名和描述
- Modify: `deploy/nginx-worklog.conf` — 更新注释

**Interfaces:**
- 无新增接口，纯文案替换

- [ ] **Step 1: 修改 pom.xml 项目名称**

将 pom.xml 的 <name> 和 <description> 改为：
- name: 医信运维系统
- description: 医院信息运维管理平台：工作日志、数据库文档、凭据保管库

- [ ] **Step 2: 修改前端页面标题**

web/index.html 的 <title> 改为「医信运维系统」

- [ ] **Step 3: 修改登录页与侧边栏标题**

LoginView.vue 中找到标题文本替换为「医信运维系统」。HomeView.vue 中侧边栏系统名称同步替换。

- [ ] **Step 4: 修改 README.md 第一行标题**

- [ ] **Step 5: 修改 Nginx 配置注释**

- [ ] **Step 6: 验证前端启动无报错**

Run: `cd web && npm run dev`

- [ ] **Step 7: Commit**

`git commit -m "chore: 项目更名为医信运维系统"`

---


### Task 2: 数据库文档模块 — 后端 SQL 解析引擎

**Files:**
- Create: `src/main/java/com/zjl/worklog/dbdoc/SqlParseService.java`
- Create: `src/main/java/com/zjl/worklog/dbdoc/dto/TableSchema.java`
- Create: `src/main/java/com/zjl/worklog/dbdoc/dto/ColumnInfo.java`
- Create: `src/main/java/com/zjl/worklog/dbdoc/dto/IndexInfo.java`
- Create: `src/main/java/com/zjl/worklog/dbdoc/dto/ParseResult.java`
- Modify: `pom.xml` — 添加 JSqlParser 依赖

**Interfaces:**
- Consumes: 无
- Produces: `SqlParseService.parseDdl(String sql)` -> `ParseResult`
  - `ParseResult` 包含 `List<TableSchema> tables`
  - `TableSchema`: tableName, tableComment, List<ColumnInfo> columns, List<IndexInfo> indexes
  - `ColumnInfo`: name, type, length, nullable, defaultValue, comment, primaryKey
  - `IndexInfo`: name, type, List<String> columns

- [ ] **Step 1: 添加 JSqlParser 依赖到 pom.xml**

```xml
<dependency>
    <groupId>com.github.jsqlparser</groupId>
    <artifactId>jsqlparser</artifactId>
    <version>4.9</version>
</dependency>
```

- [ ] **Step 2: 创建 DTO 类（ColumnInfo, IndexInfo, TableSchema, ParseResult）**

均为 Lombok @Data @Builder 的 POJO，字段见上方 Interfaces 描述。包路径 `com.zjl.worklog.dbdoc.dto`。

- [ ] **Step 3: 编写 SqlParseService 单元测试**

`src/test/java/com/zjl/worklog/dbdoc/SqlParseServiceTest.java`

测试用例：
1. 解析单条 CREATE TABLE（含主键、唯一索引、COMMENT）
2. 解析多条 CREATE TABLE
3. ALTER TABLE ADD COLUMN 合并到已有表
4. 无效 SQL 抛出异常

- [ ] **Step 4: 运行测试确认失败**

Run: `mvn test -Dtest=SqlParseServiceTest`
Expected: FAIL — 类不存在

- [ ] **Step 5: 实现 SqlParseService**

核心逻辑：
1. 用 `CCJSqlParserUtil.parseStatements(sql)` 解析多条语句
2. 遍历 Statement：`CreateTable` 提取列定义、索引、COMMENT；`Alter` 处理 ADD COLUMN/INDEX
3. 用 LinkedHashMap 维护表名->结构的有序映射，ALTER 合并到同名表
4. 辅助方法：cleanName 去反引号、extractType/Length/Comment 解析列属性

- [ ] **Step 6: 运行测试确认通过**

- [ ] **Step 7: Commit**

```bash
git commit -m "feat: 数据库文档模块 - SQL DDL 解析引擎"
```

---

### Task 3: 数据库文档模块 — 后端 REST API + 历史记录存储

**Files:**
- Create: `src/main/java/com/zjl/worklog/dbdoc/DbDocController.java`
- Create: `src/main/java/com/zjl/worklog/dbdoc/entity/DbDocEntity.java`
- Create: `src/main/java/com/zjl/worklog/dbdoc/mapper/DbDocMapper.java`
- Create: `src/main/resources/mapper/DbDocMapper.xml`
- Create: `src/main/resources/db/db_doc_module.sql`

**Interfaces:**
- Consumes: `SqlParseService.parseDdl()`
- Produces:
  - `POST /api/db-doc/parse` — 解析 SQL 返回 ParseResult（不保存）
  - `POST /api/db-doc/save` — 解析并保存文档记录
  - `GET /api/db-doc` — 分页查询已保存文档
  - `GET /api/db-doc/{id}` — 单条详情
  - `DELETE /api/db-doc/{id}` — 删除

- [ ] **Step 1: 创建建表脚本 db_doc_module.sql**

```sql
CREATE TABLE IF NOT EXISTS db_doc (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  title varchar(200) NOT NULL COMMENT '文档标题',
  source_sql longtext NOT NULL COMMENT '原始 SQL 文本',
  parsed_json longtext NOT NULL COMMENT '解析后的 JSON 结构',
  table_count int NOT NULL DEFAULT 0 COMMENT '包含表数量',
  created_by bigint NOT NULL COMMENT '创建人 ID',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库文档记录';
```

- [ ] **Step 2: 创建实体类 DbDocEntity**

Lombok @Data，字段对应上表。

- [ ] **Step 3: 创建 Mapper 接口与 XML**

DbDocMapper 提供：insert、selectById、selectPage（关键字+分页）、countByKeyword、deleteById。
MyBatis XML 实现动态 SQL 的关键字模糊搜索（title LIKE %keyword%）。

- [ ] **Step 4: 创建 DbDocController**

POST /api/db-doc/parse — 仅解析不保存（前端实时预览用）
POST /api/db-doc/save — 解析 + 序列化 JSON + 入库
GET /api/db-doc — PageResponse 分页
GET /api/db-doc/{id} — 详情
DELETE /api/db-doc/{id} — 删除

使用 UserContext.current().getId() 获取创建人。

- [ ] **Step 5: 验证编译通过**

Run: `mvn compile`

- [ ] **Step 6: Commit**

```bash
git commit -m "feat: 数据库文档模块 - REST API 与历史记录存储"
```

---

### Task 4: 数据库文档模块 — 前端 SQL 编辑器与解析预览页

**Files:**
- Create: `web/src/api/dbDoc.js`
- Create: `web/src/views/DbDocParseView.vue`
- Modify: `web/src/router/index.js` — 添加路由
- Modify: `web/src/views/HomeView.vue` — 侧边栏添加菜单项

**Interfaces:**
- Consumes: POST /api/db-doc/parse, POST /api/db-doc/save
- Produces: 「SQL 分析」页面，含 SQL 编辑器和解析结果表格

- [ ] **Step 1: 创建 API 封装 dbDoc.js**

导出：dbDocParse(sql)、dbDocSave(sql, title)、dbDocList(params)、dbDocDetail(id)、dbDocDelete(id)

- [ ] **Step 2: 创建 SQL 分析页面 DbDocParseView.vue**

页面布局：
- el-input textarea（等宽字体）输入 SQL DDL
- 可选标题输入框
- 「解析预览」「保存文档」「清空」按钮
- 解析结果区域：每张表一个 el-card，内含字段表格（字段名/类型/长度/可空/主键/默认值/注释）+ 索引表格

- [ ] **Step 3: 添加路由和侧边栏菜单**

路由：`/db-doc/parse` 和 `/db-doc/list`
侧边栏：在合同管理前添加「数据库文档」分组菜单

- [ ] **Step 4: 验证前端无报错**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: 数据库文档模块 - 前端 SQL 编辑器与解析预览"
```

---

### Task 5: 数据库文档模块 — 前端文档列表页与 ER 图可视化

**Files:**
- Create: `web/src/views/DbDocListView.vue`
- Create: `web/src/views/DbDocDetailView.vue`

**Interfaces:**
- Consumes: GET /api/db-doc, GET /api/db-doc/{id}, DELETE /api/db-doc/{id}
- Produces: 文档列表页、文档详情页（含 ECharts ER 关系图）

- [ ] **Step 1: 创建文档列表页**

el-table 展示已保存文档列表（ID、标题、表数量、创建时间、操作列）
el-pagination 分页
搜索框按标题关键字过滤
操作列：查看详情（弹窗）、删除

- [ ] **Step 2: 创建文档详情页（含 ER 图）**

el-descriptions 展示基本信息
ECharts Graph 图渲染 ER 关系：
- 每张表为一个节点，节点 label 列出字段（主键标 🔑）
- 基于列名 _id 后缀推断表间关系（如 user_id -> user 表）生成 links
- 力导向布局，可拖拽、缩放
各表结构 el-card 展示字段表格

- [ ] **Step 3: 添加详情路由**

`/db-doc/detail/:id` — meta.label = '文档详情'

- [ ] **Step 4: 验证完整流程**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: 数据库文档模块 - 文档列表页与 ER 图可视化"
```

---

### Task 6: 凭据保管库模块 — AES-256-GCM 加解密服务

**Files:**
- Create: `src/main/java/com/zjl/worklog/credential/CredentialCryptoService.java`
- Create: `src/main/java/com/zjl/worklog/credential/CredentialCryptoProps.java`
- Modify: `application.yml` — 添加 credential.aes-key 配置

**Interfaces:**
- Consumes: 环境变量 CREDENTIAL_AES_KEY（Base64 编码的 32 字节）
- Produces:
  - `CredentialCryptoService.encrypt(String plainText)` -> String（Base64: IV + 密文+Tag）
  - `CredentialCryptoService.decrypt(String cipherText)` -> String（明文）

- [ ] **Step 1: 创建配置属性类 CredentialCryptoProps**

@Component + @ConfigurationProperties(prefix = "credential")
@PostConstruct 校验：aesKey 非空、Base64 解码后恰好 32 字节，否则 throw IllegalStateException 阻止启动

- [ ] **Step 2: 编写单元测试**

测试：加密解密往返、同明文不同密文（GCM 随机 IV）、空串、篡改密文解密异常

- [ ] **Step 3: 运行测试确认失败**

- [ ] **Step 4: 实现 CredentialCryptoService**

算法：AES/GCM/NoPadding
IV：12 字节 SecureRandom
密文格式：Base64( IV(12B) || CipherText+Tag(16B) )
加密 init ENCRYPT_MODE + GCMParameterSpec(128, iv)
解密 init DECRYPT_MODE + 从 Base64 解码后拆分 IV 与密文

- [ ] **Step 5: 运行测试确认通过**

- [ ] **Step 6: 在 application.yml 中添加配置**

```yaml
credential:
  aes-key: ${CREDENTIAL_AES_KEY}
```

- [ ] **Step 7: Commit**

```bash
git commit -m "feat: 凭据保管库 - AES-256-GCM 加解密服务"
```

---

### Task 7: 凭据保管库模块 — 后端 CRUD + 自动脱敏

**Files:**
- Create: `src/main/java/com/zjl/worklog/credential/entity/CredentialEntity.java`
- Create: `src/main/java/com/zjl/worklog/credential/mapper/CredentialMapper.java`
- Create: `src/main/resources/mapper/CredentialMapper.xml`
- Create: `src/main/java/com/zjl/worklog/credential/CredentialController.java`
- Create: `src/main/resources/db/credential_module.sql`

**Interfaces:**
- Consumes: CredentialCryptoService.encrypt() / decrypt()
- Produces:
  - POST /api/credential — 新增（密码/密钥加密存储）
  - GET /api/credential — 分页查询（关键字/分类筛选）
  - GET /api/credential/{id} — 详情（密文脱敏为 null）
  - GET /api/credential/{id}/reveal — 返回解密明文
  - PUT /api/credential/{id} — 编辑
  - DELETE /api/credential/{id} — 删除

- [ ] **Step 1: 创建建表脚本 credential_module.sql**

```sql
CREATE TABLE IF NOT EXISTS credential (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  name varchar(200) NOT NULL COMMENT '凭据名称',
  category varchar(50) NOT NULL DEFAULT 'other' COMMENT '分类：database/server/app/network/other',
  username varchar(200) DEFAULT NULL COMMENT '账号（明文）',
  password_encrypted text DEFAULT NULL COMMENT '密码（AES-256-GCM 密文）',
  secret_key_encrypted text DEFAULT NULL COMMENT '密钥（AES-256-GCM 密文）',
  url varchar(500) DEFAULT NULL COMMENT '访问地址',
  tags varchar(500) DEFAULT NULL COMMENT '标签，逗号分隔',
  remark text DEFAULT NULL COMMENT '备注',
  expire_date date DEFAULT NULL COMMENT '过期日期',
  created_by bigint NOT NULL COMMENT '创建人 ID',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_category (category),
  KEY idx_expire_date (expire_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='凭据保管库';
```

- [ ] **Step 2: 创建实体类、Mapper 接口与 XML**

CredentialEntity: Lombok @Data，对应上表字段
CredentialMapper: insert, selectById, selectPage(关键字+分类+分页), countByFilter, update, deleteById, selectExpiringSoon
MyBatis XML 实现动态 SQL

- [ ] **Step 3: 创建 CredentialController**

核心逻辑：
- create：密码/密钥字段调用 cryptoService.encrypt() 后存库
- list：返回 CredentialListVO（密文字段替换为 hasPassword/hasSecretKey 布尔标记 + expired/expiringSoon 状态）
- detail：返回实体但密文字段置 null（脱敏）
- reveal：调用 decrypt() 返回明文 Map
- update：密码/密钥非空时重新加密，否则保留原密文

- [ ] **Step 4: 验证编译通过**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: 凭据保管库 - 后端 CRUD 与自动脱敏"
```

---

### Task 8: 凭据保管库模块 — 前端页面

**Files:**
- Create: `web/src/api/credential.js`
- Create: `web/src/views/CredentialListView.vue`
- Modify: `web/src/router/index.js` — 添加路由
- Modify: `web/src/views/HomeView.vue` — 侧边栏添加菜单

**Interfaces:**
- Consumes: /api/credential 全部接口
- Produces: 凭据保管库列表页（分页、搜索、分类筛选、过期高亮）+ 新增/编辑弹窗 + 密码明文查看

- [ ] **Step 1: 创建 API 封装 credential.js**

导出：credentialList, credentialCreate, credentialUpdate, credentialDetail, credentialReveal, credentialDelete

- [ ] **Step 2: 创建凭据保管库列表页 CredentialListView.vue**

页面功能：
- el-table 列表：凭据名称、分类（tag）、账号、密码/密钥图标、访问地址、过期日（红色高亮已过期、黄色即将过期）、操作
- 分类下拉筛选 + 关键字搜索
- 新增/编辑弹窗：名称、分类选择、账号、密码（password input）、密钥、URL、标签、过期日期、备注
- 查看密码弹窗：二次确认 -> 调用 reveal 接口 -> 显示明文 + 复制按钮
- 删除：二次确认

- [ ] **Step 3: 添加路由 /credential 和侧边栏菜单**

路由：/credential
侧边栏：在数据库文档菜单后添加「凭据保管库」（Lock 图标，ADMIN 角色限制）

- [ ] **Step 4: 验证前端完整流程**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: 凭据保管库 - 前端列表页与新增/编辑弹窗"
```

---

### Task 9: README 与部署文档更新

**Files:**
- Modify: `README.md`
- Modify: `deploy/nginx-worklog.conf`

- [ ] **Step 1: 更新 README 项目结构段落**

添加 dbdoc/ 和 credential/ 目录说明

- [ ] **Step 2: 更新数据库脚本执行顺序**

补充 db_doc_module.sql 和 credential_module.sql

- [ ] **Step 3: 更新环境变量说明**

补充 CREDENTIAL_AES_KEY 配置说明

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: 更新 README 与部署文档，补充新模块说明"
```

---

## 任务依赖关系

- Task 1 (更名) — 独立，最先执行
- Task 2→3→4→5 — 数据库文档模块链
- Task 6→7→8 — 凭据模块链
- Task 2 和 Task 6 可并行
- Task 9 在所有模块完成后执行
