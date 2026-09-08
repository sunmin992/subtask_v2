plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    api(project(":modules:template"))
    implementation(project(":modules:llm"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-expression")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
