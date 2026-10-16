pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "changeradar"

include(
  "changeradar-core",
  "changeradar-git",
  "changeradar-analyzer-java",
  "changeradar-analyzer-typescript",
  "changeradar-analyzer-python",
  "changeradar-analyzer-php",
  "changeradar-cli",
)
