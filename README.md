# 工作日志管理系统

> 一个基于 Spring Boot 和 Vue 3 的企业级工作记录管理平台

[![Java](https://img.shields.io/badge/Java-17-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.10-brightgreen)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5.24-4FC08D)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

## 📋 项目简介

工作日志管理系统是一个功能完整的企业级工作记录管理平台，旨在帮助团队高效记录、管理和追踪日常工作内容。系统支持工作记录管理、供应商管理、合同管理、数据统计分析等功能。

### 主要特性

- ✅ **工作记录管理**：支持创建、编辑、查询、删除工作记录，支持任务转移
- ✅ **分类管理**：灵活的工作分类和状态管理，支持按科室分类
- ✅ **供应商管理**：完整的供应商信息及联系人管理
- ✅ **合同管理**：合同全生命周期管理，包括付款计划跟踪和到期提醒
- ✅ **数据统计**：多维度数据报表和可视化分析（ECharts）
- ✅ **文件管理**：集成阿里云OSS，支持文件上传和管理
- ✅ **权限控制**：基于JWT的身份认证和权限管理
- ✅ **科室管理**：支持多级科室树形结构管理
- ✅ **任务转移**：支持工作任务的转移和审批流程
- ✅ **费用管理**：记录工作相关费用，支持费用类型分类

## 🛠 技术栈

### 后端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.5.10 | 核心框架 |
| MyBatis | 3.0.3 | ORM框架 |
| MySQL | 8.0+ | 数据库 |
| Spring Security | - | 安全框架 |
| JWT | 0.11.5 | 身份认证 |
| Lombok | - | 代码简化 |
| Maven | 3.6+ | 构建工具 |
| JDK | 17+ | Java版本 |

### 前端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| Vue | 3.5.24 | 前端框架 |
| Vite | 7.2.4 | 构建工具 |
| Element Plus | 2.13.2 | UI组件库 |
| Vue Router | 4.6.4 | 路由管理 |
| Axios | 1.13.4 | HTTP客户端 |
| ECharts | 6.0.0 | 图表库 |
| XLSX | 0.18.5 | Excel处理 |

## 📦 环境要求

### 开发环境

- **JDK**: 17 或更高版本
- **Node.js**: 16.x 或更高版本
- **Maven**: 3.6+
- **MySQL**: 8.0 或更高版本
- **IDE**: IntelliJ IDEA / VS Code（推荐）

### 生产环境

- **服务器**: Linux/Windows Server
- **Java运行环境**: JDK 17+
- **数据库**: MySQL 8.0+
- **Web服务器**: Nginx（可选，用于前端静态资源）

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/zhuangj1945/worklogApp.git
cd worklogApp
```

### 2. 数据库配置

#### 2.1 创建数据库

```sql
CREATE DATABASE work_log_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

#### 2.2 执行SQL脚本

依次执行项目中的SQL脚本文件：

```bash
# 工作记录模块
mysql -u root -p work_log_system < src/main/resources/db/work_record_module.sql

# 合同模块
mysql -u root -p work_log_system < src/main/resources/db/contract_module.sql
```

#### 2.3 配置数据库连接

复制配置模板并修改：

```bash
cp src/main/resources/application.yml.example src/main/resources/application.yml
```

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/work_log_system?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    username: root
    password: your_password  # 修改为你的数据库密码
    driver-class-name: com.mysql.cj.jdbc.Driver
```

### 3. 配置JWT密钥

编辑 `src/main/resources/application.yml`，修改JWT密钥：

```yaml
jwt:
  secret: "your-long-random-secret-key-at-least-32-bytes"  # 修改为强随机字符串
  expire-seconds: 7200
```

### 4. 配置阿里云OSS（可选）

如果需要使用文件上传功能，配置阿里云OSS：

```yaml
aliyun:
  oss:
    endpoint: oss-cn-hangzhou.aliyuncs.com
    bucket: your-bucket-name
    access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_OSS_ACCESS_KEY_SECRET:}
    dir-prefix: work-records/
```

通过环境变量设置：

```bash
# Windows
set ALIYUN_OSS_ACCESS_KEY_ID=your_access_key_id
set ALIYUN_OSS_ACCESS_KEY_SECRET=your_access_key_secret

# Linux/macOS
export ALIYUN_OSS_ACCESS_KEY_ID=your_access_key_id
export ALIYUN_OSS_ACCESS_KEY_SECRET=your_access_key_secret
```

### 5. 启动后端服务

```bash
# 方式一：使用Maven运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/worklog-0.0.1-SNAPSHOT.jar
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

### 7. 访问系统

打开浏览器访问：`http://localhost:5173`

默认需要先创建管理员账户（通过数据库直接插入或使用初始化脚本）。

## 📖 功能模块

### 1. 用户认证与权限管理

- 用户登录（JWT Token认证）
- 用户信息管理
- 用户状态管理（启用/禁用）
- 密码重置功能

### 2. 工作记录管理

- **工作记录**：创建、编辑、查询、删除工作记录
- **工作分类**：按科室管理分类，支持分类的增删改查
- **工作状态**：预定义工作状态管理
- **工作日志**：记录工作处理详情
- **工作费用**：记录工作相关费用
- **任务转移**：支持任务转移申请和审批

### 3. 供应商管理

- 供应商基本信息管理
- 供应商编码自动生成
- 供应商联系人管理
- 供应商状态管理

### 4. 合同管理

- 合同信息管理（采购、销售、服务等类型）
- 付款计划管理
- 合同状态流转（启动、完成、终止、续签）
- 合同到期提醒
- 付款计划到期提醒

### 5. 数据统计与报表

- 工作负荷统计（按分类、用户、科室）
- 费用统计（按费用类型）
- 数据可视化展示（ECharts图表）

### 6. 科室管理

- 多级科室树形结构
- 科室的增删改查
- 科室状态管理

## 📁 项目结构

```
worklog/
├── src/main/java/com/zjl/worklog/    # 后端Java代码
│   ├── auth/                          # 认证模块
│   ├── common/                        # 公共模块（异常处理、响应封装）
│   ├── contract/                      # 合同模块
│   ├── dept/                          # 科室模块
│   ├── oss/                           # 文件上传模块
│   ├── security/                      # 安全配置
│   ├── supplier/                      # 供应商模块
│   ├── user/                          # 用户模块
│   └── work/                          # 工作记录模块
├── src/main/resources/                # 资源文件
│   ├── application.yml                # 应用配置（需复制模板）
│   ├── application.yml.example       # 配置模板
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

## 🔧 配置说明

### 后端配置

主要配置文件：`src/main/resources/application.yml`

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
- 检查CORS配置
- 查看浏览器控制台错误信息

### 4. 文件上传失败

**问题**：上传文件到OSS失败

**解决方案**：
- 检查阿里云OSS配置是否正确
- 确认AccessKey和SecretKey是否有效
- 检查OSS Bucket是否存在且有权限
- 确认网络连接正常

### 5. 编译错误：找不到主类

**问题**：`ClassNotFoundException: com.zjl.worklog.WorklogApplication`

**解决方案**：
```bash
# 重新编译项目
mvn clean compile

# 或完整构建
mvn clean package
```

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

### 代码规范

- 遵循Java编码规范
- 使用Lombok简化代码
- 统一异常处理
- API响应统一格式

### 提交规范

推荐使用约定式提交：

- `feat`: 新功能
- `fix`: 修复bug
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 代码重构
- `test`: 测试相关
- `chore`: 构建/工具相关

示例：
```bash
git commit -m "feat: 添加工作记录导出功能"
git commit -m "fix: 修复合同状态更新bug"
```

## 📄 许可证

本项目采用 [MIT License](LICENSE) 许可证。

## 👥 贡献

欢迎提交Issue和Pull Request！

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 [Issue](https://github.com/zhuangj1945/worklogApp/issues)
- 发送邮件

## 🔄 更新日志

### v0.0.1-SNAPSHOT

- 初始版本发布
- 实现基础工作记录管理功能
- 实现供应商管理功能
- 实现合同管理功能
- 实现数据统计报表功能
- 集成阿里云OSS文件上传

---

**注意**：本文档会持续更新，请关注最新版本。
