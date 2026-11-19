pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WikiAI-Offline"
include(":app")
val upstreamLlamaModule = file("vendor/llama.cpp/examples/llama.android/lib")
if (!upstreamLlamaModule.resolve("build.gradle.kts").isFile) {
    error("Módulo llama.cpp ausente. Execute PREPARAR_IA_WINDOWS.bat ou bash PREPARAR_IA_LINUX_MAC.sh antes de compilar.")
}
include(":lib")
project(":lib").projectDir = upstreamLlamaModule
