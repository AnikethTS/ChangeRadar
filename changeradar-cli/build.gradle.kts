plugins {
  application
  alias(libs.plugins.shadow)
}

application {
  applicationName = "changeradar"
  mainClass.set("io.github.anikethts.changeradar.cli.ChangeRadarApp")
}

dependencies {
  implementation(project(":changeradar-core"))
  implementation(project(":changeradar-git"))
  implementation(project(":changeradar-analyzer-java"))
  implementation(project(":changeradar-analyzer-kotlin"))
  implementation(project(":changeradar-analyzer-typescript"))
  implementation(project(":changeradar-analyzer-python"))
  implementation(project(":changeradar-analyzer-php"))
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

tasks.shadowJar {
  archiveFileName.set("changeradar.jar")
  mergeServiceFiles()
  manifest {
    attributes["Main-Class"] = "io.github.anikethts.changeradar.cli.ChangeRadarApp"
  }
}
