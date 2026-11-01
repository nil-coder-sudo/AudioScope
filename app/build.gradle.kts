plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 簽章 ──
// CI 從 GitHub Secrets 解出 keystore，並透過環境變數傳進來；
// 本機編譯可在專案根目錄放 keystore.properties（已列入 .gitignore）。
// 兩者都沒有時退回 debug 金鑰（每台機器不同，無法覆蓋安裝，只適合測試）。
val localProps = java.util.Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop)

val releaseStore = secret("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// 版本：CI 用執行編號當 versionCode（只增不減，才能覆蓋安裝），tag 當 versionName
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val tagName = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }

android {
    namespace = "com.audioscope"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.audioscope"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = tagName?.removePrefix("v") ?: "1.1.$runNumber"
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = secret("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secret("KEY_ALIAS", "keyAlias")
                keyPassword = secret("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
