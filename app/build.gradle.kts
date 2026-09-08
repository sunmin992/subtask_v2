plugins {
    id("org.springframework.boot")
    application
}

dependencies {
    implementation(project(":modules:api"))
    implementation(project(":modules:persistence"))
    implementation(project(":modules:dialogue"))
    implementation(project(":modules:scenario"))
    implementation(project(":modules:llm"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

application {
    mainClass.set("org.hanbat.ses.app.SesScenarioServerApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("ses-scenario-server.jar")
}

// core-ses 만으로 도는 pruning CLI. LLM 도 Spring 도 DB 도 쓰지 않는다.
tasks.register<JavaExec>("pruneCli") {
    group = "application"
    description = "SES 파일과 답변 파일로 PES 를 만들어 본다 (Phase 1 확인용)"
    mainClass.set("org.hanbat.ses.core.cli.SesPruningCli")
    classpath = project(":modules:core-ses").sourceSets["main"].runtimeClasspath
    // 샘플 경로를 저장소 루트 기준으로 쓰기 위해 작업 디렉터리를 올린다.
    workingDir = rootProject.projectDir
    args = (findProperty("cliArgs") as String?
        ?: "app/src/main/resources/seed/resort.ses.json scripts/samples/answers-cablecar.json")
        .split(" ")
}
