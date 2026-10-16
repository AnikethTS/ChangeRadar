plugins {
  application
}

application {
  applicationName = "changeradar"
  mainClass.set("io.github.anikethts.changeradar.cli.ChangeRadarApp")
}

dependencies {
  implementation(project(":changeradar-core"))
  implementation(project(":changeradar-git"))
  implementation(project(":changeradar-analyzer-java"))
  implementation(project(":changeradar-analyzer-typescript"))
  implementation(libs.picocli)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.datatype.jsr310)
  testImplementation(libs.jgit)
}

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
}

tasks.processResources {
  from(rootProject.layout.projectDirectory.file("VERSION"))
}
