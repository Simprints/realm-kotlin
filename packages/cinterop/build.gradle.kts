/*
 * Copyright 2020 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("realm-publisher")
}

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:${Versions.atomicfu}")
    }
}

apply(plugin = "kotlinx-atomicfu")
// AtomicFu cannot transform JVM code. Throws
// ClassCastException: org.objectweb.asm.tree.InsnList cannot be cast to java.lang.Iterable
project.extensions.configure(kotlinx.atomicfu.plugin.gradle.AtomicFUPluginExtension::class) {
    transformJvm = false
}

// Directory for generated Version.kt holding VERSION constant
val versionDirectory = "$buildDir/generated/source/version/"

// Types of builds supported
enum class BuildType(val type: String, val buildDirSuffix: String) {
    DEBUG( type ="Debug", buildDirSuffix = "-dbg"),
    RELEASE( type ="Release", buildDirSuffix = "");
}


fun checkIfBuildingNativeLibs(task: Task, action: Task.() -> Unit) {
    // Whether or not to build the underlying native Realm Libs. Generally these are only
    // needed at runtime and thus can be ignored when only building the layers on top
    if (project.extra.properties["realm.kotlin.buildRealmCore"] == "true") {
        action(task)
    } else {
        logger.warn("Ignore building native libs")
    }
}

val corePath = "external/core"
val absoluteCorePath = "$rootDir/$corePath"
val jvmJniPath = "src/jvmMain/resources/jni"

fun includeBinaries(binaries: List<String>): List<String> {
    return binaries.flatMap { listOf("-include-binary", it) }
}
@Suppress("UNUSED_VARIABLE")
kotlin {
    jvm()
    androidTarget {
        // Changing this will also requires an update to the publishCIPackages task
        // in /packages/build.gradle.kts
        publishLibraryVariants("release")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}")
                api("org.mongodb.kbson:kbson:${Versions.kbson}")
            }
            kotlin.srcDir(versionDirectory)
        }
        val commonTest by getting
        val jvm by creating {
            dependsOn(commonMain)
            dependencies {
                api(project(":jni-swig-stub"))
            }
        }
        val jvmMain by getting {
            dependsOn(jvm)
        }
        val androidMain by getting {
            dependsOn(jvm)
            dependencies {
                implementation("androidx.startup:startup-runtime:${Versions.androidxStartup}")
                implementation("com.getkeepsafe.relinker:relinker:${Versions.relinker}")
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("reflect"))
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("junit:junit:${Versions.junit}")
                implementation("androidx.test.ext:junit:${Versions.androidxJunit}")
                implementation("androidx.test:runner:${Versions.androidxTest}")
                implementation("androidx.test:rules:${Versions.androidxTest}")
            }
        }

    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.ExperimentalUnsignedTypes")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.withType<KotlinNativeCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
    }
}

android {
    namespace = "io.realm.kotlin.internal.interop"
    compileSdk = Versions.Android.compileSdkVersion
    buildToolsVersion = Versions.Android.buildToolsVersion
    ndkVersion = Versions.Android.ndkVersion

    defaultConfig {
        minSdk = Versions.Android.minSdk
        targetSdk = Versions.Android.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        sourceSets {
            getByName("main") {
                manifest.srcFile("src/androidMain/AndroidManifest.xml")
                // Don't know how to set AndroidTest source dir, probably in its own source set by
                // "val test by getting" instead
                // androidTest.java.srcDirs += "src/androidTest/kotlin"
            }
        }

        ndk {
            abiFilters += setOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
        }

        // Out externalNativeBuild (outside defaultConfig) does not seem to have correct type for setting cmake arguments
        externalNativeBuild {
            cmake {
                if (!HOST_OS.isWindows()) {
                    // CCache is not officially supported on Windows and there are problems
                    // using it with the Android NDK. So disable for now.
                    // See https://github.com/ccache/ccache/discussions/447 for more information.
                    arguments("-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                    arguments("-DCMAKE_C_COMPILER_LAUNCHER=ccache")
                }
                targets.add("realmc")
            }
        }
    }

    // Inner externalNativeBuild (inside defaultConfig) does not seem to have correct type for setting path
    externalNativeBuild {
        cmake {
            // We need to grab cmake version from `cmake --version` on the path and set it here
            // otherwise the build system will use the one from the NDK
            @Suppress("UnstableApiUsage")
            version = project.providers.of(CmakeVersionProvider::class) {}.get()
            path = project.file("src/jvm/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = Versions.sourceCompatibilityVersion
        targetCompatibility = Versions.targetCompatibilityVersion
    }
}

val buildJVMSharedLibs: TaskProvider<Task> by tasks.registering {
    if (HOST_OS.isMacOs()) {
        buildSharedLibrariesForJVMMacOs()
    } else if (HOST_OS.isWindows()) {
        buildSharedLibrariesForJVMWindows()
    } else {
        throw IllegalStateException("Building JVM libraries on this platform is not supported: $HOST_OS")
    }
}

/**
 * Task responsible for copying native files for all architectures into the correct location,
 * making the library ready for distribution.
 *
 * The task assumes that some other task already pre-built the binaries, making this task
 * mostly useful on CI.
 */
val copyJVMSharedLibs: TaskProvider<Task> by tasks.registering {
    val copyJvmABIs = project.hasProperty("realm.kotlin.copyNativeJvmLibs")
            && (project.property("realm.kotlin.copyNativeJvmLibs") as String).isNotEmpty()
    logger.info("Copy native Realm JVM libraries: $copyJvmABIs")
    if (copyJvmABIs) {
        val archs = (project.property("realm.kotlin.copyNativeJvmLibs") as String)
            .split(",")
            .map { it.trim() }
            .map { it.toLowerCase() }

        archs.forEach { arch ->
            when(arch) {
                "linux" -> {
                    // copy Linux pre-built binaries
                    project.file("$buildDir/realmLinuxBuild/librealmc.so")
                        .copyTo(project.file("$jvmJniPath/linux/librealmc.so"), overwrite = true)
                    outputs.file(project.file("$jvmJniPath/linux/librealmc.so"))
                }
                "macos" -> {
                    // copy MacOS pre-built binaries
                    project.file("$buildDir/realmMacOsBuild/librealmc.dylib")
                        .copyTo(project.file("$jvmJniPath/macos/librealmc.dylib"), overwrite = true)
                    outputs.file(project.file("$jvmJniPath/macos/librealmc.dylib"))
                }
                "windows" -> {
                    // copy Window pre-built binaries
                    project.file("$buildDir/realmWindowsBuild/Release/realmc.dll")
                        .copyTo(project.file("$jvmJniPath/windows/realmc.dll"), overwrite = true)
                    outputs.file(project.file("$jvmJniPath/windows/realmc.dll"))
                }
                else -> throw IllegalArgumentException("Unsupported platform for realm.kotlin.copyNativeJvmLibs: $arch")
            }
        }
    }
}

/**
 * Consolidate shared CMake flags used across all configurations
 */
fun getSharedCMakeFlags(buildType: BuildType, ccache: Boolean = true): Array<String> {
    // Any change to CMAKE properties here, should be reflected in GHA(.github/workflows/pr.yml), specifically
    // the `build_jvm_linux` and `build_jvm_windows` functions.
    val args = mutableListOf<String>()
    if (ccache) {
        args.add("-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
        args.add("-DCMAKE_C_COMPILER_LAUNCHER=ccache")
    }
    val cmakeBuildType: String = when(buildType) {
        BuildType.DEBUG -> "Debug"
        BuildType.RELEASE -> "Release"
    }
    with(args) {
        add("-DCMAKE_BUILD_TYPE=$cmakeBuildType")
        add("-DREALM_NO_TESTS=1")
        add("-DREALM_BUILD_LIB_ONLY=true")
        add("-DREALM_CORE_SUBMODULE_BUILD=true")
        // This will prevent exporting Core's symbols which is useful when combining for example the Swift SDK and Kotlin Multiplatform
        // in the same app. The generated dynamically linked shared Framework from Kotlin/Native can then be linked dynamically into the iOS app (!use_frameworks in Cocoapods)
        // or statically. This will also reduce the binary size a little bit (avoid storing metadata about exported symbols)
        add("-DCMAKE_CXX_VISIBILITY_PRESET=hidden")
    }
    return args.toTypedArray()
}

// JVM native libs are currently always built in Release mode.
fun Task.buildSharedLibrariesForJVMMacOs() {
    group = "Build"
    description = "Compile dynamic libraries loaded by the JVM fat jar for supported platforms."
    val directory = "$buildDir/realmMacOsBuild"

    doLast {
        exec {
            commandLine("mkdir", "-p", directory)
        }
        exec {
            workingDir(project.file(directory))
            commandLine(
                "cmake",
                *getSharedCMakeFlags(BuildType.RELEASE),
                "-DCPACK_PACKAGE_DIRECTORY=..",
                "-DCMAKE_OSX_ARCHITECTURES=x86_64;arm64",
                project.file("src/jvm/")
            )
        }
        exec {
            workingDir(project.file(directory))
            commandLine("cmake", "--build", ".", "-j8")
        }

        // copy files (macos)
        exec {
            commandLine("mkdir", "-p", project.file("$jvmJniPath/macos"))
        }
        File("$directory/librealmc.dylib")
            .copyTo(project.file("$jvmJniPath/macos/librealmc.dylib"), overwrite = true)
    }

    inputs.dir(project.file("src/jvm"))
    inputs.dir(project.file("$absoluteCorePath/src"))
    outputs.file(project.file("$jvmJniPath/macos/librealmc.dylib"))
}

fun Task.buildSharedLibrariesForJVMWindows() {
    group = "Build"
    description = "Compile dynamic libraries loaded by the JVM fat jar for supported platforms."
    val directory = "$buildDir/realmWindowsBuild"

    doLast {
        file(directory).mkdirs()
        exec {
            workingDir(project.file(directory))
            commandLine(
                "cmake",
                *getSharedCMakeFlags(BuildType.RELEASE, ccache = false),
                "-DCMAKE_GENERATOR_PLATFORM=x64",
                "-DCMAKE_SYSTEM_VERSION=8.1",
                "-DVCPKG_TARGET_TRIPLET=x64-windows-static",
                project.file("src/jvm/")
            )
        }
        exec {
            workingDir(project.file(directory))
            commandLine("cmake", "--build", ".", "--config", "Release")
        }

        // copy files (Windows)
        project.file("$jvmJniPath/windows").mkdirs()
        File("$directory/Release/realmc.dll")
            .copyTo(project.file("$jvmJniPath/windows/realmc.dll"), overwrite = true)
    }

    inputs.dir(project.file("$absoluteCorePath/src"))
    outputs.file(project.file("$jvmJniPath/windows/realmc.dll"))
}
afterEvaluate {
    // Ensure that Swig wrapper is generated before compiling the JNI layer. This task needs
    // the cpp file as it somehow processes the CMakeList.txt-file, but haven't dug up the
    // actuals
    tasks.named("generateJsonModelDebug") {
        inputs.files(tasks.getByPath(":jni-swig-stub:realmWrapperJvm").outputs)
    }
    tasks.named("generateJsonModelRelease") {
        inputs.files(tasks.getByPath(":jni-swig-stub:realmWrapperJvm").outputs)
    }
}

tasks.named("jvmMainClasses") {
    checkIfBuildingNativeLibs(this) {
        dependsOn(buildJVMSharedLibs)
    }
    dependsOn(copyJVMSharedLibs)
}

tasks.named("jvmProcessResources") {
    checkIfBuildingNativeLibs(this) {
        dependsOn(buildJVMSharedLibs)
    }
    dependsOn(copyJVMSharedLibs)
}


// Maven Central requires JavaDoc so add empty javadoc artifacts
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    // See https://dev.to/kotlin/how-to-build-and-publish-a-kotlin-multiplatform-library-going-public-4a8k
    publications.withType<MavenPublication> {
        // Stub javadoc.jar artifact
        artifact(javadocJar.get())
    }
}

realmPublish {
    pom {
        name = "C Interop"
        description =
            "Wrapper for interacting with Realm Kotlin native code. This artifact is not " +
            "supposed to be consumed directly, but through " +
            "'io.realm.kotlin:gradle-plugin:${Realm.version}' instead."
    }
}

// Generate code with version constant
val generateSdkVersionConstant: Task = tasks.create("generateSdkVersionConstant") {
    val outputDir = file(versionDirectory)

    inputs.property("version", project.version)
    outputs.dir(outputDir)

    doLast {
        val versionFile = file("$outputDir/io/realm/kotlin/internal/Version.kt")
        versionFile.parentFile.mkdirs()
        versionFile.writeText(
            """
            // Generated file. Do not edit!
            package io.realm.kotlin.internal
            public const val SDK_VERSION: String = "${project.version}"
            """.trimIndent()
        )
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
    dependsOn(generateSdkVersionConstant)
}

tasks.named("clean") {
    doLast {
        delete(buildJVMSharedLibs.get().outputs)
        delete(project.file(".cxx"))
    }
}

// Provider that reads the version of cmake that is on the PATH
@Suppress("UnstableApiUsage")
abstract class CmakeVersionProvider : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations
    override fun obtain(): String? {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("cmake", "--version")
            standardOutput = output
        }
        val cmakeOutput = String(output.toByteArray(), Charset.defaultCharset())
        val regex = "cmake version (?<version>[0-9\\.]*)".toRegex()
        val cmakeVersion = regex.find(cmakeOutput)?.groups?.get("version")
            ?: throw RuntimeException("Couldn't match cmake version from: '$cmakeOutput'")
        return cmakeVersion.value
    }
}

// enable execution optimizations for generateSdkVersionConstant
afterEvaluate {
    tasks.getByName("sourcesJar").dependsOn(generateSdkVersionConstant)
}
