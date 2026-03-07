# 本机开发环境搭建指南

## 前置说明

本指南用于在 Windows 本机安装所有依赖环境，以便在本机运行后端服务并与前端（`http://localhost:5173`）联调。

**所需环境：**
- JDK 17
- Maven 3.9
- MySQL 8.0
- Redis（使用 Memurai，Redis 的 Windows 原生兼容版）

---

## 第一步：以管理员身份打开 PowerShell

右键点击「开始菜单」→「Windows PowerShell」→「以管理员身份运行」

---

## 第二步：安装 JDK 17

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```

安装完成后验证（需重新打开终端）：
```powershell
java -version
# 期望输出: openjdk version "17.x.x"
```

---

## 第三步：安装 Maven

```powershell
winget install Apache.Maven
```

验证：
```powershell
mvn -version
# 期望输出: Apache Maven 3.x.x
```

> **提示：** 如果 winget 中找不到 Maven，可手动下载：
> https://maven.apache.org/download.cgi
> 下载 zip 包，解压到 `C:\maven`，将 `C:\maven\bin` 添加到系统环境变量 `PATH`。

---

## 第四步：安装 MySQL 8.0

```powershell
winget install Oracle.MySQL
```

安装过程中会提示设置 root 密码，**请设置为 `root123`**（与项目配置一致）。

安装完成后启动服务：
```powershell
net start MySQL80
```

验证：
```powershell
mysql -u root -proot123 -e "SELECT VERSION();"
```

---

## 第五步：安装 Redis（Memurai）

Memurai 是 Redis 的 Windows 原生版本，完全兼容 Redis 协议。

```powershell
winget install Memurai.Memurai
```

启动服务：
```powershell
net start Memurai
```

验证：
```powershell
memurai-cli ping
# 期望输出: PONG
```

---

## 第六步：初始化数据库

MySQL 安装并启动后，执行初始化 SQL：

```powershell
# 进入项目根目录
cd d:\wangzhe\CodeBuddy\fileWork_backend

# 执行初始化脚本
mysql -u root -proot123 < src\main\resources\db\init.sql
```

> 该脚本包含 `CREATE DATABASE IF NOT EXISTS`，幂等安全，可重复执行。

初始化完成后会创建：
- 数据库：`file_proc_db`
- 超管账号：`superadmin` / 密码：`admin@123`
- 演示租户用户：`admin` / 密码：`test@123`

---

## 第七步：启动后端

所有环境就绪后，运行一键启动脚本：

```powershell
cd d:\wangzhe\CodeBuddy\fileWork_backend
.\start-dev.ps1
```

或手动启动：
```powershell
cd d:\wangzhe\CodeBuddy\fileWork_backend
mvn spring-boot:run
```

后端启动成功后访问：
- **接口地址：** http://localhost:8080/api
- **接口文档：** http://localhost:8080/api/doc.html

---

## 常见问题

### Q: winget 命令不存在？
**A:** winget 需要 Windows 10 1809+ 或 Windows 11。如未安装，在微软商店搜索「应用安装程序」进行安装。

### Q: MySQL root 密码不是 root123 怎么办？
**A:** 修改 `src/main/resources/application-dev.yml` 中的 `spring.datasource.password` 为你的实际密码。

### Q: 端口 3306 已被占用？
**A:** 检查是否已有 MySQL 实例运行：
```powershell
netstat -ano | findstr :3306
```

### Q: Maven 下载依赖很慢？
**A:** 配置阿里云镜像。在 `~\.m2\settings.xml` 中添加：
```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/central</url>
</mirror>
```

### Q: 如何停止后端？
**A:** 在启动后端的终端窗口按 `Ctrl+C`。

---

## 账号信息汇总

| 账号类型 | 用户名 | 密码 |
|---------|--------|------|
| MySQL root | root | root123 |
| 超级管理员 | superadmin | admin@123 |
| 租户管理员 | admin | test@123 |
| 普通用户 | operator | test@123 |
