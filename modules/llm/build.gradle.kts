plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    // AnthropicLlmGateway 의 생성자가 WebClient 를 받으므로 api 로 노출한다.
    api("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
}
