plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "com.moxietech.family_tree"
version = "1.0.0"
application {
    mainClass = "com.moxietech.family_tree.ApplicationKt"
}

kotlin {
    compilerOptions {
        // Exposed 1.0's uuid() columns are typed kotlin.uuid.Uuid.
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

tasks.test {
    // Opt-in live check against a disposable Keycloak container (KeycloakBrowserTokenTest):
    // ./gradlew :server:test --tests "*KeycloakBrowserTokenTest" -PkeycloakIt=true
    systemProperty("family_tree.keycloakIt", providers.gradleProperty("keycloakIt").getOrElse("false"))
    // FT-008: the S3-compatible image used by the media round-trip tests (MediaTestStore).
    // Override with -PobjectStoreImage=<image:tag> to try another release.
    providers.gradleProperty("objectStoreImage").orNull?.let { systemProperty("family_tree.objectStoreImage", it) }
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javaTime)
    implementation(libs.exposed.dao)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.awssdk.s3)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}
