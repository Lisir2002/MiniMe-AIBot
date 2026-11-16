plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

// 云端常驻 Agent 服务：纯 JVM，复用 :agent-core 的协议层与领域模型。
// 提供 HTTP API 供 App 提交提问、增量拉取事件、回传审批；内部跑 agent loop，事件落 SQLite。

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(project(":agent-core"))

    // Ktor server（Netty 引擎）
    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")

    // 日志实现（slf4j）
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // SQLite JDBC（事件/会话/任务持久化）
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // 若 core 的 compileOnly javax.inject 在此需要（Adapter 构造无 @Inject 运行时依赖，通常不需要）
    runtimeOnly("javax.inject:javax.inject:1")
}

application {
    mainClass.set("com.minime.agent.server.MainKt")
}

// 打可执行 fat jar，便于 scp 到用户服务器后 java -jar 直接运行
tasks.jar {
    manifest { attributes["Main-Class"] = "com.minime.agent.server.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })
    archiveFileName.set("agent-server.jar")
}
