plugins { `java-library` }

dependencies {
    api(project(":modules:dialogue"))
    api(project(":modules:scenario"))
    api(project(":modules:template"))
    implementation(project(":modules:llm"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
