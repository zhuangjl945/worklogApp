# 工作日志管理系统 - 使用说明手册

> 📢 **准备发布到 GitHub？** 请查看 [GitHub 发布指南](GITHUB_PUBLISH.md) 和 [发布前检查清单](PUBLISH_CHECKLIST.md)

## 📋 项目简介

工作日志管理系统是一个基于 Spring Boot 和 Vue 3 的企业级工作记录管理平台，旨在帮助团队高效记录、管理和追踪日常工作内容。系统支持工作记录管理、供应商管理、合同管理、数据统计分析等功能。

### 主要特性

- ✅ **工作记录管理**：支持创建、编辑、查询、删除工作记录，支持任务转移
- ✅ **分类管理**：灵活的工作分类和状态管理
- ✅ **供应商管理**：完整的供应商信息及联系人管理
- ✅ **合同管理**：合同全生命周期管理，包括付款计划跟踪
- ✅ **数据统计**：多维度数据报表和可视化分析
- ✅ **文件管理**：集成阿里云OSS，支持文件上传和管理
- ✅ **权限控制**：基于JWT的身份认证和权限管理
- ✅ **科室管理**：支持多级科室树形结构管理

## 🛠 技术栈

### 后端技术
- **框架**：Spring Boot 3.5.10
- **数据库**：MySQL 8.0+
- **ORM**：MyBatis 3.0.3
- **安全**：Spring Security + JWT
- **构建工具**：Maven
- **Java版本**：JDK 17+

### 前端技术
- **框架**：Vue 3.5.24
- **构建工具**：Vite 7.2.4
- **UI组件库**：Element Plus 2.13.2
- **路由**：Vue Router 4.6.4
- **HTTP客户端**：Axios 1.13.4
- **图表库**：ECharts 6.0.0
- **工具库**：Lodash-es 4.17.23

## 📦 环境要求

### 开发环境
- **JDK**：17 或更高版本
- **Node.js**：16.x 或更高版本
- **Maven**：3.6+ 
- **MySQL**：8.0 或更高版本
- **IDE**：推荐 IntelliJ IDEA 或 VS Code

### 生产环境
- **服务器**：Linux/Windows Server
- **Java运行环境**：JDK 17+
- **数据库**：MySQL 8.0+
- **Web服务器**：Nginx（可选，用于前端静态资源）

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd worklog
```

### 2. 数据库配置

#### 2.1 创建数据库

```sql
CREATE DATABASE work_log_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

#### 2.2 执行SQL脚本

依次执行以下SQL脚本文件：

```bash
# 工作记录模块
mysql -u root -p work_log_system < src/main/resources/db/work_record_module.sql

# 合同模块
mysql -u root -p work_log_system < src/main/resources/db/contract_module.sql
```

#### 2.3 配置数据库连接

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/work_log_system?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    username: root
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver
```

### 3. 配置JWT密钥

编辑 `src/main/resources/application.yml`，修改JWT密钥：

```yaml
jwt:
  secret: "your-long-random-secret-key-at-least-32-bytes"
  expire-seconds: 7200
```

### 4. 配置阿里云OSS（可选）

如果需要使用文件上传功能，需要配置阿里云OSS：

```yaml
aliyun:
  oss:
    endpoint: oss-cn-hangzhou.aliyuncs.com
    bucket: your-bucket-name
    access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_OSS_ACCESS_KEY_SECRET:}
    dir-prefix: work-records/
```

可以通过环境变量设置：
```bash
export ALIYUN_OSS_ACCESS_KEY_ID=your_access_key_id
export ALIYUN_OSS_ACCESS_KEY_SECRET=your_access_key_secret
```

### 5. 启动后端服务

```bash
# 使用Maven编译打包
mvn clean package

# 运行Spring Boot应用
java -jar target/worklog-0.0.1-SNAPSHOT.jar

# 或者使用Maven直接运行
mvn spring-boot:run
```

后端服务默认运行在：`http://localhost:8080`

### 6. 启动前端服务

```bash
# 进入前端目录
cd web

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端服务默认运行在：`http://localhost:5173`

### 7. 构建生产版本

#### 后端构建

```bash
mvn clean package -DskipTests
```

生成的JAR文件位于：`target/worklog-0.0.1-SNAPSHOT.jar`

#### 前端构建

```bash
cd web
npm run build
```

构建产物位于：`web/dist/` 目录

## 📖 功能模块说明

### 1. 用户认证与权限管理

#### 1.1 用户登录
- 支持用户名/工号登录
- JWT Token认证机制
- Token有效期：2小时（可配置）

#### 1.2 用户管理
- 用户信息管理（增删改查）
- 用户状态管理（启用/禁用）
- 密码重置功能
- 用户与科室关联

#### 1.3 科室管理
- 支持多级科室树形结构
- 科室的增删改查
- 科室状态管理
- 科室排序功能

### 2. 工作记录管理

#### 2.1 工作记录
- **创建记录**：记录工作标题、内容、分类、状态、时间等
- **编辑记录**：修改工作记录信息
- **查询记录**：支持多条件筛选（用户、科室、分类、状态、时间范围）
- **删除记录**：软删除机制
- **状态更新**：更新工作记录状态
- **图片上传**：支持上传工作相关图片（OSS存储）

#### 2.2 工作分类管理
- 按科室管理分类
- 分类的增删改查
- 分类状态管理
- 分类编码唯一性校验

#### 2.3 工作状态管理
- 预定义工作状态（待处理、进行中、已完成、已归档等）
- 状态排序管理
- 状态启用/停用

#### 2.4 工作记录日志
- 记录工作处理详情
- 支持添加、编辑、删除日志
- 记录操作人和操作时间

#### 2.5 工作费用管理
- 记录工作相关费用
- 费用类型：配件、耗材、服务费、交通费、其他
- 费用金额和说明
- 费用记录的增删改查

#### 2.6 任务转移
- 发起任务转移申请
- 接收转移任务
- 接受/拒绝转移
- 转移状态跟踪

### 3. 供应商管理

#### 3.1 供应商信息
- 供应商基本信息管理
- 供应商编码自动生成
- 统一社会信用代码管理
- 银行账户信息管理
- 供应商状态管理（启用/禁用）

#### 3.2 供应商联系人
- 联系人信息管理
- 主要联系人标记
- 联系人职位、电话、邮箱管理

### 4. 合同管理

#### 4.1 合同信息
- **合同类型**：采购、销售、服务、其他
- **合同方向**：采购（我方付款）、销售（我方收款）
- **合同状态**：草稿、执行中、已完成、已终止、已过期
- **合同基本信息**：合同编号、名称、金额、日期等
- **合同文件**：支持上传合同原件（OSS存储）

#### 4.2 付款计划管理
- 创建付款计划
- 付款计划类型：收款、付款
- 计划付款日期和金额
- 实际付款记录
- 付款状态跟踪（待付款、已付款、已逾期）

#### 4.3 合同状态流转
- 启动合同
- 完成合同
- 终止合同
- 续签合同

#### 4.4 合同提醒
- 即将到期合同提醒
- 即将到期付款计划提醒

### 5. 数据统计与报表

#### 5.1 工作负荷统计
- 按分类统计工作负荷
- 按用户、科室、分类统计
- 数据可视化展示（ECharts图表）

#### 5.2 费用统计
- 按费用类型统计
- 费用趋势分析

#### 5.3 仪表盘
- 综合数据概览
- 关键指标展示

## 📱 使用指南

### 首次使用

1. **初始化管理员账户**
   - 需要在数据库中手动创建第一个管理员用户
   - 或者通过数据库直接插入用户记录

2. **登录系统**
   - 访问前端地址：`http://localhost:5173`
   - 使用管理员账号登录

3. **基础数据配置**
   - 创建科室信息
   - 创建工作分类
   - 创建工作状态（如需要）
   - 创建用户账号

### 日常工作流程

#### 创建工作记录

1. 进入"工作记录"页面
2. 点击"新建"按钮
3. 填写工作信息：
   - 工作标题（必填）
   - 工作内容（可选）
   - 选择工作分类（必填）
   - 选择工作状态（默认：待处理）
   - 设置开始时间和结束时间
   - 标记是否重要
   - 上传相关图片（可选）
4. 点击"保存"完成创建

#### 管理工作记录

- **查看详情**：点击记录行的"详情"按钮
- **编辑记录**：在详情页面点击"编辑"按钮
- **更新状态**：在列表或详情页面更新工作状态
- **添加日志**：在详情页面添加工作处理日志
- **记录费用**：在详情页面添加相关费用
- **转移任务**：在详情页面发起任务转移

#### 管理供应商

1. 进入"供应商管理"页面
2. 点击"新建供应商"
3. 填写供应商信息：
   - 供应商名称（必填）
   - 统一社会信用代码
   - 法定代表人
   - 注册地址
   - 银行账户信息
4. 添加联系人信息
5. 保存供应商信息

#### 管理合同

1. 进入"合同管理"页面
2. 点击"新建合同"
3. 填写合同信息：
   - 合同编号（系统自动生成或手动输入）
   - 合同名称（必填）
   - 合同类型和方向
   - 选择供应商或客户
   - 合同金额
   - 合同日期
   - 付款条款
4. 创建付款计划
5. 上传合同文件（可选）
6. 保存合同

#### 查看报表

1. 进入"报表"菜单
2. 选择相应的报表类型
3. 设置筛选条件
4. 查看统计结果和图表

## 🔌 API接口文档

### 认证接口

#### 用户登录
```
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "password"
}
```

#### 获取当前用户信息
```
GET /api/auth/me
Authorization: Bearer {token}
```

#### 退出登录
```
POST /api/auth/logout
Authorization: Bearer {token}
```

### 工作记录接口

#### 分页查询工作记录
```
GET /api/work-records?page=1&size=10&userId=1&categoryId=1&statusId=1
Authorization: Bearer {token}
```

#### 创建工作记录
```
POST /api/work-records
Authorization: Bearer {token}
Content-Type: application/json

{
  "title": "工作标题",
  "content": "工作内容",
  "categoryId": 1,
  "statusId": 1,
  "startTime": "2024-01-01T09:00:00",
  "endTime": "2024-01-01T18:00:00",
  "isImportant": false
}
```

#### 更新工作记录
```
PUT /api/work-records/{id}
Authorization: Bearer {token}
Content-Type: application/json
```

#### 删除工作记录
```
DELETE /api/work-records/{id}
Authorization: Bearer {token}
```

#### 更新工作状态
```
PUT /api/work-records/{id}/status
Authorization: Bearer {token}
Content-Type: application/json

{
  "statusId": 2
}
```

### 供应商接口

#### 获取供应商列表
```
GET /api/supplier/list?page=1&size=10&keyword=关键词
Authorization: Bearer {token}
```

#### 创建供应商
```
POST /api/supplier
Authorization: Bearer {token}
Content-Type: application/json

{
  "supplierName": "供应商名称",
  "supplierShortName": "简称",
  "creditCode": "统一社会信用代码",
  ...
}
```

### 合同接口

#### 获取合同列表
```
GET /api/contract/list?page=1&size=10&status=30
Authorization: Bearer {token}
```

#### 创建合同
```
POST /api/contract
Authorization: Bearer {token}
Content-Type: application/json

{
  "contractNo": "HT20240101001",
  "contractName": "合同名称",
  "contractType": 1,
  "contractDirection": 1,
  "contractAmount": 100000.00,
  ...
}
```

### 文件上传接口

#### 获取OSS上传策略
```
GET /api/oss/policy
Authorization: Bearer {token}
```

#### 删除OSS文件
```
DELETE /api/oss/object?objectName=file/path
Authorization: Bearer {token}
```

## ⚙️ 配置说明

### 后端配置（application.yml）

```yaml
server:
  port: 8080  # 服务端口

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/work_log_system
    username: root
    password: your_password

jwt:
  secret: "your-secret-key"  # JWT密钥，建议至少32字符
  expire-seconds: 7200  # Token过期时间（秒）

aliyun:
  oss:
    endpoint: oss-cn-hangzhou.aliyuncs.com
    bucket: your-bucket-name
    access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_OSS_ACCESS_KEY_SECRET:}
    dir-prefix: work-records/
```

### 前端配置

前端API基础URL配置在 `web/src/api/http.js` 中：

```javascript
const baseURL = 'http://localhost:8080/api'
```

生产环境需要修改为实际的后端服务地址。

## 🐛 常见问题

### 1. 数据库连接失败

**问题**：启动后端时提示数据库连接失败

**解决方案**：
- 检查MySQL服务是否启动
- 确认数据库名称、用户名、密码是否正确
- 检查数据库用户是否有足够权限
- 确认数据库时区设置

### 2. JWT Token过期

**问题**：登录后一段时间提示Token过期

**解决方案**：
- 重新登录获取新Token
- 或修改 `application.yml` 中的 `jwt.expire-seconds` 增加过期时间

### 3. 前端无法连接后端

**问题**：前端页面无法获取数据

**解决方案**：
- 检查后端服务是否正常启动
- 确认前端API配置的baseURL是否正确
- 检查CORS配置（如跨域问题）
- 查看浏览器控制台错误信息

### 4. 文件上传失败

**问题**：上传文件到OSS失败

**解决方案**：
- 检查阿里云OSS配置是否正确
- 确认AccessKey和SecretKey是否有效
- 检查OSS Bucket是否存在且有权限
- 确认网络连接正常

### 5. 前端构建失败

**问题**：`npm run build` 失败

**解决方案**：
- 删除 `node_modules` 和 `package-lock.json`
- 重新执行 `npm install`
- 检查Node.js版本是否符合要求
- 查看具体错误信息

## 🔒 安全建议

1. **生产环境配置**
   - 修改默认JWT密钥为强随机字符串
   - 使用环境变量管理敏感配置
   - 启用HTTPS
   - 配置防火墙规则

2. **数据库安全**
   - 使用强密码
   - 限制数据库访问IP
   - 定期备份数据库
   - 启用数据库审计

3. **应用安全**
   - 定期更新依赖包
   - 实施输入验证和SQL注入防护
   - 配置CORS策略
   - 实施访问日志记录

## 📝 开发指南

### 项目结构

```
worklog/
├── src/main/java/com/zjl/worklog/    # 后端Java代码
│   ├── auth/                          # 认证模块
│   ├── common/                        # 公共模块
│   ├── contract/                      # 合同模块
│   ├── dept/                          # 科室模块
│   ├── oss/                           # 文件上传模块
│   ├── security/                      # 安全配置
│   ├── supplier/                      # 供应商模块
│   ├── user/                          # 用户模块
│   └── work/                          # 工作记录模块
├── src/main/resources/                # 资源文件
│   ├── application.yml                # 应用配置
│   ├── db/                            # 数据库脚本
│   └── mapper/                        # MyBatis映射文件
├── web/                               # 前端项目
│   ├── src/
│   │   ├── api/                       # API接口
│   │   ├── components/                # 组件
│   │   ├── router/                    # 路由配置
│   │   ├── stores/                    # 状态管理
│   │   └── views/                     # 页面视图
│   └── package.json
└── pom.xml                            # Maven配置
```

### 代码规范

- 遵循Java编码规范
- 使用Lombok简化代码
- 统一异常处理
- API响应统一格式

### 提交规范

- 提交信息清晰明确
- 一个提交只做一件事
- 提交前运行测试

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

## 👥 贡献

欢迎提交Issue和Pull Request！

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- 提交Issue
- 发送邮件

## 🔄 更新日志

### v0.0.1-SNAPSHOT
- 初始版本发布
- 实现基础工作记录管理功能
- 实现供应商管理功能
- 实现合同管理功能
- 实现数据统计报表功能

---

**注意**：本文档会持续更新，请关注最新版本。
