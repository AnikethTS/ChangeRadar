plugins {
  java
  alias(libs.plugins.spotless)
}

val changeradarVersion =
  providers.fileContents(layout.projectDirectory.file("VERSION")).asText.map { it.trim() }

val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.platform.launcher

allprojects {
  group = "io.github.anikethts.changeradar"
  version = changeradarVersion.get()

  repositories {
    mavenCentral()
  }
}

subprojects {
  apply(plugin = "java")
  apply(plugin = "com.diffplug.spotless")

  the<JavaPluginExtension>().apply {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
  }

  dependencies {
    add("testImplementation", junitJupiter)
    add("testRuntimeOnly", junitLauncher)
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
      events("passed", "skipped", "failed")
    }
  }

  the<com.diffplug.gradle.spotless.SpotlessExtension>().apply {
    java {
      target("src/**/*.java")
      googleJavaFormat("1.28.0")
      endWithNewline()
    }
    kotlinGradle {
      target("*.gradle.kts")
      ktlint()
      endWithNewline()
    }
  }
}

spotless {
  kotlinGradle {
    target("*.gradle.kts")
    ktlint()
    endWithNewline()
  }
}
