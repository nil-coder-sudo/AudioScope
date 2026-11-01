# AudioScope（Android 版）

專業音訊分析 App：即時頻譜、即時瀑布圖、整首頻譜圖（找隱藏圖像）、電平、EBU R128 響度、真峰值、相位、向量示波器，以及完整的檔案資訊。

純 Kotlin、沒有任何第三方執行期套件，Android 8.0（API 26）以上可用。授權：MIT（見 `LICENSE`）。

<!-- 建議在這裡放幾張實機截圖：docs/screenshot-live.png、docs/screenshot-spectrogram.png -->

## 下載

到 repository 的 **Releases** 頁面下載最新的 `AudioScope.apk`。不需要登入，也不會過期。

## 發佈新版

1. 把修改推上 GitHub。每次推送都會自動跑單元測試並編譯，產物在 Actions 頁面（需登入，90 天後過期，適合自己測試）。
2. 要正式發佈時：**Releases → Draft a new release → Choose a tag**，輸入新版號，例如 `v1.2.0`，然後按 **Publish release**。
3. Actions 會自動編譯，幾分鐘後 `AudioScope.apk` 會出現在這個 Release 的附件裡。

`versionCode` 會自動使用 Actions 的執行編號（只增不減），`versionName` 會使用 tag（例如 `v1.2.0` 會變成 `1.2.0`）。

## 固定簽章（一定要設定）

Android 只允許「同一把金鑰簽章」的新版覆蓋安裝。沒設定時，CI 每次都在新機器上產生新的 debug 金鑰，使用者必須先解除安裝才能更新。

在 **Settings → Secrets and variables → Actions** 新增四個 repository secret：

| Name | 內容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 檔案的 base64（一整行） |
| `KEYSTORE_PASSWORD` | keystore 密碼 |
| `KEY_ALIAS` | 金鑰別名 |
| `KEY_PASSWORD` | 金鑰密碼 |

自己產生金鑰（Android Studio 內附的 JDK 就有 `keytool`）：

```
keytool -genkeypair -keystore audioscope-release.jks -storetype PKCS12 -alias audioscope -keyalg RSA -keysize 3072 -validity 18250
base64 -w0 audioscope-release.jks          # Linux
certutil -encode audioscope-release.jks b64.txt   # Windows（刪掉頭尾兩行與所有換行）
```

**金鑰檔請備份到安全的地方，不要提交到 repo**（`.gitignore` 已排除 `*.jks`）。弄丟了就無法再發佈能覆蓋安裝的版本。

> 注意：從舊版（debug 簽章）換成正式金鑰時，需要最後一次手動解除安裝；之後的版本就能直接覆蓋安裝。

本機用 Android Studio 編 release 版時，在專案根目錄建立 `keystore.properties`（已被 `.gitignore` 排除）：

```
storeFile=/完整路徑/audioscope-release.jks
storePassword=…
keyAlias=audioscope
keyPassword=…
```

## 功能

**即時分頁**
- 波形總覽：點或拖曳後放開即可跳轉
- 即時頻譜：平滑曲線、峰值保持（橘色虛線）、自動標出峰值頻率與音名；手指按住可讀取頻率、dB 與音分
- 即時瀑布圖：按住可讀取頻率與時間
- 電平表：RMS（實心）、Peak（半透明）、峰值保持（白線，削波時變紅）；多聲道檔案每個聲道各一條
- 向量示波器（自動增益）與相位相關性
- 讀數：峰值頻率、音名、瞬時 M／短期 S／整合 I 響度、真峰值

**頻譜圖分頁**
- 整首歌的頻譜圖，可切換線性或對數刻度
- **聲道：混合／L／R／Side**。藏在 Side（左右反相）的內容在混合聲道會被完全抵消，要切到 Side 才看得到
- 兩指縮放（水平、垂直可各自縮放）、單指拖曳、點一下跳到該時間、點兩下還原
- 「自動對比」與上下限滑桿

**資訊分頁**
- 檔案、電平、響度（I／LRA／M／S／真峰值／PLR／各平台比較）、動態、立體聲、頻譜特徵、調性、BPM、品質檢測（有損轉檔、補零位元深度、削波、DC）、標籤
- 可複製或分享成文字報告

**其他**
- 麥克風即時分析：支援的裝置會優先使用「未處理」音源（沒有自動增益和降噪）
- 可以從檔案管理員選「用 AudioScope 開啟」，或從其他 App 分享音檔進來
- ⚙ 設定：FFT 大小、窗函數、頻率刻度、色彩、聲道（混合／L／R／Mid／Side）、顯示範圍、平滑、瀑布長度、峰值保持

## 技術細節

- **響度**：依 ITU-R BS.1770-4 / EBU R128。多聲道依聲道遮罩加權：LFE 不計，環繞聲道 ×1.41。即時響度為串流式，濾波器狀態持續保留，每格只處理新樣本。
- **解碼**：WAV 由 App 自己解析，支援 8／16／24／32 bit 整數、32／64 bit 浮點、WAVE_FORMAT_EXTENSIBLE，保留真實位元深度與聲道遮罩。其他格式（MP3、AAC／M4A、FLAC、Ogg Vorbis、Opus…）用 MediaCodec，並要求浮點輸出，24-bit FLAC 不會被截成 16-bit。
- **記憶體**：解碼結果先寫進暫存檔，知道確切長度後才配置，峰值記憶體約等於最終大小（48 kHz 立體聲每分鐘約 23 MB）。超過可用記憶體會顯示明確的錯誤訊息，不會閃退。換檔時會先停掉舊的解碼與分析。
- **播放**：多聲道檔案以原始聲道配置輸出，由系統縮混；不支援時自行縮混成立體聲（不含 LFE，係數已正規化避免削波）。

## 測試

DSP 與分析核心是純 Kotlin，可以在電腦上跑單元測試（CI 每次推送都會執行）：

```
./gradlew testDebugUnitTest
```

測試涵蓋 FFT、音名、BS.1770 響度（與 pyloudnorm 對照）、串流響度與離線結果一致、5.1 聲道加權與 LFE 排除、真峰值的取樣間峰值、Side 聲道頻譜圖、BPM、縮混不削波。

## 用 Android Studio 編譯

用 Android Studio 開啟這個資料夾，等 Gradle 同步完成，再按 ▶ Run，或選 Build → Build APK(s)。
