# Cromite-Patcher

基于 GitHub Actions 自动注入代理组件并重打包的 Cromite 浏览器。

## 注入说明
本项目通过注入 `ProxyDocumentsProvider`，将 Cromite 内部私有目录（`/data/data/org.cromite.cromite/files/proxy`）映射为系统级 **DocumentsProvider**。

此技术允许在无需 Root 权限的情况下，通过文件管理器直接读写应用的私有配置文件及运行日志。

## 使用方法 (以 MT 管理器为例)

1. **安装 APK**：安装并运行一次本项目构建的 Cromite（运行后会初始化工作目录并启动 `libproxy.so`）。
2. **侧边栏连接**：
   * 打开 MT 管理器，点击左侧菜单 -> **更多操作**（垂直三点图标）。
   * 选择 **添加本地存储**。
   * 在弹出的系统文件选择器中，点击左上角菜单，选择 **Cromite**。
   * 进入目录后点击底部的 **使用此文件夹** 并点击 **允许**。
3. **管理配置**：
   * 授权后，你可以在 MT 管理器中直接看到 `proxy` 目录。
   * 你可以自由创建、编辑 `config.toml` 或查看运行日志，修改会即时生效（需重启应用以使 `libproxy.so` 重新加载配置）。

## 浏览器代理设置

你需要手动告知浏览器使用该代理：

1. 在 Cromite 地址栏输入：`chrome://proxy`
2. 选中 **Use a single proxy list for all schemes**
3. 在输入框中填入你的本地 HTTP 代理地址，例如： `PROXY 127.0.0.1:8080`
4. 点击 **Apply** 生效。

## 技术实现
* **核心注入**：代码位于 `patch/ProxyDocumentsProvider.java`，实现了 `DocumentsProvider` 接口。
* **执行逻辑**：应用启动时，`attachInfo` 自动调用 `libproxy.so`，并以 `files/proxy` 作为工作目录。
* **自动化流**：利用 `apktool` 修改 `AndroidManifest.xml` 注册 Provider，并通过 `zip` 增量更新技术合并 DEX 与 SO 文件，保持原版 APK 结构并进行 V3 签名。

## 自行构建
1. Fork 本仓库。
2. 在 Settings -> Secrets -> Actions 中配置：
   * `KEYSTORE_BASE64`: 签名密钥库 Base64。
   * `KEY_ALIAS`: 密钥别名。
   * `KEYSTORE_PASSWORD`: 密钥库密码。
   * `KEY_PASSWORD`: 密钥密码。
3. 触发 Workflow 即可在 Release 页面获得成品。
