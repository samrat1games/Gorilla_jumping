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
    }
}

rootProject.name = "GorillaJumping"

include(":app")
// SDK PhoneXR подключаем модулем :sdk (так его называют docs/README.txt): отдельная папка
// phonexr-sdk-1.0.1, если она есть, иначе SDK из MobileXR_Orange.
include(":sdk")
project(":sdk").projectDir = file("phonexr-sdk-1.0.1").takeIf { it.isDirectory } ?: file("MobileXR_Orange/sdk")
