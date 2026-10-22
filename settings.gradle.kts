rootProject.name = "KendiRepo"

// This file sets what projects are included.
// All new projects should get automatically included unless specified in the "disabled" variable.

// Sablon sadece yeni eklenti oluşturmak için var; GitHub'da derlenip yayınlanmaz.
val disabled = if (System.getenv("CI") != null) listOf("Sablon") else listOf()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// To only include a single project, comment out the previous lines (except the first one), and include your plugin like so:
// include("PluginName")