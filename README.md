# 工作日志管理系统

## 📋 项目简介

工作日志管理系统是一个基于 **Spring Boot + Vue 3** 的内部管理系统，用于记录和统计日常工作情况，并对供应商、合同等信息进行统一管理。  
适合科室/部门级别的工作登记、统计分析和简单的业务协同。

---

## ✨ 主要功能

- **工作记录管理**：新增、编辑、删除、查询工作记录，支持任务转移、处理日志、费用记录、图片上传等
- **工作分类与状态**：支持按科室配置工作分类、工作状态字典
- **供应商管理**：供应商基本信息、联系人信息的维护与查询
- **合同管理**：合同基础信息、合同文件、付款计划及实际付款记录
- **统计报表**：按分类、科室、人员等维度对工作量和费用进行统计分析
- **科室与用户管理**：多级科室树、用户账号与状态管理
- **权限与认证**：基于 JWT 的登录认证与接口访问控制
- **文件存储**：集成阿里云 OSS，用于保存合同、工作记录相关附件/图片

## 🛠 技术栈

### 后端

- Java 17
- Spring Boot 3.5.10
- Spring MVC / Validation
- Spring Security + JWT
- MyBatis
- MySQL 8+
- Maven

### 前端

- Vue 3 + Vite
- Vue Router 4
- Element Plus
- Axios
- ECharts

## 📦 环境要求

- **JDK**：17+
- **Node.js**：16+（推荐 18 LTS）
- **MySQL**：8.0+
- **Maven**：3.6+
- 操作系统：Windows / Linux / macOS 均可

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/你的用户名/你的仓库名.git
cd worklog   # 或你的项目目录名
```

### 2. 初始化数据库

1. 创建数据库：

```sql
CREATE DATABASE work_log_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 执行 SQL 脚本（根据你的实际路径调整）：

```bash
# 工作记录相关表
mysql -u root -p work_log_system < src/main/resources/db/work_record_module.sql

# 如有其他模块脚本，可在此补充
```

### 3. 配置后端

编辑 `src/main/resources/application.yml`（或参考你自己的模板）：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/work_log_system?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    username: root
    password: 你的数据库密码
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

jwt:
  # 生产环境请使用更复杂且至少 32 字符的密钥
  secret: "change-me-to-a-long-random-secret-at-least-32-bytes"
  expire-seconds: 7200

aliyun:
  oss:
    endpoint: oss-cn-hangzhou.aliyuncs.com
    bucket: your-bucket-name
    access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_OSS_ACCESS_KEY_SECRET:}
    dir-prefix: work-records/
```

> 建议：把真实密码、密钥通过环境变量传入，仓库中只保留 `application.yml` 或模板文件中示例占位符。

### 4. 启动后端

```bash
# 在项目根目录
mvn clean package
java -jar target/worklog-0.0.1-SNAPSHOT.jar
# 或
mvn spring-boot:run
```

后端默认地址：`http://localhost:8080`

### 5. 启动前端

```bash
cd web
npm install
npm run dev
```

前端默认地址：`http://localhost:5173`

浏览器访问前端地址即可使用系统（首次需要先登录）。

## 📖 功能模块概要

- **用户与权限**：登录、退出、当前用户信息、用户管理、JWT 认证
- **科室管理**：科室树结构维护、启用禁用、排序
- **工作记录**：记录日常工作、处理日志、费用记录、任务转移、图片上传
- **分类与状态**：工作分类（按科室）、工作状态字典
- **供应商与合同**：供应商及联系人、合同信息、合同文件、付款计划与记录、状态流转
- **报表统计**：按分类/部门/人员/费用等维度统计，并通过图表展示

## 🧪 常用命令

### 后端

```bash
mvn clean compile         # 编译
mvn spring-boot:run       # 运行
mvn clean package -DskipTests  # 打包（跳过测试）
```

### 前端

```bash
cd web
npm install
npm run dev        # 开发环境
npm run build      # 生产构建
npm run preview    # 本地预览构建产物
```

## 🧯 常见问题

- **无法连接数据库**：确认 MySQL 已启动，检查 `application.yml` 中的 URL/用户名/密码，数据库名是否为 `work_log_system`。
- **接口 401/403**：检查前端是否携带 `Authorization: Bearer <token>`，以及 JWT 密钥配置是否一致。
- **前端路由 404**：生产部署时使用 Nginx 做前端静态资源托管，并将未匹配路由转发到前端 `index.html`。

## 📄 许可证

本项目默认使用 MIT 协议（如有需要可根据实际情况修改为其他许可证）。

## 🤝 贡献

欢迎提交 Issue 或 Pull Request 进行改进。

