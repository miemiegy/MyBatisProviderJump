pluginManagement {
    repositories {
        // 阿里云镜像：Gradle 插件
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // 阿里云镜像：Maven 公共依赖
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "MyBatisProviderJump"
