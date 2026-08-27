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

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version Versions.gradlePluginPublishPlugin
    id("com.vanniktech.maven.publish")
}

buildscript {
    dependencies {
        classpath("org.yaml:snakeyaml:1.33")
    }
}

dependencies {
    compileOnly(kotlin("gradle-plugin"))
    compileOnly("com.android.tools.build:gradle:${Versions.Android.buildTools}")
    implementation("javax.xml.bind:jaxb-api:2.3.1")
}

gradlePlugin {
    website.set("https://github.com/realm/realm-kotlin")
    vcsUrl.set("https://github.com/realm/realm-kotlin")
    isAutomatedPublishing = true
    plugins {
        create("RealmPlugin") {
            id = Realm.pluginPortalId
            displayName = "Realm Kotlin Plugin"
            description = "Gradle plugin for the Realm Kotlin SDK, supporting Android and Multiplatform. " +
                    "Realm is a mobile database: Build better apps faster."
            implementationClass = "io.realm.kotlin.gradle.RealmPlugin"
            tags.set(
                listOf(
                    "MongoDB",
                    "Realm",
                    "Database",
                    "Kotlin",
                    "Mobile",
                    "Multiplatform",
                    "Android",
                    "KMM"
                )
            )
        }
    }
}

mavenPublishing {
    coordinates(Realm.group, Realm.gradlePluginId, Realm.version)

    pom {
        name.set("Gradle Plugin")
        description.set(
            "Gradle plugin for Realm Kotlin. Realm is a mobile database: Build better apps faster."
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

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = Versions.sourceCompatibilityVersion
    targetCompatibility = Versions.targetCompatibilityVersion
}

val versionDirectory = layout.buildDirectory.dir("generated/source/version").get().asFile.path

sourceSets {
    main {
        java.srcDir(versionDirectory)
    }
}

val versionConstants = tasks.register("versionConstants") {
    val outputDir = file(versionDirectory)
    outputs.dir(outputDir)
    val coreVersion = "14.12.0"
    doLast {
        val versionFile = file("$outputDir/io/realm/kotlin/gradle/version.kt")
        versionFile.parentFile.mkdirs()
        versionFile.writeText(
            """
            // Generated file. Do not edit!
            package io.realm.kotlin.gradle
            internal const val PLUGIN_VERSION = "${project.version}"
            internal const val CORE_VERSION = "$coreVersion"
            """.trimIndent()
        )
    }
}

tasks.named("compileKotlin") { dependsOn(versionConstants) }
tasks.named("sourcesJar") { dependsOn(versionConstants) }

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(versionConstants)
}