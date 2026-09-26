# MyBatisProviderJump

为 MyBatis 注解式 Mapper 提供 gutter 导航图标（类似 MyBatisX 在 XML Mapper 场景的小鸟图标，但面向注解方式）。

作者：Gavin Yang（miemiegyy@gmail.com）

- **正向**：Mapper 接口方法上的 `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` 注解（`type = X.class, method = "m"`）→ 方法名左侧出现 **Forward** 图标，点击跳转到 `X` 类的 `m` 方法。
- **反向**：Provider 类的 SQL 构建方法名左侧出现 **Back** 图标，点击跳回引用它的 Mapper 方法（多个引用点时弹出列表选择）。

**English version**: [README_EN.md](README_EN.md)

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Kotlin |
| Gradle | 8.14（Kotlin DSL） |
| intellij-platform-gradle-plugin | 2.5.0 |
| 目标平台 | IntelliJ IDEA 2025.2.4（build 252） |
| 构建 JDK | 21（IDEA 2025 平台 jar 为 Java 21 字节码；`jvmTarget` 仍为 17） |
| 兼容区间 | sinceBuild `251` 起、不设 untilBuild 上限（IDEA 2025.1 及以上，含未来版本） |

## 国内镜像

- Gradle 安装包：`mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`
- Gradle 插件解析：`maven.aliyun.com/repository/gradle-plugin`
- Maven 依赖：`maven.aliyun.com/repository/public`

## 构建与运行

```bash
# 1. 生成/更新 wrapper（已配置好，通常无需执行）
gradle wrapper --gradle-version 8.14

# 2. 构建插件产物（build/distributions/MyBatisProviderJump-1.0.0.zip）
./gradlew buildPlugin

# 3. 启动沙箱 IDE 验证：打开任意含 @SelectProvider 的 Mapper，
#    方法名左侧出现 Forward 图标即成功
./gradlew runIde
```

## 实现要点

- `SelectProviderLineMarkerProvider`（正向）：在 `collectSlowLineMarkers` 中识别带 Provider 注解的 `PsiMethod`，从 `PsiClassObjectAccessExpression` 取 `type` 类、从 `PsiLiteralExpression` 取 `method` 字符串，`findMethodsByName` 后用 `NavigationGutterIconBuilder` + `AllIcons.Actions.Forward` 锚定到 `nameIdentifier`。
- `ProviderToMapperLineMarkerProvider`（反向）：在 Provider 方法上用 `ReferencesSearch.search(providerClass)` 反查 `type = X.class` 引用点，`PsiTreeUtil.getParentOfType` 向上找到注解与 Mapper 方法，匹配 `method` 字符串后挂 `AllIcons.Actions.Back` 图标（多目标自动弹出列表）。
- `method` 省略语义（与 MyBatis `ProviderSqlSource` 完全一致）：省略或为空串时，插件真实校验 Provider 是否实现 `ProviderMethodResolver`——实现则按 Mapper 方法同名匹配（同名约定），未实现则按 MyBatis 默认行为匹配 `provideSql`；正向反向一致。
- 同名重载精准匹配：正向按 Mapper 方法参数个数过滤同名 Provider 方法，唯一命中时点击直接跳转，多候选再弹列表。
- 反向按类批量反查：同一 Provider 类在一次收集中只做一次全项目 `ReferencesSearch`，再按生效方法名分发（而非逐方法搜索）。
- 图标统一 `Alignment.LEFT`；`plugin.xml` 通过 `codeInsight.lineMarkerProvider language="JAVA"` 注册两个 Provider。

## 已知限制

- `method` 按字符串名字匹配；同名重载按 Mapper 方法参数个数启发式过滤，不做完整签名/类型匹配。
- 仅支持 Java 文件（`language="JAVA"`）；Kotlin Mapper 不显示图标。
- 未做 `verifyPlugin` 二进制兼容性校验。
