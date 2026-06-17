plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.akj"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
    maven { url = uri("https://repo.spring.io/snapshot") }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-json")

    // Spring AI — DeepSeek native adapter
    implementation("org.springframework.ai:spring-ai-starter-model-deepseek")

    // Jackson JDK8 module for Optional support in records
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.21.4")

    // Spring AI MCP Client — connects to GitHub MCP (stdio) and Context7 MCP (SSE)
    implementation("org.springframework.ai:spring-ai-starter-mcp-client:2.0.0")

    // GitHub REST API client
    implementation("org.kohsuke:github-api:1.326")

    // Logging
    implementation("org.slf4j:slf4j-api")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}