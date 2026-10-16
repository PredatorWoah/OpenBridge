plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.register<JavaExec>("protocolTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.openbridge.protocol.ProtocolTest")
    enableAssertions = true
}
tasks.check { dependsOn("protocolTest") }
