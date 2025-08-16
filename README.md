
# Realm Kotlin (Fork)

This is a **fork of the official [realm-kotlin](https://github.com/realm/realm-kotlin)** project.
This fork aims to modernize the library, streamline the build process, and ensure compatibility with Android projects that use the latest Kotlin and build tools.

## ❓ Why this fork exists

The latest official release of Realm Kotlin (v3.0.0) does not support Kotlin 2.x or higher (current Kotlin is 2.2.0).
Additionally, the Realm team officially deprecated the project and ended support in October 2024.

## 🔄 Changes in this Fork

* **Kotlin updated** → Migrated from Kotlin `1.x` to **Kotlin 2.2.0**.
* **Gradle & Java updated** → Upgraded to modern Gradle and Java versions for compatibility.
* **Simplified build**:

    * Commented out code related to building native libraries.
    * Using **prebuilt Realm native binaries** from
      [`io.realm.kotlin:cinterop:3.0.0`](https://mvnrepository.com/artifact/io.realm.kotlin/cinterop/3.0.0).
* **Deployment**:

    * Only **Android artifacts** are built and deployed.
    * Published under [`com.simprints:realm`](https://mvnrepository.com/artifact/com.simprints/realm).
* **Repository cleanup**:

    * Commented non-Kotlin variants (iOS and macOS). and  native C code, relying instead on existing binaries.

## ⚡ Migration Notes

* **No migration effort required**.
* This fork relies on the same Realm core libraries, so existing projects can switch to the fork without any code changes.
* Note: This fork only publishes **Android artifacts**.

## 📦 Usage

Add the following dependency in your Gradle configuration:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.simprints.realm-kotlin:<forked-version>")
}
```

## 📖 Documentation

For original documentation, please see [README\_LEGACY.md](./README_LEGACY.md).

---