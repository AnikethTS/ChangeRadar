plugins {
  `java-library`
}

dependencies {
  api(project(":changeradar-core"))
  implementation(libs.javaparser.core)
}
