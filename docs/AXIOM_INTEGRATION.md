# Axiom 整合

實作版本：VanillaShape 0.7.0（2026-08-30）。研究基準為 Axiom 5.5.0 內附的 AxiomClientAPI 2.0.1，以及 AxiomPaperPlugin 26.2 原始碼；Axiom 整合因此只在 26.2 啟用，多版本成品的其餘功能不依賴 Axiom。

## 公開 API 邊界

AxiomClientAPI 的 `CustomTool` 只提供工具啟用期間的 right-click、confirm、delete、render 與 options callback；`ToolService` 提供原版 raycast、目前方塊、region change 與 render override。公開 API 沒有讀寫 Axiom 內建 clipboard、附加第三方每座標 payload，或訂閱內建 copy/paste 完成事件的介面。

AxiomPaper 的 custom-block registry 也不能直接表示 VanillaShape。該 API 將每個 custom state 映射到一個唯一、已存在的 Minecraft `BlockState`，並拒絕兩個 custom state 共用 carrier。VanillaShape 的資料是每個座標上任意的 `shape × material BlockData × model BlockData × flags`，且 backing world 必須保持空氣；使用有限的原版 state palette 會遺失資料或把 carrier 留在世界。

因此 0.7.0 不依賴 Axiom 5.5.0 的閉源內部 `Selection`／`Clipboard` 類別，也不修改使用者的 Axiom JAR。整合只使用官方公開 API，另外提供一個 `VanillaShape Clipboard` CustomTool。

研究來源：

- [AxiomClientAPI `CustomTool`](https://github.com/Moulberry/AxiomClientAPI/blob/master/src/main/java/com/moulberry/axiomclientapi/CustomTool.java)
- [AxiomClientAPI `ToolService`](https://github.com/Moulberry/AxiomClientAPI/blob/master/src/main/java/com/moulberry/axiomclientapi/service/ToolService.java)
- [AxiomClientAPI `RegionProvider`](https://github.com/Moulberry/AxiomClientAPI/blob/master/src/main/java/com/moulberry/axiomclientapi/service/RegionProvider.java)
- [AxiomPaper custom-block 實作](https://github.com/Moulberry/AxiomPaperPlugin/blob/master/src/main/java/com/moulberry/axiom/paperapi/block/ImplServerCustomBlocks.java)

## 操作流程

1. 選擇 `VanillaShape Clipboard`。
2. 右鍵兩個方塊格作為選區對角，Enter 複製。
3. 右鍵表面選擇相鄰的貼上原點，Enter 貼上。
4. 可重複選擇原點並貼上；Delete 清除目前 selection/clipboard。

Client 只送選區座標。Paper 會從權威 `BlockService` 擷取完整記錄，按玩家 UUID 保存 clipboard；貼上封包也只含目的原點，不能由 client 偽造任意方塊內容。貼上保留完整狀態，以一次 SQLite transaction 寫入並增量同步到同世界 Fabric clients。來源選區內沒有 VanillaShape 記錄的位置代表虛擬空氣，因此會清除目的區域對應的既有 VanillaShape 記錄。

安全限制：

- `vanillashape.axiom`，以及存在 AxiomPaper 時的 `axiom.editor.use`／`axiom.build.place`。
- 最大 16,777,216 格選區體積及 100,000 個 VanillaShape 記錄。
- 驗證世界高度、world border、整數座標溢位。
- 需要建立特殊方塊的目的格若有真實原版方塊會拒絕，不會覆寫世界或留下 carrier。

## 限制

公開 API 無法把這份 sidecar 資料合併到 Axiom 內建 clipboard，因此 VanillaShape 與同區域的真實原版方塊目前是兩份剪貼簿操作；VanillaShape paste 也不會進入 Axiom 內建 undo stack。這是避免依賴閉源、未承諾相容性的內部類別所做的明確邊界。
