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
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library") apply false
    id("com.vanniktech.maven.publish") version "0.34.0"
    id ("signing")
    id("realm-lint")
    `java-gradle-plugin`
}

allprojects {
    version = Realm.version
    group = Realm.group

    // Define JVM bytecode target for all Kotlin targets
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(Versions.kotlinJvmTarget))
        }
    }
}

mavenPublishing {
    pom {
        name.set("Realm Kotlin")
        description.set(
            "Realm Kotlin is a library and tools to work with the Realm Database in Kotlin applications. "
        )
        url.set(Realm.projectUrl)
        licenses {
            license {
                name.set(Realm.License.name)
                url.set(Realm.License.url)
                distribution.set(Realm.License.distribution)
            }
        }
        developers {
            developer {
                id.set(Realm.Developer.name)
                name.set(Realm.Developer.name)
                url.set(Realm.Developer.organizationUrl)
            }
        }
        scm {
            url.set(Realm.SCM.url)
            connection.set(Realm.SCM.connection)
            developerConnection.set(Realm.SCM.developerConnection)
        }
    }
}

/**
 * Task that will build and publish the defined packages to <root>/packages/build/m2-buildrepo`.
 * This is mostly suited for CI jobs that wants to build select publications on specific runners.
 *
 *
 * See `gradle.properties` for specific configuration options available to this task.
 *
 * For local development, using:
 *
 * ```
 * > ./gradlew publishAllPublicationsToTestRepository
 * ```
 *
 * will build and publish all targets available to the builder platform.
 */
tasks.register("publishCIPackages") {
    group = "Publishing"
    description = "Publish packages that has been configured for this CI node. See `gradle.properties`."

    // Figure out which targets are configured. This will impact which sub modules will be published
    val availableTargets = setOf(
        "jvm",
        "android",
        "metadata",
        "compilerPlugin",
        "gradlePlugin"
    )

    val mainHostTarget: Set<String> = setOf("metadata") // "kotlinMultiplatform"

    val isMainHost: Boolean = project.properties["realm.kotlin.mainHost"]?.let { it == "true" } ?: false

    // Find user configured platforms (if any)
    val userTargets: Set<String>? = (project.properties["realm.kotlin.targets"] as String?)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()

    userTargets?.forEach {
        if (!availableTargets.contains(it)) {
            project.logger.error("Unknown publication: $it")
            throw IllegalArgumentException("Unknown publication: $it")
        }
    }

    // Configure which platforms publications we do want to publish
    val publicationTargets = (userTargets ?: availableTargets).let {
        when (isMainHost) {
            true -> it + mainHostTarget
            false -> it - mainHostTarget
        }
    }

    publicationTargets.forEach { target: String ->
        when(target) {

            "jvm" -> {
                dependsOn(
                    ":library-base:publishJvmPublicationToTestRepository",
                )
            }

            "android" -> {
                dependsOn(
                    ":library-base:publishAndroidReleasePublicationToTestRepository",
                )
            }
            "metadata" -> {
                dependsOn(
                    ":library-base:publishKotlinMultiplatformPublicationToTestRepository",
                )
            }
            "compilerPlugin" -> {
                dependsOn(
                    ":plugin-compiler:publishAllPublicationsToTestRepository",
                    ":plugin-compiler-shaded:publishAllPublicationsToTestRepository"
                )
            }
            "gradlePlugin" -> {
                dependsOn(":gradle-plugin:publishAllPublicationsToTestRepository")
            }
            else -> {
                throw IllegalArgumentException("Unsupported target: $target")
            }
        }
    }
}
