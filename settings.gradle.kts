pluginManagement {
    repositories { maven("https://maven.aliyun.com/repository/gradle-plugin")
                   maven("https://maven.aliyun.com/repository/google")
                   gradlePluginPortal(); google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { maven("https://maven.aliyun.com/repository/public")
                   maven("https://maven.aliyun.com/repository/google")
                   google(); mavenCentral() }
}
rootProject.name = "AssistantAnimV3"
include(":app")
