# GitHub 发布前检查清单

在将项目发布到 GitHub 之前，请仔细检查以下项目，确保没有遗漏敏感信息。

## ✅ 安全检查

### 1. 配置文件检查

- [ ] **application.yml** 中是否包含真实密码？
  - 数据库密码应使用环境变量或占位符
  - 示例：`password: ${DB_PASSWORD:your_password_here}`

- [ ] **JWT 密钥**是否为生产环境密钥？
  - 应使用示例密钥或环境变量
  - 示例：`secret: "change-me-to-a-long-random-secret"`

- [ ] **阿里云 OSS 密钥**是否已移除？
  - AccessKey ID 和 Secret 应使用环境变量
  - 示例：`access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}`

- [ ] 是否已创建 `application.yml.example` 模板文件？

### 2. 代码检查

- [ ] 代码中是否有硬编码的密码、密钥或Token？
  ```bash
  # 搜索可能的敏感信息
  grep -r "password" src/ --exclude-dir=target
  grep -r "secret" src/ --exclude-dir=target
  grep -r "api[_-]key" src/ --exclude-dir=target
  ```

- [ ] 是否有测试数据包含真实信息？
  - 检查测试文件中的示例数据

### 3. 文件检查

- [ ] `.gitignore` 是否已正确配置？
  - 确保排除 `target/`、`node_modules/`、`.idea/` 等

- [ ] 是否排除了包含敏感信息的配置文件？
  - 如 `application-local.yml`、`application-prod.yml`

- [ ] 是否有临时文件或备份文件？
  - 检查 `.bak`、`.tmp` 等文件

## 📝 文档检查

- [ ] **README.md** 是否完整？
  - 项目介绍
  - 安装说明
  - 使用指南
  - 配置说明

- [ ] **GITHUB_PUBLISH.md** 是否已创建？
  - 发布指南文档

- [ ] 文档中是否包含敏感信息？
  - 检查文档中的示例配置

## 🔧 项目结构检查

- [ ] 项目结构是否清晰？
- [ ] 是否有不必要的文件？
- [ ] 依赖文件是否完整？
  - `pom.xml`（后端）
  - `package.json`（前端）

## 🚀 发布前最后步骤

### 1. 处理敏感配置

**选项A：使用环境变量（推荐）**

修改 `application.yml`：
```yaml
spring:
  datasource:
    password: ${DB_PASSWORD:default_password}
    
jwt:
  secret: ${JWT_SECRET:change-me-to-a-long-random-secret}

aliyun:
  oss:
    access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_OSS_ACCESS_KEY_SECRET:}
```

**选项B：创建本地配置文件**

1. 将 `application.yml` 添加到 `.gitignore`
2. 创建 `application.yml.example` 作为模板
3. 在 README 中说明如何复制模板文件

### 2. 验证 Git 状态

```bash
# 查看将要提交的文件
git status

# 查看文件差异
git diff

# 确认没有敏感信息
git diff --cached
```

### 3. 测试构建

```bash
# 后端构建测试
mvn clean package -DskipTests

# 前端构建测试
cd web
npm install
npm run build
```

## ⚠️ 如果已经提交了敏感信息

如果发现已经提交了敏感信息到 GitHub：

1. **立即修改**
   ```bash
   # 修改敏感信息
   # 提交更改
   git add .
   git commit -m "fix: 移除敏感信息"
   git push
   ```

2. **从历史中移除（高级）**
   - 使用 `git filter-branch` 或 `git filter-repo`
   - 或联系 GitHub 支持

3. **更换密钥**
   - 如果密钥已泄露，立即更换所有相关密钥

## 📋 快速命令检查

```bash
# 1. 检查 Git 状态
git status

# 2. 查看将要提交的文件列表
git ls-files

# 3. 搜索可能的敏感信息
grep -r "1234\|password\|secret" src/ --exclude-dir=target | grep -v ".class"

# 4. 检查配置文件
cat src/main/resources/application.yml | grep -E "password|secret|key"

# 5. 验证 .gitignore
git check-ignore -v application.yml
```

## ✅ 完成检查后

确认所有检查项都已完成，然后：

1. 按照 `GITHUB_PUBLISH.md` 的步骤发布
2. 发布后再次检查 GitHub 上的文件
3. 如有问题，立即处理

---

**记住**：安全第一！宁可多检查一遍，也不要泄露敏感信息。
