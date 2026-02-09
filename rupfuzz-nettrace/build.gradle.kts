plugins {
  application
  id("com.ibm.wala.gradle.java")
}

eclipse.project.natures("org.eclipse.pde.PluginNature")

application { mainClass = "org.zlab.nettrace.cli.NettraceCli" }

dependencies {
  implementation(projects.core)
  implementation(libs.commons.cli)
  implementation(libs.gson)
  implementation(libs.snakeyaml)

  testImplementation(libs.assertj.core)
  testImplementation(libs.junit.jupiter.api)
}
