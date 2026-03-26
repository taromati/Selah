plugins {
    java
    id("org.springframework.boot") version "4.0.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// 루트 프로젝트는 JAR 생성하지 않음
tasks.jar {
    enabled = false
}

allprojects {
    group = "me.taromati"
    version = "0.2.4"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        sourceCompatibility = JavaVersion.toVersion(25)
        targetCompatibility = JavaVersion.toVersion(25)
    }

    tasks.withType<JavaCompile> {
        options.release.set(25)
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok:1.18.42")
        "annotationProcessor"("org.projectlombok:lombok:1.18.42")
        "implementation"("com.github.f4b6a3:uuid-creator:6.1.0")
    }
}
