import java.time.Duration

plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "org.hanbat.ses"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        systemProperty("file.encoding", "UTF-8")
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    // 태그 필터는 기본 test 태스크에만 건다. withType 으로 걸면 integrationTest 에도
    // 적용되어 포함과 제외가 충돌하고, 그때는 제외가 이겨서 아무것도 실행되지 않는다.
    tasks.named<Test>("test") {
        useJUnitPlatform {
            // 실제 LLM 호출과 Docker 가 필요한 테스트는 기본 실행에서 제외한다.
            excludeTags("live", "integration")
        }
    }

    // Testcontainers / 실 DB 통합 테스트 전용 태스크.
    // 소스셋은 람다 밖에서 잡아 둔다 — 람다 안의 수신자는 Task 라
    // the<SourceSetContainer>() 가 프로젝트가 아니라 태스크의 확장을 뒤진다.
    val testSourceSet = extensions.getByType<SourceSetContainer>()["test"]
    tasks.register<Test>("integrationTest") {
        description = "Testcontainers 기반 통합 테스트 (@Tag(\"integration\"))"
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("integration") }
        shouldRunAfter(tasks.named("test"))

        // Docker Engine 29 는 API 1.44 미만을 거부한다. Testcontainers 가 쓰는 docker-java 는
        // 기본값이 1.32 라, 그대로 두면 데몬이 멀쩡히 떠 있어도 첫 Info 호출이 400 으로 떨어지고
        // "Could not find a valid Docker environment" 라는, 원인과 한참 떨어진 메시지만 남는다.
        // 구형 데몬을 쓴다면 -PdockerApiVersion=1.32 로 내리면 된다.
        systemProperty("api.version",
            (project.findProperty("dockerApiVersion") as String?) ?: "1.44")
    }

    // 실제 LLM 에 붙는 테스트. 모델 응답이 비결정적이고 느려 기본 실행에서 제외한다.
    //   ./gradlew :modules:llm:liveTest
    tasks.register<Test>("liveTest") {
        description = "실제 LLM 호출 테스트 (@Tag(\"live\")). 로컬 Ollama 또는 API 키가 필요하다"
        group = "verification"
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("live") }
        // 로컬 추론은 모델 로딩까지 포함하면 분 단위로 걸린다.
        timeout.set(Duration.ofMinutes(15))
        outputs.upToDateWhen { false }

        // 모델을 바꿔 시험할 수 있게 한다:
        //   ./gradlew :modules:llm:liveTest -PollamaModel=gemma2:9b
        // 환경변수 대신 Gradle 프로퍼티를 쓴다 — 이미 떠 있는 데몬에는
        // 클라이언트 쪽 환경변수가 그대로 전달되지 않아 조용히 무시된다.
        (project.findProperty("ollamaModel") as String?)
            ?.let { systemProperty("ollama.model", it) }
        (project.findProperty("ollamaUrl") as String?)
            ?.let { systemProperty("ollama.url", it) }
    }
}
