plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// plain jar 비활성화
tasks.jar {
    enabled = false
}

// ── Vue 프론트엔드 빌드 ──
val webDir = file("web")

val npmCi = tasks.register<Exec>("npmCi") {
    workingDir = webDir
    commandLine("npm", "ci")
    inputs.file(webDir.resolve("package-lock.json"))
    outputs.dir(webDir.resolve("node_modules"))
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    dependsOn(npmCi)
    workingDir = webDir
    commandLine("npm", "run", "build")
    inputs.dir(webDir.resolve("src"))
    inputs.file(webDir.resolve("index.html"))
    inputs.file(webDir.resolve("vite.config.ts"))
    inputs.file(webDir.resolve("tsconfig.json"))
    outputs.dir(webDir.resolve("dist"))
}

val cleanFrontend = tasks.register<Delete>("cleanFrontend") {
    delete(layout.buildDirectory.dir("resources/main/static"))
}

val copyFrontend = tasks.register<Copy>("copyFrontend") {
    from(webDir.resolve("dist"))
    into(layout.buildDirectory.dir("resources/main/static"))
    dependsOn(cleanFrontend, npmBuild)
}

tasks.processResources {
    dependsOn(copyFrontend)
    filesMatching("application.yml") {
        filter { it.replace("@project.version@", rootProject.version.toString()) }
    }
}

// bootJar를 루트 build/libs에 selah-{version}.jar로 생성
tasks.bootJar {
    archiveBaseName.set("selah")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
}

// ── jlink 커스텀 런타임 ──
// jdeps 분석 결과 + TLS/네트워크 필수 모듈
val jlinkModules = listOf(
    // jdeps --ignore-missing-deps --print-module-deps 결과
    "java.base", "java.compiler", "java.desktop", "java.instrument",
    "java.management", "java.naming", "java.net.http", "java.prefs",
    "java.rmi", "java.scripting", "java.security.jgss", "java.sql",
    "jdk.jfr", "jdk.unsupported",
    // TLS/네트워크 (Discord, OpenAI API 통신에 필수)
    "jdk.crypto.ec", "jdk.crypto.cryptoki",
    "jdk.naming.dns", "jdk.net", "jdk.security.auth",
    // Spring XML, Thymeleaf
    "java.xml",
    // JTA (Hibernate)
    "java.transaction.xa",
    // ZIP 파일시스템 (Spring Boot JAR 로딩)
    "jdk.zipfs"
)

val jlinkPlatform: String by lazy {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch")
    val os = when {
        osName.contains("mac") -> "macos"
        osName.contains("linux") -> "linux"
        osName.contains("windows") -> "windows"
        else -> "unknown"
    }
    val arch = when (osArch) {
        "aarch64", "arm64" -> "aarch64"
        else -> "x64"
    }
    "$os-$arch"
}

val jlinkOutputDir = rootProject.layout.buildDirectory.dir("runtime/$jlinkPlatform")

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "distribution"
    description = "jlink로 현재 플랫폼용 커스텀 JRE 생성"

    val javaHome = System.getProperty("java.home")
    val outputDir = jlinkOutputDir.get().asFile

    doFirst {
        if (outputDir.exists()) outputDir.deleteRecursively()
    }

    commandLine(
        "$javaHome/bin/jlink",
        "--module-path", "$javaHome/jmods",
        "--add-modules", jlinkModules.joinToString(","),
        "--output", outputDir.absolutePath,
        "--strip-debug",
        "--compress", "zip-6",
        "--no-header-files",
        "--no-man-pages"
    )
}

val packageRuntime = tasks.register<Exec>("packageRuntime") {
    group = "distribution"
    description = "jlink 런타임을 아카이브로 패키징"
    dependsOn(jlinkRuntime)

    val archiveName = "selah-runtime-${rootProject.version}-$jlinkPlatform"
    val buildDir = rootProject.layout.buildDirectory.get().asFile

    doFirst {
        // 기존 아카이브 삭제
        buildDir.listFiles()?.filter { it.name.startsWith(archiveName) }?.forEach { it.delete() }
    }

    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        workingDir = rootProject.layout.buildDirectory.dir("runtime").get().asFile
        commandLine("powershell", "-Command",
            "Compress-Archive -Path '$jlinkPlatform' -DestinationPath '${buildDir.absolutePath}/$archiveName.zip'")
    } else {
        workingDir = rootProject.layout.buildDirectory.dir("runtime").get().asFile
        commandLine("tar", "czf",
            "${buildDir.absolutePath}/$archiveName.tar.gz",
            jlinkPlatform)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(project(":plugins:agent"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("net.dv8tion:JDA:6.3.1")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
