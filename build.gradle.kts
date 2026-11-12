plugins {
    java
}

group = "dev.ytstream"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API のみ(サーバー側が提供するため同梱しない)。外部ライブラリへの依存はゼロ。
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // 設定読み込みテスト(SettingsLoaderTest)で実物の YamlConfiguration を使うため、テスト時のみ参照
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") {
        expand("version" to v)
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
