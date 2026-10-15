pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "changeradar"

include(
  "changeradar-core",
  "changeradar-git",
  "changeradar-analyzer-java",
  "changeradar-cli",
)
