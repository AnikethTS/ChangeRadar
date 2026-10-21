import org.gradle.testing.jacoco.tasks.JacocoReport

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
  apply(plugin = "jacoco")
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

  tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
      xml.required.set(true)
      html.required.set(false)
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

tasks.register("collectTestEvidence") {
  val coverageOut = layout.buildDirectory.dir("test-evidence/coverage")
  val junitOut = layout.buildDirectory.dir("test-evidence/junit")
  dependsOn(subprojects.map { it.tasks.named("jacocoTestReport") })
  outputs.dir(coverageOut)
  outputs.dir(junitOut)
  doLast {
    val coverage = coverageOut.get().asFile
    val junit = junitOut.get().asFile
    coverage.mkdirs()
    junit.mkdirs()
    subprojects.forEach { sub ->
      val xml =
        sub.layout.buildDirectory
          .file("reports/jacoco/test/jacocoTestReport.xml")
          .get()
          .asFile
      if (xml.isFile && xml.length() > 0L) {
        xml.copyTo(coverage.resolve("${sub.name}-jacoco.xml"), overwrite = true)
      }
      val results =
        sub.layout.buildDirectory
          .dir("test-results/test")
          .get()
          .asFile
      results
        .listFiles()
        ?.filter { it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
        ?.forEach { file ->
          file.copyTo(junit.resolve("${sub.name}-${file.name}"), overwrite = true)
        }
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
