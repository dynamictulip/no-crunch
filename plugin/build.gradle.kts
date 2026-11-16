import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.nocrunch"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.environmentVariable("RIDER_PATH").orElse(System.getProperty("user.home") + "/Applications/Rider.app"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.nocrunch.rider"
        name = "No Crunch"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

// ---- Bundle the .NET engine inside the plugin ----------------------------------------------------------
val engineProject = rootDir.resolve("../engine/NoCrunch.Engine/NoCrunch.Engine.csproj")
val engineOut = layout.buildDirectory.dir("engine")

val publishEngine by tasks.registering(Exec::class) {
    group = "build"
    description = "dotnet publish the No Crunch engine"
    inputs.dir(rootDir.resolve("../engine/NoCrunch.Engine")).withPropertyName("engineSources")
        .optional()
    outputs.dir(engineOut)
    commandLine("dotnet", "publish", engineProject.absolutePath, "-c", "Release", "-o", engineOut.get().asFile.absolutePath, "-nologo", "-v:q")
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    dependsOn(publishEngine)
    from(engineOut) {
        into(pluginName.map { "$it/engine" })
        exclude("**/bin/**", "**/obj/**")
    }
}

tasks.test {
    useJUnitPlatform()
}
