import org.jetbrains.intellij.platform.gradle.TestFrameworkType // 测试框架类型（Platform / Plugin.Java）
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21"                     // Kotlin 插件（IDEA 2025 平台 jar 用 Kotlin 2.2 编译，1.9 读不了其元数据）
    id("org.jetbrains.intellij.platform") version "2.5.0" // IntelliJ 平台插件 2.x（支持 IDEA 2025）
    java                                                // Java 支持（plugin.xml 依赖 java 模块）
}

group = "io.github.miemiegyy"          // 组织名（中性，无公司标识）
version = "1.3.0"                    // 插件版本（1.3.0：正式版；不设 untilBuild 上限，兼容未来 IDE）

repositories {
    intellijPlatform { defaultRepositories() } // IntelliJ 平台官方仓库（create() 方式下载 IDE 时用）
    maven("https://maven.aliyun.com/repository/public") // 阿里云镜像：Maven 公共依赖
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib")) // 编译期用 Kotlin 标准库；运行期由 IDE 平台提供（不打包进插件，也不上测试运行时 classpath）
    testCompileOnly(kotlin("stdlib")) // 测试代码同样只编译期需要
    intellijPlatform {
        // 插件开发 SDK：使用本机已安装的 IntelliJ IDEA 2025.2.4 (build 252)，零下载
        // 如需改为下载官方 ideaIC，换成：create("IC", "2025.2.4")
        local("/Applications/IntelliJ IDEA.app")
        bundledPlugin("com.intellij.java") // 依赖内置 Java 插件（PsiMethod / PSI 注解等）
        testFramework(TestFrameworkType.Platform) // 平台测试框架（BasePlatformTestCase）
        testFramework(TestFrameworkType.Plugin.Java) // Java 插件测试框架（Java 代码 fixture）
    }
    // 测试用真实 MyBatis 注解（@SelectProvider 的解析、ProviderMethodResolver 接口检测都依赖它）
    testImplementation("org.mybatis:mybatis:3.5.14") // 与 demo 工程同版本
    testImplementation("junit:junit:4.13.2") // JUnit4（平台测试基类基于 JUnit3/4 风格）
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("251")   // 兼容从 2025.1 开始
            // 不设 untilBuild 属性：官方认可的“兼容所有未来 IDE 版本”写法（Marketplace 校验禁止 999.* 魔法值）
        }
        // 发布到 JetBrains Marketplace 的版本变更说明（会打进 plugin.xml 的 change-notes）
        changeNotes.set(
            """
            <b>1.3.0</b><br/>
            - Open compatibility upper bound: IDEA 2025.1+ (no until-build upper bound)<br/>
            - Fix bare <code>@SelectProvider(X.class)</code> short syntax (value attribute) in both directions<br/>
            - Same-name convention: verify <code>ProviderMethodResolver</code>; fall back to MyBatis default <code>provideSql</code><br/>
            - Reverse lookup batched per provider class (one ReferencesSearch per class per pass)<br/>
            - Overloaded provider methods matched by mapper parameter count<br/>
            - Rename package/group to io.github.miemiegyy; kotlin-stdlib no longer bundled (14KB zip)<br/>
            - Add 8 automated headless gutter tests
            """.trimIndent(),
        )
    }
}

tasks {
    wrapper {
        gradleVersion = "8.14" // Gradle 版本（intellij-platform 2.x 要求 8.10+）
        // 腾讯云镜像：Gradle 安装包
        distributionUrl = "https://mirrors.cloud.tencent.com/gradle/gradle-8.14-bin.zip"
    }

    withType<JavaCompile> {
        sourceCompatibility = "17" // Java 编译目标 17
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    // 本插件无可配置选项，跳过可搜索选项索引（本地 Ultimate 无头运行该任务会失败）
    buildSearchableOptions {
        enabled = false
    }

    // 平台测试：无头运行，堆内存放宽（fixture 项目较重）
    test {
        maxHeapSize = "1g"
        systemProperty("java.awt.headless", "true") // 无头环境
    }

    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17" // Kotlin 编译目标 17
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }
}
