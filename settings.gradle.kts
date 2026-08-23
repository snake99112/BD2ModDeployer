pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // <-- 关键：加上这一行，Shizuku依赖就靠它下载
    }
}

rootProject.name = "BD2ModDeployer"
include(":app")
