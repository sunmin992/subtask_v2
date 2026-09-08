plugins { `java-library` }

dependencies {
    api(project(":modules:core-ses"))
    api("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework:spring-context")
}
