// Spring 무의존 순수 Java 모듈. org.springframework import 금지.
plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("net.jqwik:jqwik:1.8.5")
}
