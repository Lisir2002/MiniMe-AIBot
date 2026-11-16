plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// 纯 Kotlin/JVM 模块：App 与云端 agent-server 共享 Agent 领域模型与 LLM 协议适配层。
// 不依赖任何 Android / Compose / Hilt / Room —— 这是能被 JVM 服务端复用的前提。
// 源码包名沿用 com.minime.code.* ，避免搬迁时改动 app 模块海量 import。

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    // Retrofit + Gson + OkHttp：与 app 模块同版本，LLM HTTP 协议层需要
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-gson:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // 协程与序列化
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // @Inject 注解：保留源码原样，JVM 端与 Hilt 端都能编译
    compileOnly("javax.inject:javax.inject:1")
}
