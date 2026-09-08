plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    api(project(":modules:template"))
    api(project(":modules:dialogue"))
    api(project(":modules:scenario"))
    implementation(project(":modules:llm"))

    api("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}
