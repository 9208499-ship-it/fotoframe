plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    // KSP вместо kapt: Room генерирует код без «gradle clean» после каждой
    // правки DAO и заметно быстрее собирается.
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
