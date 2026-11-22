# TextReader

TextReader 是一個 Android 影像文字讀取工具。拍照或從相簿選圖後，由 Azure Decisions 判斷影像類型，再由 Azure Content Understanding 讀取全文。結果保留在本次掃描清單，同類型的多筆結果可分別開啟。

**[下載 TextReader 1.1.0 APK](https://github.com/Angus-repo/TextReader/raw/refs/heads/main/apk/TextReader-1.1.0.apk)**。支援 Android 9 以上，沿用 1.0.0「台灣證件讀取」的套件名稱、簽署憑證與連線設定。

## 使用方式

1. 在「連線設定」選擇 Microsoft Decision 1.1 或 Azure OpenAI Decisions，填入 Azure 公有雲資源端點、實際部署名稱及金鑰。
2. 填入 Content Understanding 資源端點與金鑰。全文辨識使用內建 OCR 分析器，可直接開始掃描。
3. 點「拍照」或「從相簿選取」，預覽後按「開始辨識」。預設會依序完成分類與 OCR。勾選「讀取前讓我確認分類」即可在兩步之間更正類型。
4. 在結果頁檢視文件類型與全文，長按文字可選取。回首頁可開啟本次其他結果或清除整批資料。

| Decisions 判定類型 | 全文讀取方式 |
| --- | --- |
| 收據／發票、表單／表格 | 使用內建 prebuilt-layout 分析器 |
| 身分證正反面、健保卡正面、駕照正反面 | 預設使用 prebuilt-read。勾選證件欄位時，改由對應的自訂分析器一次讀取全文及欄位 |
| 名片、一般文件、標示／畫面文字、其他影像 | 使用內建 prebuilt-read。未歸入特定類型的影像也會進行 OCR |

若要辨識台灣證件並同時取得姓名、卡號、發證日期等具名欄位，勾選「台灣證件一併擷取指定欄位」。這項功能需預先在 Content Understanding 設定擷取模型及 text-embedding-3-large 的部署對應，並允許建立自訂分析器。既有 1.0.0 分析器可繼續使用。若欄位擷取失敗，可以取消勾選，再按「讀取文字」以保留分類並改讀全文。

## 資料處理

上傳於使用者按下「開始辨識」後執行。縮圖傳送至 Decisions，重新編碼的影像傳送至 Content Understanding。App 在記憶體保留本次結果，結束程序即清除。相機暫存檔於載入後刪除，連線設定由 Android Keystore 提供的 AES-GCM 金鑰加密，停用系統自動備份。

## 編譯與簽署

使用 JDK 17、Gradle 8.11.1 和 Android SDK 36。以 Android Studio 開啟本目錄，或在專案根目錄執行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

套件名稱為 `tw.codeviva.cardreader`。每次更新請提高 `versionCode`，沿用原始發行金鑰。Linux 簽署腳本會在簽署前後比對 `release-certificate.sha256`。

```sh
export ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
export TW_SIGN_STORE="/private/signing/TWCardReader-release.p12"
export TW_SIGN_PASSWORD_FILE="/private/signing/TWCardReader-signing-password.txt"
./sign-release.sh app/build/outputs/apk/release/app-release-unsigned.apk TextReader-release.apk
```

`app/src/main/assets/document-types.json` 定義分類標籤、分類判準及台灣證件欄位。測試以模擬回應驗證兩種 Decisions 格式、分類與 OCR 的先後順序、分析器選擇、欄位、無文字影像與畫面行為。

## 參考文件

- [Microsoft Decision 1.1](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-microsoft-decision)
- [Azure OpenAI Decisions](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/decisions)
- [Content Understanding 預建分析器](https://learn.microsoft.com/en-us/azure/ai-services/content-understanding/concepts/prebuilt-analyzers)
