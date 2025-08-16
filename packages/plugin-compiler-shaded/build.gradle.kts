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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java`
    id("com.github.johnrengelman.shadow") version Versions.shadowJar
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(project(":plugin-compiler"))
}

val mavenPublicationName = "compilerPluginShaded"
tasks {
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        this.destinationDirectory.set(file("$buildDir/libs"))
    }
}
tasks {
    named("jar") {
        actions.clear()
        dependsOn(
            shadowJar
        )
    }
}
java {
    withSourcesJar()
    // withJavadocJar() optional, remove if you don't want javadoc
    sourceCompatibility = Versions.sourceCompatibilityVersion
    targetCompatibility = Versions.targetCompatibilityVersion
}

mavenPublishing {
    coordinates(Realm.group, Realm.compilerPluginIdNative, Realm.version)

    pom {
        name.set("Shaded Compiler Plugin")
        description.set(
            "Shaded compiler plugin for native platforms for Realm Kotlin. " +
                    "This artifact is not supposed to be consumed directly, but through " +
                    "'io.realm.kotlin:gradle-plugin:${Realm.version}' instead."
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
