import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "org.agentworkbench"
version = "0.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val ideaPath = providers.gradleProperty("ideaPath").map(::file)
val rebasedPath = providers.gradleProperty("rebasedPath").map(::file)
val smokeRoot = providers.gradleProperty("smokeRoot").orNull
// 本机装了 IDEA/Rebased 时优先 local 构建；路径不存在（其他开发机、CI）时回退到可移植声明。
val localIdea = ideaPath.orNull?.takeIf { it.exists() }
val localRebased = rebasedPath.orNull?.takeIf { it.exists() }
val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2025.2")

dependencies {
    intellijPlatform {
        if (localIdea != null) local(localIdea) else intellijIdeaCommunity(platformVersion)
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        jvmTarget.set(JvmTarget.JVM_21)
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("org.agentworkbench.workbench")
        name.set("Agent Workbench")
        version.set(project.version.toString())
        description.set("Agent Workbench provides a local read-only workspace and multi-repository Git workbench. 在 IntelliJ Platform 中查看工作区、需求和原生 Git 入口。")
        ideaVersion {
            sinceBuild.set("252")
            untilBuild.set("262.*")
        }
    }
    pluginVerification {
        ides {
            if (localIdea != null) local(localIdea)
            if (localRebased != null) local(localRebased)
            if (localIdea == null && localRebased == null) recommended()
        }
    }
}

tasks {
    withType<Test>().configureEach {
        systemProperty("workbench.integrationRoot", providers.gradleProperty("integrationRoot").orElse("").get())
        systemProperty("workbench.integrationEntry", providers.gradleProperty("integrationEntry").orElse("").get())
        if (!providers.gradleProperty("integrationRoot").isPresent) {
            exclude("**/CrmReadOnlyIntegrationTest.class")
        }
    }
    withType<JavaCompile>().configureEach {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    named<RunIdeTask>("runIde") {
        sandboxDirectory.set(layout.buildDirectory.dir("idea-sandbox"))
        if (smokeRoot != null) args(smokeRoot)
    }
}

intellijPlatformTesting {
    if (localRebased != null) {
        runIde.register("runRebased") {
            localPath.set(layout.dir(rebasedPath))
            sandboxDirectory.set(layout.buildDirectory.dir("rebased-sandbox"))
            task {
                group = "intellij platform"
                description = "在 Rebased 隔离沙箱中运行插件。"
                if (smokeRoot != null) args(smokeRoot)
            }
        }
        testIde.register("testRebased") {
            localPath.set(layout.dir(rebasedPath))
            sandboxDirectory.set(layout.buildDirectory.dir("rebased-test-sandbox"))
            testFramework(TestFrameworkType.Platform)
            task {
                group = "verification"
                description = "使用 Rebased 运行平台测试。"
            }
        }
    }
}
