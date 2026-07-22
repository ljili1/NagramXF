plugins {
    java
    kotlin("jvm")
}

dependencies {
    implementation("com.neovisionaries:nv-websocket-client:2.14")

}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}


kotlin {
    jvmToolchain(21)
}
