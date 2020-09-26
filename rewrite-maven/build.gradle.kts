dependencies {
    api(project(":rewrite-xml"))

    api("com.fasterxml.jackson.core:jackson-annotations:latest.release")
    implementation("com.fasterxml.jackson.core:jackson-databind:latest.release")

    implementation("org.mapdb:mapdb:latest.release")
    implementation("io.micrometer:micrometer-core:latest.release")

    implementation("org.eclipse.aether:aether-api:latest.release")
    implementation("org.eclipse.aether:aether-spi:latest.release")
    implementation("org.eclipse.aether:aether-util:latest.release")
    implementation("org.eclipse.aether:aether-connector-basic:latest.release")
    implementation("org.eclipse.aether:aether-transport-file:latest.release")
    implementation("org.eclipse.aether:aether-transport-http:latest.release")
    implementation("org.apache.maven:maven-aether-provider:latest.release")
    implementation("org.apache.maven:maven-core:latest.release")
    implementation("org.apache.maven:maven-settings-builder:latest.release")

    implementation("io.micrometer.prometheus:prometheus-rsocket-client:latest.release")
    implementation("io.rsocket:rsocket-transport-netty:1.0.2")

    testImplementation(project(":rewrite-test"))
}
