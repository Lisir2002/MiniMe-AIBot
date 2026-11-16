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

    // 单元测试：决策持久化 / 待决策查询 / 终态清理
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.minime.agent.server.MainKt")
}

// 版本号由发布 tag 派生（与 App 一致）：CI 打 v1.0.0-rc10 tag 时 GITHUB_REF_NAME=v1.0.0-rc10 → 1.0.0-rc10。
// 本地/CI 未设环境变量时退化为 dev（Java 进程仍可跑，App 会显示"开发版"）。
// 生成到 resources，App 通过 /version 读取，支撑"当前/期望版本"对比与升级提示。
val serverVersionProvider = providers.environmentVariable("GITHUB_REF_NAME")
    .orElse(providers.gradleProperty("agentServerVersion"))
    .map { it.removePrefix("v") }
    .orElse("dev")

// 用 Gradle 内置 WriteProperties（原生支持 configuration cache，避免自定义 doLast 捕获脚本实例）。
val generateVersionResource by tasks.registering(WriteProperties::class) {
    property("version", serverVersionProvider)
    outputFile = layout.buildDirectory.file("generated/version-resources/agent-server-version.properties").get().asFile
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/version-resources"))
    }
}

tasks.named("processResources") {
    dependsOn(generateVersionResource)
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
