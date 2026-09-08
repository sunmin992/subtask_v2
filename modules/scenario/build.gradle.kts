plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    api(project(":modules:core-devs"))
    api(project(":modules:template"))
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
