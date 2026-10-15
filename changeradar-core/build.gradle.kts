plugins {
  `java-library`
}

dependencies {
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
}

tasks.processResources {
  from(rootProject.layout.projectDirectory.file("VERSION"))
}
