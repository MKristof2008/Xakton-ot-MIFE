pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("app/libs") }
    }
}
rootProject.name = "NeuroStenograf"
include(":app")