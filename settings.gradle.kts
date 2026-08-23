pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.rikka.app/") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.rikka.app/") }
    }
}

rootProject.name = "BD2ModDeployer"
include(":app")
