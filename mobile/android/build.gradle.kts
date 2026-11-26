allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
// camera_android_camerax compiles against CameraX 1.5, whose API references
// androidx.concurrent.futures.CallbackToFutureAdapter; make it available to every plugin module.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        dependencies.add("implementation", "androidx.concurrent:concurrent-futures:1.2.0")
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
