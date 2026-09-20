plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Чистая JVM-библиотека: подходит и Android-игре, и настольному инструменту.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
