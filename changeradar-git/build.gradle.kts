plugins {
  `java-library`
}

dependencies {
    api(project(":changeradar-core"))
    implementation(libs.jgit)
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
}
