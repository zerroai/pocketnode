plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.test {
    useJUnitPlatform()
}
