# TextReader

TextReader 是一個 Android 影像文字讀取工具。拍照或從相簿選圖後，由 Azure Decisions 判斷影像類型，再由 Azure Content Understanding 讀取全文。結果保留在本次掃描清單，同類型的多筆結果可分別開啟。

**[下載 TextReader 1.2.1 APK](https://github.com/Angus-repo/TextReader/raw/refs/heads/main/apk/TextReader-1.2.1.apk)**。支援 Android 9 以上，沿用 1.0.0「台灣證件讀取」的套件名稱、簽署憑證與連線設定。

## 使用方式

1. 在「連線設定」選擇 Microsoft Decision 1.1 或 Azure OpenAI Decisions，填入 Azure 公有雲資源端點、實際部署名稱及金鑰。
2. 填入 Content Understanding 資源端點與金鑰。全文辨識使用內建 OCR 分析器，可直接開始掃描。
3. 點「拍照」或「從相簿選取」，預覽後按「開始辨識」。預設會依序完成分類與 OCR。勾選「讀取前讓我確認分類」即可在兩步之間更正類型。
4. 在結果頁檢視文件類型與全文，長按文字可選取。回首頁可開啟本次其他結果或清除整批資料。

## 依描述設定辨識規則

在首頁點「辨識規則」，選擇內建類型或「新增自訂類型」。11 個內建類型已預填分類描述與共 62 個欄位的讀取說明。規則清單可預覽分類描述，點入後可修改。新增自訂類型時，可按「帶入內建範例」複製一份分類描述與欄位，再為新類型命名及調整。已自行儲存的類型優先使用手機上的版本，點「還原此類型的預設規則」可取代該類型的自訂內容。每個類型可設定：

- **分類描述**：填寫這個類型獨有的標題、版面及正反面特徵。兩種 Decisions 服務均會使用儲存後的描述。
- **欄位名稱與讀取描述**：可以新增、修改或移除欄位。例如「健保卡卡號」可描述為「讀取卡片下方的 12 碼卡號，保留開頭的 0」。
- **自動讀取欄位**：勾選「辨識為此類型時，自動讀取設定的欄位」後儲存。下次掃描此類型會一併顯示全文與具名欄位。每次掃描也可以在影像確認頁單獨選擇「全文及已設定的欄位」或「只讀全文」。

規則儲存在手機。內建類型可還原預設，自訂類型可刪除。修改欄位的讀取描述後，下次執行欄位擷取會選用符合新規則的 Azure 分析器。只變更分類描述時可沿用原分析器。

| 已選的讀取方式 | Content Understanding 分析器 |
| --- | --- |
| 收據或表單的全文 | prebuilt-layout |
| 其他類型的全文，含無法歸類的影像 | prebuilt-read |
| 全文及已設定的欄位 | 使用該類型的自訂分析器，一次讀取全文與欄位；未設定欄位的類型只讀全文 |

自訂欄位需要在 Content Understanding 設定擷取模型及 text-embedding-3-large 的部署對應，並允許建立分析器。首次擷取新版欄位時會使用與其描述相符的分析器。

## 資料處理

App 支援系統截圖。上傳於使用者按下「開始辨識」後執行。縮圖傳送至 Decisions，重新編碼的影像傳送至 Content Understanding。App 在記憶體保留本次結果，結束程序即清除。相機暫存檔於載入後刪除，連線設定由 Android Keystore 提供的 AES-GCM 金鑰加密，停用系統自動備份。

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
