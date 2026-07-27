plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Pinned to the KSP built for Kotlin 2.2.10, and pinned *below* 2.3.x on purpose: KSP 2.3+
    // removes the KSP1 backend outright, and Room 2.6.1's processor crashes under KSP2's
    // Analysis API worker with "unexpected jvm signature V" before it emits a single class.
    // Moving this alone breaks the build (see the ksp.useKSP2 note in gradle.properties) —
    // it can only go up together with Room.
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
