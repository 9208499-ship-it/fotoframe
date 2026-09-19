import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Ключ подписи и пароли лежат в keystore.properties рядом с проектом и в
// репозиторий не попадают (см. .gitignore). Без этого файла собирается
// только debug — release просто не подпишется.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.fotoframe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fotoframe"
        // Android 8.0. Ниже приложение никто не проверял, а smbj и ML Kit
        // на старых версиях ведут себя непредсказуемо — обещать поддержку,
        // которой не видели в работе, нечестно.
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.7.1"
    }

    signingConfigs {
        create("release") {
            val path = keystoreProperties.getProperty("storeFile")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8 выключен намеренно: smbj и BouncyCastle обращаются к
            // классам через отражение, и без выверенных keep-правил
            // сетевая папка отваливается уже после установки, а не при
            // сборке. Цена — размер APK.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Схема базы сохраняется в app/schemas: по ней видно, что именно меняет
// каждая версия, и её можно проверить тестом миграции.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Хранилище индекса
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Настройки
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Фоновая индексация
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Загрузка изображений
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Сеть (Яндекс.Диск)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Чтение EXIF для даты съёмки
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Распознавание лиц офлайн (модель зашита в APK, Play Services не нужны)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Сетевые папки по SMB2/SMB3
    implementation("com.hierynomus:smbj:0.12.2")
    // Список общих папок хранилища (srvsvc через RPC) — сам smbj этого не умеет.
    // Библиотека тянет старую BouncyCastle под прежним именем (bcprov-jdk15on),
    // а smbj — ту же под новым (bcprov-jdk18on); вместе они дают дубли классов.
    // Старую исключаем, работает с той, что пришла со smbj.
    implementation("com.rapid7.client:dcerpc:0.12.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "com.hierynomus", module = "smbj")
    }

    // Заставка: Compose внутри DreamService нуждается во владельцах
    // жизненного цикла, состояния и ViewModel.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Тесты чистой логики: даты из имён, смещение выборки, кадрирование.
    testImplementation("junit:junit:4.13.2")
}
