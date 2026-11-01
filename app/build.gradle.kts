import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 簽章 ──
// CI 從 GitHub Secrets 解出 keystore，並透過環境變數傳進來；
// 本機編譯可在專案根目錄放 keystore.properties（已列入 .gitignore）。
// 兩者都沒有時退回 debug 金鑰（每台機器不同，無法覆蓋安裝，只適合測試）。
// 注意：Gradle Kotlin 腳本裡的 `java` 是 java { } 擴充區塊，不能寫成 java.util.Properties，要用 import。
val localProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop)

val releaseStore = secret("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// 版本：versionCode = 100 + git commit 數。
// 同一個 commit 不論在 CI 或本機編譯都得到同一個號碼，而且只增不減，才能覆蓋安裝。
// （+100 是為了高於舊版用 CI 執行編號產生的版號，已安裝的舊版仍可直接升級。）
// CI 需要完整歷史（checkout 的 fetch-depth: 0）；拿不到 git 資訊時退回 1。
val commitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)
val tagName = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }

android {
    namespace = "com.audioscope"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.audioscope"
        minSdk = 26
        targetSdk = 34
        versionCode = if (commitCount > 0) 100 + commitCount else 1
        versionName = tagName?.removePrefix("v") ?: "1.1.$commitCount"
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
