# TextReader

TextReader 是一個 Android 影像文字讀取工具。即時裁切文件或從相簿選圖後，由 Azure Decisions 判斷影像類型，再由 Azure Content Understanding 讀取全文。結果自動儲存在手機的「辨識紀錄」，同類型的多筆結果可分別開啟。

**[下載 TextReader 1.6.3 APK](https://github.com/Angus-repo/TextReader/raw/refs/heads/main/apk/TextReader-1.6.3.apk)**。支援 Android 9 以上，沿用 1.0.0「台灣證件讀取」的套件名稱、簽署憑證與連線設定。

## 使用方式

1. 在「連線設定」選擇 Microsoft Decision 1.1 或 Azure OpenAI Decisions，填入 Azure 公有雲資源端點、實際部署名稱及金鑰。
2. 填入 Content Understanding 資源端點與金鑰。全文辨識使用內建 OCR 分析器，可直接開始掃描。
3. 點「即時辨識」並允許相機權限。將文件放在取景框中央，黃框表示需調整距離，綠框表示面積合適。兩色皆使用深色外框與四角圓點。邊框會隨文件移動，可切換橫框或直框。文件四角完整、占框內面積至少 60%、文字清晰且連續兩次位置穩定時會自動裁切並校正透視。也可點「從相簿選取」。即時辨識預設自動把裁切影像送往 Azure，可勾選「送出前先預覽裁切影像」調整這次的設定。相簿選圖則維持預覽後按「開始辨識」。預設會依序完成分類與 OCR。勾選「讀取前讓我確認分類」即可在兩步之間更正類型。若分類失敗，保留同張照片，點「手動選擇類型」後按「讀取文字」即可繼續。
4. 結果頁上方固定顯示「欄位結果」，下方是預設收合的「辨識原文（JSON）」。健保卡只讀全文時也能依卡面排列整理姓名、身分證／居留證號、出生日期及完整卡號。展開「辨識原文（JSON）」可查看文件類型、欄位及逐行 OCR 原文，點「複製 JSON」可複製完整內容。若無可辨識的欄位，上方顯示提示。
5. 在首頁的「辨識紀錄」可開啟歷史結果。同一套件及簽署金鑰覆蓋安裝 APK 時會沿用紀錄。1.2.4 及之前的版本僅暫存於記憶體，目前無可遷移的歷史檔案。
6. 如要先解除安裝，先在首頁點「匯出備份」，選擇保存位置。備份是明文 JSON，含原文、當時的欄位規則及擷取結果。完成重裝後點「匯入備份」，可合併還原並略過重複紀錄。系統若在解除安裝時提供「保留應用程式資料」，也可以勾選。

身分證正面會讀取卡面印出的性別與右下角統一編號，必要時以手機 OCR 放大補讀。身分證背面會擷取 Azure 傳回的左下角一維條碼，並在手機上從條紋解碼。背面的結果與 JSON 用單一「條碼」欄顯示完整解碼原文，保留字母與前導零。舊紀錄中已解出的條碼也會套用這個欄名。右下角十碼印刷流水號另外讀取，保留前導零。「役別」即使沒有讀到文字也會保留欄名與空白值，JSON 對應空字串。條碼無法讀取時顯示「未讀取」，JSON 為 null。讀取成功的值會一併出現在欄位、JSON 與辨識紀錄。舊紀錄可依先前的文字補顯性別，缺少條碼時，可在同一筆紀錄按「補掃左下角條碼」，近拍同一張證件的條碼與兩側留白。手機連續兩次讀到相同內容便會更新該筆紀錄，無須再次上傳或重算雲端費用。

## 辨識用量與費用

首頁每筆紀錄列出 Token、API 耗時與美元估價。結果頁保留費用總額，點「Token 用量」或「辨識時間」旁的箭頭即可分別展開 Decisions 分類與 Content Understanding 讀取的明細。Token 明細列出模型回報的用量、頁數與費用。辨識時間是兩組 API 耗時的合計，從各自送出請求計至收到最終回應，包含等待 Content Understanding 分析完成的輪詢時間。重試按次列出。圖片準備、建立分析器、手動確認、手機端 OCR 與條碼補掃均不計入。舊紀錄保留當時的處理時間，新紀錄的分項耗時也會隨備份保存。

費用採 2026-11-22 查核的 Global Standard 美元參考價。Microsoft Decision 1.1、Azure OpenAI Decisions、OCR 頁數、情境 token、GPT-5.2 與 text-embedding-3-large 依各自的計價項目計算。只讀一張照片且 Azure 缺少頁數時，依當次 Read 或 Layout 估算一頁並標示。缺少用量或模型單價時顯示已知小計。部署在不同區域或有議價折扣時，可於「連線設定 → 估算費用單價」填入模型費率。實際扣款以 Azure 帳單為準。

[Microsoft Decision 1.1 費率](https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/introducing-microsoft-decision-1-1-build-faster-more-efficient-ai-applications-w/4573877) · [Azure OpenAI Decisions 費率](https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/azure-openai-decisions-api-now-generally-available-in-microsoft-foundry/4567265) · [Content Understanding 計價](https://learn.microsoft.com/azure/ai-services/content-understanding/pricing-explainer)

## 依描述設定辨識規則

即時辨識會以 ML Kit 偵測的文字位置配合四邊輪廓估計文件範圍，再用文件內文字區域的銳利度和連續畫面的四角位移判斷是否截取。

即時辨識和相簿選取的樣本照片都會正常分類、讀取文字並儲存。欄位、展開的 JSON 與複製的 JSON 會略過「樣本」兩字，也支援兩字間有空格或換行。既有紀錄開啟後會套用同一顯示規則，資料庫與備份保留辨識原始資料。首次使用即時辨識時需連網，讓 Google Play 服務下載中文辨識模型。

在首頁點「辨識規則」，選擇內建類型或「新增自訂類型」。11 個內建類型已預填分類描述與共 72 個欄位的讀取說明。規則清單可預覽分類描述，點入後可修改。新增自訂類型時，可按「帶入內建範例」複製一份分類描述與欄位，再為新類型命名及調整。已自行儲存的類型優先使用手機上的版本，點「還原此類型的預設規則」可取代該類型的自訂內容。每個類型可設定：

- **分類描述**：填寫這個類型獨有的標題、版面及正反面特徵。兩種 Decisions 服務均會使用儲存後的描述。
- **欄位名稱與讀取描述**：可以新增、修改或移除欄位。例如「健保卡卡號」可描述為「讀取卡片下方的 12 碼卡號，保留開頭的 0」。
- **自動讀取欄位**：勾選「辨識為此類型時，自動讀取設定的欄位」後儲存。下次掃描此類型會一併顯示全文與具名欄位。每次掃描也可以在影像確認頁單獨選擇「全文及已設定的欄位」或「只讀全文」。

五種證件面別已依樣本的印字與版面設定規則。例如身分證正面將同一行的發證日期、地名及領補換類別分開讀取，健保卡從三組四碼讀取完整卡號。駕照正面預設 12 欄，分開讀取出生、有效、發照與審驗日期。反面預設 16 欄，分別讀取印製號碼、動員編號、A／B 條件說明、C 加註、三列住址變更、兩列職業駕駛審驗、各列登記機關章戳及備註。辨識到對應的格線版面時，依文字位置對欄並用手機 OCR 補讀。結果依規則順序逐欄顯示，空值在 JSON 是空字串。升級時，僅改過分類描述或自動讀取開關的駕照規則會套用新版欄位。自行編輯過的欄位與舊辨識紀錄則沿用原設定。

規則儲存在手機。內建類型可還原預設，自訂類型可刪除。修改欄位的讀取描述後，下次執行欄位擷取會選用符合新規則的 Azure 分析器。只變更分類描述時可沿用原分析器。

| 已選的讀取方式 | Content Understanding 分析器 |
| --- | --- |
| 收據或表單的全文 | prebuilt-layout |
| 其他類型的全文，含無法歸類的影像 | prebuilt-read |
| 全文及已設定的欄位 | 使用該類型的自訂分析器，一次讀取全文與欄位；未設定欄位的類型只讀全文 |

自訂欄位需要在 Content Understanding 設定文字生成模型（例如 gpt-5.2）及向量模型 text-embedding-3-large 的部署對應，並允許建立分析器。App 的 completion 欄填入文字生成模型的名稱。首次擷取新版欄位時會使用與其描述相符的分析器。

## 資料處理

App 支援系統截圖。即時辨識會在裁切成功後上傳。若選擇先預覽或使用相簿，則於按下「開始辨識」後上傳。縮圖傳送至 Decisions，重新編碼的影像傳送至 Content Understanding。辨識紀錄儲存在 App 私有資料庫，包含原文、欄位結果及辨識時的類型設定。照片和 Azure 金鑰不寫入辨識紀錄或匯出備份。首頁的「清除所有紀錄」可刪除本機歷史。相機暫存檔於載入後刪除，連線設定由 Android Keystore 提供的 AES-GCM 金鑰加密，停用系統自動備份。

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

- [ZXing 條碼解碼器](https://github.com/zxing/zxing)

- [ML Kit Android 中文文字辨識](https://developers.google.com/ml-kit/vision/text-recognition/v2/android?hl=zh-tw)

- [Microsoft Decision 1.1](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-microsoft-decision)
- [Azure OpenAI Decisions](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/decisions)
- [Content Understanding 預建分析器](https://learn.microsoft.com/en-us/azure/ai-services/content-understanding/concepts/prebuilt-analyzers)
