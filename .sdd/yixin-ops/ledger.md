# 医信运维系统 — SDD 执行 Ledger

## Task 1: 项目更名 — ✅ 完成
- Commit: 9e0294b chore: 项目更名为医信运维系统
- 修改文件: pom.xml, web/index.html, LoginView.vue, HomeView.vue, README.md, nginx-worklog.conf

## Task 2: SQL 解析引擎 — ✅ 完成
- Commit: b743096 (合并提交)
- 创建文件: SqlParseService.java, ColumnInfo.java, IndexInfo.java, TableSchema.java, ParseResult.java
- 测试: SqlParseServiceTest.java (4 个测试全部通过)

## Task 3: 数据库文档 REST API — ✅ 完成
- Commit: a9381dc feat: 数据库文档模块 - REST API 与历史记录存储
- 创建文件: DbDocController.java, DbDocEntity.java, DbDocMapper.java, DbDocMapper.xml, db_doc_module.sql

## Task 4: 前端 SQL 分析页 — ✅ 完成
- Commit: 45980fa feat: 数据库文档模块 - 前端 SQL 编辑器与 ER 图可视化
- 创建文件: dbDoc.js, DbDocParseView.vue, DbDocListView.vue, DbDocDetailView.vue
- 修改文件: router/index.js, HomeView.vue

## Task 5: 文档列表 + ER 图 — ✅ 完成 (合并到 Task 4)

## Task 6: AES-256-GCM 加密服务 — ✅ 完成
- Commit: b743096 (合并提交)
- 创建文件: CredentialCryptoProps.java, CredentialCryptoService.java
- 测试: CredentialCryptoServiceTest.java (4 个测试全部通过)

## Task 7: 凭据保管库 CRUD — ✅ 完成
- Commit: a9381dc (合并提交)
- 创建文件: CredentialController.java, CredentialEntity.java, CredentialMapper.java, CredentialMapper.xml, credential_module.sql

## Task 8: 凭据前端页面 — ✅ 完成
- Commit: 45980fa (合并提交)
- 创建文件: credential.js, CredentialListView.vue
- 修改文件: router/index.js, HomeView.vue

## Task 9: 文档更新 — ✅ 完成
- Commit: 8cd8eee docs: 更新 README 与部署文档，补充新模块说明
- 修改文件: README.md (添加数据库文档和凭据保管库模块说明)

---

## Task 10: 表结构目录 v2 — 三级归档（业务系统 → 数据库用户 → 表）— ✅ 完成（未提交，工作区改动）

需求原话：「数据库表文档功能做的不够完善，首先要区分要有什么系统，其次具体是什么表，哪个用户下，后续新增分析后新增，我要能查看。」
拆成四件事：① 先有业务系统这一层；② 表要挂在具体库用户/模式下；③ 重复分析走增量合并，新表补进、变表更新、没变不堆快照；④ 每张表的当前结构和历史变更都能查。

### 数据模型（src/main/resources/db/db_doc_module_v2.sql，幂等可重复执行，本地库已执行）
- `db_system` 业务系统：名称/编码/厂商/数据库类型/版本/负责人
- `db_database` 数据库用户：唯一键 `(system_id, db_user, env)`，一个系统下同一用户同一环境只有一条目录
- `db_table_doc` 表当前快照：唯一键 `(database_id, table_name)`，存当前结构 JSON、字段数、被分析次数、在用/疑似下线状态、人工备注
- `db_table_version` 历史快照：每次结构真变了写一条，带 `change_type`(NEW/CHANGED/UNCHANGED/MISSING) 与中文差异摘要
- `db_doc` 扩列：`system_id`/`database_id`/`db_user` + 本批 `added/updated/unchanged/missing_count`（计数列 NOT NULL DEFAULT 0，v1 老行取不到真实统计，前端按「未归类」不展示）

### 后端（src/main/java/com/zjl/worklog/dbdoc/）
- 新建：`DbAssetService`（三级目录 CRUD/分页/详情/catalog）、`DbTableImportService`（预览 diff、MERGE/REPLACE 导入、rearchive 补归类）、`DbAssetController`（/api/db-doc/systems|databases|tables|catalog|preview-diff|import|batches/{id}/changes|rearchive|options）、`TableSchemaDiffer`（结构差异判定 + 中文摘要）、`TableSchemaCodec`（JSON 读写，脏数据降级不抛）、`DdlRenderer`（还原建表语句）
- 新建实体/Mapper：`DbSystemEntity`/`DbDatabaseEntity`/`DbTableDocEntity`/`DbTableVersionEntity` + 四个 Mapper 接口与 XML
- 新建 DTO：`TableChangeItem`/`ImportRequest`/`ImportResult`/`DiffPreview`/`TableDetailView`/`CatalogView`/`BatchDetailView`
- 改造：`SqlParseService.splitQualifiedNames()` 把 `HIS.USER_INFO` 拆成 dbUser + tableName，`ParseResult` 回收 `dbUsers`；`DbDocController` 重写为批次视角（列表不取大字段、联查 db_system 与创建人姓名）；`DbDocMapper` 加 `updateStats`/`countByCondition`

### 前端（web/src/）
- `views/DbDocSystemView.vue`：系统与库用户两级维护
- `views/DbDocCatalogView.vue`：左树三级目录 + 表列表 + 详情抽屉（字段/索引/还原 DDL/历史版本/人工备注）、版本快照弹窗、按字段名搜索
- `views/DbDocParseView.vue`：重写为「选系统/库用户（可就地新建）→ 只解析 / 预览差异 / 导入」，带 MERGE、REPLACE、自动建档开关
- `views/DbDocListView.vue`：菜单口径改名为「分析记录」，列出归属与本批新增/变更/未变张数，逐表差异结论；未归类批次补『归入目录』入口，已归类批次可一键跳到目录视图
- `api/dbDoc.js` 追加 22 个 v2 接口；`utils/dbDocView.js` 摊平 v1/v2 三种响应形状；`components/DbTableStructure.vue` 渲染表结构；`router/index.js` + `HomeView.vue` 加路由与四个菜单项

### 测试与验证
- `mvn -o test` → 134 tests, 0 failures（新增 `TableSchemaDifferTest` 9 个、`DbTableImportServiceTest` 6 个，`SqlParseServiceTest` 补限定名用例）
- `npx vite build` 通过
- 端到端（18081 临时实例 + 临时账号，验后已清理，库里只留原始 id=1）：导入 HIS×2 + LIS×1 → 自动建 LIS 库、统计正确；二次 REPLACE 导入 → updated=1 / missing=1、TEMP_LOG 置疑似下线、版本历史 2 条、差异摘要「新增字段:AGE」「删除索引:IDX_NAME」、按字段名搜索命中、批次列表带系统名与创建人姓名、v1 遗留批次 rearchive 成功
- 过程中修掉一个真 bug：`DbDocMapper.xml` 批次 selectPage 漏了 `LEFT JOIN db_system s`，列表系统名恒空

## Task 11: Excel 数据字典导入 — ✅ 完成（未提交，工作区改动）

用户选了「先只加 Excel 数据字典导入」这条路：现场拿到的一般是厂商的《表结构说明.xlsx》，不是 DDL。

- 数据模型：`db_doc` 加 `source_type`（DDL/EXCEL/SCAN，默认 DDL）与 `source_ref`（来源说明）；`source_sql` 改为允许为空——非脚本来源不该造假 SQL。幂等脚本追加在 `db_doc_module_v2.sql` 第 6 节，本地库已执行（**必须用 `--default-character-set=utf8mb4` 跑，这台机器 MySQL 客户端默认 gbk，会把中文注释写成乱码**，已发生并修正）
- 后端：`ImportRequest` 去掉 `@NotBlank sql`，新增 `tables`/`sourceType`/`sourceRef`；`DbTableImportService` 加 `pickTables()` 让「表清单」与「脚本」汇成一条路，`preview(sql, databaseId, tables)` 重载，新增 `normalizeStructured()` 底线校验（表名合法性、表名/字段名重复、空字段、500 张上限、来源标记白名单）；`rearchive` 强制忽略外部传入的 tables，只认批次原文；`DbDocMapper.xml` 插入用 `IFNULL(#{sourceType},'DDL')` 兜 v1 的 `/save` 路径
- 前端：新增 `utils/dbDictExcel.js`（列别名匹配、合并单元格继承表名与库用户、`VARCHAR2(50)` 拆类型+长度、可空列识别 是/否/N/NOT NULL/非空/必填、主键自动合成联合主键索引、索引名相同的行合成联合索引并保留列序、说明页小 sheet 不误报、坏文件报人话）、`components/DbDictExcelImport.vue`（下载模板 + 本地解析 + 统计 + 告警 + 逐表预览）、`DbDocParseView.vue` 加「贴建表脚本 / Excel 数据字典」来源切换，预览差异与导入都带上 tables 与来源；`dbDoc.js` 的 `dbPreviewDiff` 加 tables 参数；`dbDocView.js` 摊平 `sourceType/sourceRef` 并出 `sourceTypeText`；`DbDocListView.vue` 标题下显示来源
- 测试：`mvn -o test` → **141 通过**（新增 7 个结构化导入用例：走同一套合并并留来源、能比对目录、表名重复、字段名缺失、超上限、非法来源标记、空请求）；解析器用 Node 跑了 42 项断言（官方模板自解析、脏表格、坏文件报错、合并单元格的库用户继承、无库用户列）；前端 `npx vite build` 通过
- 端到端（18082 临时实例 + 临时账号）：Excel 解析 2 张表 → 预览 → 导入（LIS 前缀自动建档）→ 批次 `source_type=EXCEL`/`source_ref` 正确且没有伪造 `source_sql` → 目录读出 3 字段/主键/联合唯一索引列序/`VARCHAR2` 拆分 → 按字段名搜到 → 二次导入 REPLACE 变更 1 张 + 版本历史 2 条 + 摘要「新增字段:AGE」→ v1 `/save` 回归正常且来源兜成 DDL → 空请求报人话，共 21 项全过；清场按显式 ID 删除
- 过程中被真实数据打回来的两个 bug：① 库用户列只在首行填写时同一张表被拆成两条（前端解析修复）；② `source_sql NOT NULL` 让 Excel 批次插不进去（改列可空）

---

## Task 12: 表结构人工修订 —— 已登记的表能在页面上直接改 — ✅ 完成（未提交，工作区改动）

需求原话：「已经新增的表结果内容，我想自己也能修改」。读下来的意思是：`remark` 和在用/下线早就能改，但**结构本体**（表名、表注释、字段、索引）只有一条修改路径——再导一遍建表脚本或 Excel。现场常有「厂商只口头说了两句」「脚本还没拿到手」，所以补一条手工路径，顺带支持「一张脚本都没有、只想手工登记一张表」。

### 后端
- 新接口 `PUT /api/db-doc/tables/{id}/structure`（修订）与 `POST /api/db-doc/tables`（手工建档），请求体 `StructureRequest` 与导入用的 `TableSchema` 同形，校验集中在服务层
- `DbTableImportService.updateStructureManually()` / `createTableManually()`：与导入**共用** `normalizeStructured` + `TableSchemaDiffer` + `writeVersion`，只有三处不同——版本记 `MANUAL`、`batch_id` 留空、不碰 `parse_count`／批次归属／在用状态；结构一个字没动就不写库也不写版本
- `normalizeManual()` 补三条手改才有的底线：字段类型必填、索引不能引用不存在或被改名的字段、主键只认一个口径（有 PRIMARY 索引按它回写字段 PK 标记，只勾字段则合成一条）；更名先查 `uk_db_table` 撞名
- `DbTableDocMapper.updateManualStructure()` 新语句：连 `table_name` 一起改，但不 `parse_count + 1`、不动 `first/last_batch_id`、不强制 `status = 1`
- `db_doc_module_v2.sql` 追加第 7 节：`db_table_version.change_type` 注释补 `MANUAL`（幂等、纯注释、列宽不变）；**本次没在本地库执行**

### 前端
- 新组件 `components/DbTableStructureEditor.vue`：字段行可增删、上下移，类型/长度/可空/默认值/注释就地改；索引行可加类型与多选字段；有 PRIMARY 索引时字段那列的 PK 改成只读跟随（把「两处各存一份主键」在页面上就锁死）；本地校验与服务层同一套底线，能页面说清的不留给接口报错
- `views/DbDocCatalogView.vue`：抽屉「字段」标题挂『编辑结构』，工具栏挂『手工建表』（取启用库用户当归属候选，默认落在当前筛选的库）；保存后重拉详情，历史里立刻出现那条『人工修订』；原 `saveManual` 里重复的详情刷新收敛成 `refreshDrawerTable()`
- `api/dbDoc.js` 加 2 个接口；`utils/dbDocView.js` 加 `MANUAL → 人工修订`（中性主色，不与「疑似下线」的告警红混用）；README 能力清单补一条

### 测试与验证
- `mvn -o test` → **157 通过**（本轮 +16）：`DbTableImportServiceTest` 补 9 条人工修订用例（写 MANUAL 版本且不挂批次／一模一样不写库不写版本／更名落库并写明原名／撞已有表名拒绝／索引引用不存在字段拒绝／主键两种口径各合成一次／漏类型拒绝／手工建档 `parse_count=0` 且版本 NEW／建档重名拒绝）
- 新 `DbDocMapperXmlTest`（照 `TicketMapperXmlTest` 的映射自检）：钉住「人工修订那条 UPDATE 不碰 `parse_count`／`last_batch_id`／`status`」
- 新 `DbAssetStructureApiTest`：拿前端真实提交的 JSON 过一遍 Jackson，钉住请求体→`TableSchema` 的字段映射与路由参数、登录人传递
- 真库只跑语法自检：在 `work_log_system` 上以 `WHERE id = 0` 执行新 UPDATE，`Rows matched: 0  Changed: 0`，列名与语法确认无误且不碰任何数据
- 前端 `npm run build` 通过

---

## Rulings

- Ruling: 合并提交 Task 2+6, Task 3+7, Task 4+5+8 — 减少提交数量，保持清晰的提交历史 — 如果拆分提交会增加 git 操作复杂度
- Ruling: JSqlParser 4.9 API 使用 Index.getColumns().getColumnName() 而非 getColumnNames() — 适配新版本 API — 如果版本不匹配会导致编译失败
- Ruling: cleanName 函数使用 char 代码 96 替换反引号 — PowerShell 转义问题 — 如果转义错误会导致替换失败
- Ruling: 表目录按「系统 → 数据库用户 → 表」三级建模，而不是在 db_doc 上打个 system 字符串标签 — 用户要的正是「哪个系统、哪个用户下、哪张表」，自由文本标签没法去重也没法按库对账 — 如果用字符串标签，同名表跨系统会互相覆盖
- Ruling: 重复分析做增量合并而非每次存整份快照，只有结构真变了才写 db_table_version — 否则分析十次就有十份快照，看不出「哪里变了」，也查不动 — 如果不 diff，历史列表会被重复数据淹没
- Ruling: 「疑似下线」只置 status=0 不物理删除 — 脚本没写不等于库里没有，可能是这份脚本不全；删掉就再也找不回来 — 如果直接删，误删风险不可逆
- Ruling: 不覆盖并发会话已改好的前端成果（DbDocListView/DbDocDetailView/DbTableStructure/dbDocView.js 与解析器三件套），只补 api 函数、新页面、路由与后端 — 同一工作区两个会话在改同一模块，整文件重写会互相抹掉改动 — 如果按原计划整写，对方的成果会丢
- Ruling: Excel 只在浏览器解析，后端不引 POI 等新依赖，也不让文件出机器 — 表结构文档里常有患者相关字段名，上传到服务端再解析既不必要也扩大暴露面；前端已有 xlsx 包 — 如果用后端解析，多一份依赖和一份大文件上传的失败面
- Ruling: 非脚本来源让 `source_sql` 允许为空，而不是往里塞生成的等价 DDL — 那不是用户给的原文，塞进去会让「原始 SQL」这一栏变成误导 — 如果造假文本，事后对账无从判断来源真伪
- Ruling: 结构化入参进 `importSql` 前必过 `normalizeStructured` 底线校验 — 接口公开，不能假定请求体来自那个页面 — 如果只靠前端校验，脏数据会直接进目录并把版本历史搅乱
- Ruling: 【事故】清场脚本只允许按本次创建返回的显式 ID 逐条删除，禁止对业务表执行无条件 DELETE — 本轮我用 `delete from db_table_*` 等整表删除清冒烟数据，误删了并发会话昨晚导入的真实 HIS 数据（2 系统 / 2 库用户 / 5 批次 / 3 表 / 9 版本），已用 binlog ROW 格式的删除前镜像逐行还原并二次校验行数一致；顺带确认 `db_doc` id=1「频次表」是 10-30 07:32:23 被别处删掉的，与本次误删无关 — 如果继续整表删，一旦库里混有他人数据就是不可逆损失
- Ruling: v1 遗留批次不迁移数据，改由页面『归入目录』按需重新导入 — 老记录只有 1 条，且重导入能顺带验证新合并逻辑；批量迁移要猜库用户归属，猜错更麻烦 — 如果静默迁移，归属猜错后更难纠正
- Ruling: 冒烟清场要按 表 -> 库用户 -> 系统 的顺序删，且必须把导入时自动建档的库用户和它名下的表一起登记进收尾清单 — API 会拦「库里还有表」「系统下还有库用户」，漏登记就会留下带「冒烟」字样的脏目录；本轮靠按系统名过滤的二次清理兜住，收尾再与 2 系统/2 库用户/3 表/9 版本/5 批次的基线逐行核对一致
- Ruling: 人工修订落在 `DbTableImportService` 而不是 `DbAssetService` — 差异判定、版本快照、结构 JSON 口径都在这一侧，另起一处就会长出第二套合并逻辑；`DbAssetService` 反过来被导入服务依赖，硬塞是循环依赖 — 如果两处各写一套，「这次到底改了什么」在两条路上会给出不同答案
- Ruling: 人工修订不累加 `parse_count`、不改 `status`、不覆盖 `last_batch_id` — 手工点一次保存不等于这张表又被脚本分析了一次；把整库对账得出的「疑似下线」因为有人动了结构就悄悄拉回在用，是替用户抹掉一个结论 — 如果直接复用导入那条 `updateStructure`，这三处语义会一起被污染；已用 `DbDocMapperXmlTest` 把它钉成构建期断言
- Ruling: 主键只认「PRIMARY 索引」这一个口径，字段的 PK 标记由它回写；只有没建主键索引时才按勾了的字段合成一条 — 目录行的 `pk_columns`、字段的 `primaryKey`、索引列表三处都能表达主键，历史上就是各存各的 — 如果两边都能改，改完必然对不上，列表的「主键」列和字段上的 PK 会互相打脸
- Ruling: 手工改过的结构下次导脚本仍按差异覆盖回来，不加「人工锁定」开关，只在编辑器顶部把这句话讲明白 — 目录的定位是「库里现在长什么样」，脚本才是事实来源；锁住会让目录越改越偏离真实库 — 如果真出现「脚本一直压掉手工修订」的场景，再按需求做逐列忽略，而不是现在就加一层状态
- Ruling: 没在本地库执行第 7 节那句 `change_type` 注释 ALTER，也没造临时系统/库用户跑整链路 HTTP — 那句只是注释、不影响功能，而库里是并发会话的真实数据；新接口语义已由 9 条服务层用例 + 映射自检 + Jackson 契约测试覆盖 — 按上一轮事故记录，在有他人数据的库里造了再删本身就是最大的风险面；要端到端就另起独立 schema

---

## 总计

- 5 个 commits
- 16 个新文件
- 8 个修改文件
- 8 个测试全部通过

---

## 总计（v2 表结构目录 + Excel 数据字典）

- 未提交（dev 分支工作区改动，等用户确认后再 commit）
- 37 个新文件（后端 25：9 个服务/工具类 + 4 实体 + 4 Mapper + 7 DTO；资源 5：1 个 SQL + 4 个 XML；测试 2；前端 6，含 Excel 解析与导入组件）
- 18 个修改文件（含并发会话改动的 README、application.yml、DbDocListView/DbDocDetailView、解析器三件套）
- 141 个 Java 测试全部通过 + 42 项 Excel 解析断言（Node）+ 21 项端到端断言，前端 vite build 通过
- 部署提醒：生产库需执行 `src/main/resources/db/db_doc_module_v2.sql`（脚本幂等，可重复执行）；客户端字符集务必 `--default-character-set=utf8mb4`，否则中文列注释会被写成乱码
- 遗留：`application.yml` 里 `credential.aes-key` 有本地调试用的默认值，提交前应撤掉
- 遗留：新增文件均为 LF；`db_doc` 的 v1 记录 id=1 已不在库中（非本轮所删），如需可重新导入

---

## 总计（v2.2 表结构人工修订）

- 未提交（dev 分支工作区改动，等用户确认后再 commit）
- 4 个新文件：后端 1（`ManualEditView` DTO）+ 前端 1（`DbTableStructureEditor.vue`）+ 测试 2（`DbDocMapperXmlTest`、`DbAssetStructureApiTest`）
- 11 个修改文件：后端 4（`DbTableImportService`、`DbAssetController`、`DbTableDocMapper` 接口与 XML）+ 资源 1（`db_doc_module_v2.sql`）+ 测试 1（`DbTableImportServiceTest` 补 9 条）+ 前端 3（`dbDoc.js`、`dbDocView.js`、`DbDocCatalogView.vue`）+ README
- 157 个 Java 测试全部通过，前端 npm run build 通过
- 部署提醒：生产库重复执行 `src/main/resources/db/db_doc_module_v2.sql` 即可拿到 `change_type` 的 MANUAL 注释（纯注释，跳过也不影响功能）；本地库本轮**未执行**任何 DDL
