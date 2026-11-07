plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // PlaceholderAPI 的仓库。拉不到时可以把 jar 丢进 libs/ 改成 compileOnly(files("libs/..."))
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
    compileOnly("org.java-websocket:Java-WebSocket:1.6.0")
    compileOnly("com.google.code.gson:gson:2.14.0")
    // 只用来注册占位符；服务器上没装 PAPI 时插件照常加载（softdepend）
    compileOnly("me.clip:placeholderapi:2.12.3")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
