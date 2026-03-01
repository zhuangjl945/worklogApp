# GitHub 发布指南

本文档将指导您如何将工作日志管理系统项目发布到 GitHub。

## 📋 目录

- [准备工作](#准备工作)
- [创建 GitHub 仓库](#创建-github-仓库)
- [初始化本地 Git 仓库](#初始化本地-git-仓库)
- [配置 Git 用户信息](#配置-git-用户信息)
- [添加文件并提交](#添加文件并提交)
- [推送到 GitHub](#推送到-github)
- [设置仓库信息](#设置仓库信息)
- [后续维护](#后续维护)
- [常见问题](#常见问题)

## 🚀 准备工作

### 1. 检查项目文件

确保以下重要文件已准备好：

- ✅ `README.md` - 项目说明文档
- ✅ `.gitignore` - Git 忽略文件配置
- ✅ 源代码文件完整
- ✅ 配置文件已处理敏感信息（如密码、密钥等）

### 2. 处理敏感信息

**重要**：在提交到 GitHub 之前，请确保：

1. **移除敏感配置**
   - 检查 `application.yml` 中的数据库密码
   - 检查 JWT 密钥（生产环境密钥）
   - 检查阿里云 OSS 的 AccessKey 和 SecretKey

2. **使用环境变量或配置文件模板**
   - 可以创建 `application.yml.example` 作为模板
   - 在 README 中说明如何配置环境变量

3. **检查是否有硬编码的敏感信息**
   ```bash
   # 搜索可能的敏感信息
   grep -r "password" src/
   grep -r "secret" src/
   grep -r "key" src/
   ```

### 3. 安装 Git

如果还没有安装 Git，请先安装：

- **Windows**: 下载 [Git for Windows](https://git-scm.com/download/win)
- **macOS**: `brew install git` 或从官网下载
- **Linux**: `sudo apt-get install git` (Ubuntu/Debian) 或 `sudo yum install git` (CentOS/RHEL)

验证安装：
```bash
git --version
```

## 📦 创建 GitHub 仓库

### 方法一：通过 GitHub 网站创建（推荐）

1. **登录 GitHub**
   - 访问 [https://github.com](https://github.com)
   - 登录您的账户（如果没有账户，请先注册）

2. **创建新仓库**
   - 点击右上角的 "+" 号
   - 选择 "New repository"

3. **填写仓库信息**
   - **Repository name**: `worklog` 或您喜欢的名称
   - **Description**: 工作日志管理系统
   - **Visibility**: 
     - Public（公开）- 任何人都可以查看
     - Private（私有）- 只有您和授权的人可以查看
   - **不要**勾选 "Initialize this repository with a README"（因为本地已有文件）
   - **不要**添加 .gitignore 或 license（本地已有）

4. **点击 "Create repository"**

5. **记录仓库地址**
   - 创建后会显示仓库地址，格式如：`https://github.com/your-username/worklog.git`
   - 或者 SSH 地址：`git@github.com:your-username/worklog.git`

### 方法二：通过 GitHub CLI 创建

如果您安装了 GitHub CLI：

```bash
gh repo create worklog --public --description "工作日志管理系统"
```

## 🔧 初始化本地 Git 仓库

### 1. 打开终端/命令行

在项目根目录（`worklog` 文件夹）打开终端。

### 2. 初始化 Git 仓库

```bash
# 进入项目目录（如果还没有）
cd D:\Cursors\worklog

# 初始化 Git 仓库
git init
```

### 3. 检查 Git 状态

```bash
git status
```

您应该看到所有未跟踪的文件列表。

## ⚙️ 配置 Git 用户信息

如果是第一次使用 Git，需要配置用户信息：

```bash
# 配置用户名（使用您的 GitHub 用户名或真实姓名）
git config --global user.name "Your Name"

# 配置邮箱（使用您的 GitHub 邮箱）
git config --global user.email "your.email@example.com"
```

验证配置：
```bash
git config --global user.name
git config --global user.email
```

**注意**：如果只想为当前项目配置，去掉 `--global` 参数：
```bash
git config user.name "Your Name"
git config user.email "your.email@example.com"
```

## 📝 添加文件并提交

### 1. 添加文件到暂存区

```bash
# 添加所有文件（.gitignore 会自动排除不需要的文件）
git add .

# 或者分步添加
git add README.md
git add .gitignore
git add src/
git add web/
git add pom.xml
```

### 2. 检查暂存的文件

```bash
git status
```

确认要提交的文件列表，确保没有敏感信息。

### 3. 创建首次提交

```bash
git commit -m "Initial commit: 工作日志管理系统

- 添加完整的项目代码
- 添加使用说明文档
- 配置 Git 忽略文件"
```

**提交信息规范**：
- 第一行：简短描述（50字符以内）
- 空行
- 详细说明（可选）

### 4. 查看提交历史

```bash
git log
```

## 🚀 推送到 GitHub

### 1. 添加远程仓库

```bash
# 使用 HTTPS（推荐新手）
git remote add origin https://github.com/your-username/worklog.git

# 或使用 SSH（需要配置 SSH 密钥）
git remote add origin git@github.com:your-username/worklog.git
```

验证远程仓库：
```bash
git remote -v
```

### 2. 重命名主分支（如果需要）

GitHub 默认使用 `main` 作为主分支，如果本地是 `master`：

```bash
# 重命名分支
git branch -M main
```

### 3. 推送到 GitHub

```bash
# 首次推送
git push -u origin main

# 如果使用 master 分支
git push -u origin master
```

**如果遇到认证问题**：

#### 使用 HTTPS（需要 Personal Access Token）

1. 生成 Personal Access Token：
   - GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
   - Generate new token (classic)
   - 选择权限：`repo`（完整仓库访问权限）
   - 复制生成的 token

2. 推送时使用 token 作为密码：
   ```bash
   # 用户名：您的 GitHub 用户名
   # 密码：刚才生成的 Personal Access Token
   ```

#### 使用 SSH（推荐）

1. 生成 SSH 密钥：
   ```bash
   ssh-keygen -t ed25519 -C "your.email@example.com"
   ```

2. 复制公钥：
   ```bash
   # Windows
   type %USERPROFILE%\.ssh\id_ed25519.pub
   
   # macOS/Linux
   cat ~/.ssh/id_ed25519.pub
   ```

3. 添加到 GitHub：
   - GitHub → Settings → SSH and GPG keys → New SSH key
   - 粘贴公钥内容

4. 测试连接：
   ```bash
   ssh -T git@github.com
   ```

### 4. 验证推送结果

访问您的 GitHub 仓库页面，应该能看到所有文件已经上传。

## 🎨 设置仓库信息

### 1. 添加仓库描述和标签

在 GitHub 仓库页面：
- 点击 "⚙️ Settings" 或直接编辑描述
- 添加 Topics（标签）：如 `spring-boot`, `vue3`, `worklog`, `management-system`

### 2. 设置默认分支

- Settings → Branches → Default branch
- 确保是 `main` 或 `master`

### 3. 添加 README 徽章（可选）

在 README.md 顶部可以添加一些徽章，例如：

```markdown
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.10-brightgreen)
![Vue](https://img.shields.io/badge/Vue-3.5.24-4FC08D)
![License](https://img.shields.io/badge/License-MIT-blue)
```

### 4. 添加许可证文件（可选）

如果需要添加许可证：

```bash
# 创建 LICENSE 文件
# 或通过 GitHub 网页添加：Settings → General → License
```

## 🔄 后续维护

### 日常更新流程

```bash
# 1. 查看修改状态
git status

# 2. 添加修改的文件
git add .

# 或添加特定文件
git add README.md
git add src/main/java/com/zjl/worklog/...

# 3. 提交更改
git commit -m "feat: 添加新功能描述"

# 4. 推送到 GitHub
git push
```

### 提交信息规范（推荐）

使用约定式提交格式：

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
git commit -m "docs: 更新API文档"
```

### 创建分支（可选）

对于新功能开发，建议创建分支：

```bash
# 创建并切换到新分支
git checkout -b feature/new-feature

# 或使用新语法
git switch -c feature/new-feature

# 开发完成后合并
git checkout main
git merge feature/new-feature
git push
```

### 查看提交历史

```bash
# 简洁视图
git log --oneline

# 图形化视图
git log --graph --oneline --all

# 查看文件变更
git log --stat
```

## ❓ 常见问题

### Q1: 推送时提示 "remote: Support for password authentication was removed"

**原因**：GitHub 已不再支持密码认证。

**解决方案**：
- 使用 Personal Access Token（HTTPS）
- 或配置 SSH 密钥（推荐）

### Q2: 如何更新远程仓库地址？

```bash
# 查看当前远程地址
git remote -v

# 修改远程地址
git remote set-url origin https://github.com/new-username/new-repo.git

# 或删除后重新添加
git remote remove origin
git remote add origin https://github.com/new-username/new-repo.git
```

### Q3: 误提交了敏感信息怎么办？

如果已经推送到 GitHub：

1. **立即修改敏感信息**
   ```bash
   # 修改文件中的敏感信息
   # 然后提交
   git add .
   git commit -m "fix: 移除敏感信息"
   git push
   ```

2. **从历史记录中移除（高级）**
   - 使用 `git filter-branch` 或 `git filter-repo`
   - 或联系 GitHub 支持

3. **最佳实践**：在推送前检查，使用 `.gitignore` 排除敏感文件

### Q4: 如何撤销最后一次提交？

```bash
# 撤销提交但保留更改
git reset --soft HEAD~1

# 撤销提交并丢弃更改（谨慎使用）
git reset --hard HEAD~1
```

### Q5: 如何同步远程更改？

```bash
# 拉取远程更改
git pull origin main

# 如果有冲突，解决后：
git add .
git commit -m "merge: 解决冲突"
git push
```

### Q6: 如何查看文件差异？

```bash
# 查看工作区与暂存区的差异
git diff

# 查看暂存区与最后一次提交的差异
git diff --staged

# 查看两次提交之间的差异
git diff commit1 commit2
```

### Q7: 如何忽略已跟踪的文件？

如果文件已经被 Git 跟踪，需要先移除：

```bash
# 从 Git 中移除但保留本地文件
git rm --cached filename

# 添加到 .gitignore
echo "filename" >> .gitignore

# 提交更改
git add .gitignore
git commit -m "chore: 忽略文件"
```

## 📚 有用的 Git 命令速查

```bash
# 基本操作
git init                    # 初始化仓库
git status                  # 查看状态
git add .                   # 添加所有文件
git commit -m "message"     # 提交
git push                    # 推送
git pull                    # 拉取

# 分支操作
git branch                  # 查看分支
git branch new-branch       # 创建分支
git checkout branch-name    # 切换分支
git merge branch-name       # 合并分支

# 查看信息
git log                     # 查看提交历史
git log --oneline           # 简洁历史
git diff                    # 查看差异
git show                    # 查看最后一次提交

# 远程操作
git remote -v               # 查看远程仓库
git remote add origin url   # 添加远程仓库
git push -u origin main     # 首次推送
```

## 🎯 下一步

发布成功后，您可以：

1. **分享仓库链接**：让团队成员访问
2. **设置 Issues**：用于问题跟踪和功能请求
3. **创建 Releases**：标记版本发布
4. **设置 GitHub Pages**：如果需要展示项目网站
5. **添加 CI/CD**：自动化构建和部署（可选）

## 📞 获取帮助

- **Git 官方文档**: https://git-scm.com/doc
- **GitHub 帮助**: https://docs.github.com
- **Git 教程**: https://learngitbranching.js.org

---

**祝您发布顺利！** 🎉

如有问题，请查看 [常见问题](#常见问题) 部分或提交 Issue。
