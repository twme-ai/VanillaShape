# WorldEdit / FastAsyncWorldEdit 整合研究

研究日期：2026-08-27。目標是讓標準 WorldEdit / FastAsyncWorldEdit 指令能看到、補全、放置、取代、複製、旋轉、儲存與復原 VanillaShape 特殊方塊，同時維持 Paper 世界中的 backing block 為空氣。

研究來源：

- [WorldEdit 原始碼](https://github.com/EngineHub/WorldEdit)
- [WorldEdit EditSession / extent 文件](https://worldedit.enginehub.org/en/latest/api/concepts/edit-sessions/)
- [FastAsyncWorldEdit 原始碼](https://github.com/IntellectualSites/FastAsyncWorldEdit)
- [FAWE API 使用文件](https://github.com/IntellectualSites/documentation/blob/main/fastasyncworldedit/API/api-usage.md)
- 使用者提供的 `ItemsAdder-WorldEdit_1.1.3.jar`

提供的 JAR SHA-256：

```text
2cd88b02979f27cbeb2d9b240455979288b988b10024e19ad0e38277040c9c31
```

## 結論

不能安全地向 `BlockType.REGISTRY` 註冊假的 `vanillashape:*` 方塊。WorldEdit 的 `BlockType` 仍需要平台提供真實 Minecraft BlockState 與 internal ID；FAWE 又會在啟動時建立固定大小的 `BlockTypesCache` 狀態陣列。事後加入偽造 ID 不會取得有效狀態，且可能破壞 FAWE 的陣列與 palette 假設。

正確方向與 ItemsAdder-WorldEdit 相同：

1. 向 `WorldEdit.getInstance().getBlockFactory()` 註冊自訂 `InputParser<BaseBlock>`。
2. parser 的 suggestions 提供八個 `vanillashape:*` 名稱，讓標準 WorldEdit 參數補全能列出它們。
3. parser 將特殊方塊編碼成「有相近狀態欄位的原版 proxy BlockState + VanillaShape NBT」。
4. 監聽 `EditSessionEvent`，以自訂 extent 攔截讀寫，將 proxy 與 VanillaShape SQLite 記錄互相轉換。
5. FAWE 模式必須使用相容的整合類、允許該 extent，並批次處理非同步變更。

這能讓 `//set vanillashape:vertical_slab` 等標準方塊參數出現在補全並可實際工作；但它不會、也不應讓 `BlockType.REGISTRY` 誤以為伺服器真的註冊了該 Minecraft 方塊。任何只直接枚舉原生 `BlockType` 的第三方 UI 仍需另外的 UI adapter。

## ItemsAdder-WorldEdit 1.1.3 拆包結果

JAR 內只有九個 class 與 `plugin.yml`，主要結構如下：

```text
CustomBlocksInputParser
AbstractCustomBlocksDelegate
├── WeCustomBlocksDelegate
└── FaweCustomBlocksDelegate
WorldEditListener
FaweListener
Main / bootstrap
```

實際行為：

- `CustomBlocksInputParser#getSuggestions` 從 ItemsAdder API 取得 ID。
- `parseFromInput` 選擇 NOTE_BLOCK、mushroom blocks、CHORUS_PLANT、TRIPWIRE 或 SPAWNER 等 ItemsAdder carrier，並在 BaseBlock NBT 寫入 `IABlock=<namespaced id>`。
- `WorldEditListener` 與 `FaweListener` 都在 `Stage.BEFORE_CHANGE` 注入 `AbstractDelegateExtent`。
- delegate 在寫入前移除舊 ItemsAdder custom block，遇到 `IABlock` marker 或能對應 ItemsAdder carrier 的狀態時改呼叫 `ItemsAdder.placeCustomBlock`。
- FAWE 版本覆寫 int x/y/z 的 `setBlock` overload，並用 Bukkit scheduler 把 ItemsAdder API 呼叫排回主執行緒。
- bootstrap 直接把兩個 delegate 的 canonical class name 加進 FAWE `Settings.settings().EXTENT.ALLOWED_PLUGINS`。
- `//replace` 與 `//replacenear` 另以 `PlayerCommandPreprocessEvent` 將 ItemsAdder ID 改成 carrier BlockData，避開舊版 mask 無法理解 NBT 的問題。

這個案例證明 parser + proxy + extent + FAWE allow-list 是已被實際採用的整合模型。

## 不能直接照抄的部分

ItemsAdder 的世界中存在可辨識的原版 carrier，而 VanillaShape 的位置保持空氣，兩者資料模型不同：

| 情況 | ItemsAdder-WorldEdit | VanillaShape 所需行為 |
|---|---|---|
| 讀取既有 custom block | 從世界 carrier 狀態反推 ItemsAdder ID | 從 `BlockService` / SQLite 查詢，再回傳 proxy BaseBlock |
| 寫入 custom block | ItemsAdder API 建立實體 carrier | SQLite 建立特殊記錄，世界仍維持空氣 |
| 寫入一般方塊 | 移除 ItemsAdder block 後交給 WE | 移除特殊記錄後把一般方塊交給 WE |
| copy / schematic | carrier 本身會被讀到 | 必須覆寫 `getFullBlock`，否則只會複製空氣 |
| mask / replace | 以指令字串替換成 carrier | 應提供正式 custom mask parser，不能竄改玩家指令 |
| FAWE 大量操作 | 每格排一個 Bukkit task | 必須按 edit/chunk 批次，否則大量方塊會塞爆 scheduler 與 SQLite |

此外，1.1.3 是 Java 8 時代的舊實作，反射呼叫已淘汰的 `BaseBlock(BlockState, CompoundTag)` 建構子。現行 WorldEdit 應使用 `BlockState#toBaseBlock(LazyReference<LinCompoundTag>)` 與 LinBus NBT，不應保留這段反射。

## 建議的方塊字串

基礎補全列出：

```text
vanillashape:wall
vanillashape:fence
vanillashape:fence_gate
vanillashape:slab
vanillashape:stairs
vanillashape:door
vanillashape:trapdoor
vanillashape:vertical_slab
```

省略資料時使用石頭、north、straight、flags=0。完整狀態可用 SNBT，避免 material BlockData 自身的中括號與 WorldEdit property parser 衝突：

```text
vanillashape:vertical_slab{material:"minecraft:oak_log[axis=x]",facing:"east",corner:"straight",flags:0}
```

parser 只接受已知 shape、合法 Minecraft BlockData、四向 facing、合法 corner 與已知 flags；所有錯誤應回傳明確 `InputParseException`。

## Proxy codec

proxy 只存在於 WorldEdit pipeline、clipboard、history 與 schematic，永遠不能真正寫進 Paper 世界。NBT 至少保存：

```text
id: "vanillashape:proxy"
version: 1
shape: "vertical_slab"
material: "minecraft:oak_log[axis=x]"
flags: 0
```

方向與可變幾何同時映射到相近的原版 BlockState，讓 WorldEdit 的 `BlockTransformExtent` 在 `//rotate` / `//flip` 時自動轉換 facing、half、shape、hinge 與連接欄位：

| VanillaShape | 建議 proxy state |
|---|---|
| stairs / vertical_slab | oak_stairs |
| slab | oak_slab |
| wall | cobblestone_wall |
| fence | oak_fence |
| fence_gate | oak_fence_gate |
| door | oak_door |
| trapdoor | oak_trapdoor |

解碼時 material 取自 NBT；方向、corner 與可被 transform 的 flags 則以已轉換後的 proxy BlockState 為準。這樣旋轉不需要猜測 clipboard transform。

proxy state 帶非原生 block-entity NBT 是整合層內的 marker。extent 必須攔住所有寫入；Sponge schematic v2/v3 的跨版本 round-trip 也必須列為相容性測試，確認 reader 不會丟棄 marker。

## Extent 的讀寫規則

WorldEdit 官方文件把 extent stack 分成 `BEFORE_CHANGE`、`BEFORE_REORDER`、`BEFORE_HISTORY`。核心代理至少需要覆寫：

- `getBlock`：既有特殊方塊回傳 proxy immutable state，否則 delegate。
- `getFullBlock`：既有特殊方塊回傳含完整 NBT 的 proxy BaseBlock，否則 delegate。
- `setBlock(BlockVector3, holder)`：WorldEdit 路徑。
- `setBlock(int, int, int, holder)`：FAWE 熱路徑。
- 任何會整區直接 delegate、繞過逐格 `setBlock` 的 bulk overload，或改以 FAWE batch processor 接管。

寫入矩陣：

| 目前 SQLite | 輸入 | 結果 |
|---|---|---|
| 任意 | VanillaShape proxy | upsert SQLite；world 保持 air |
| 有特殊記錄 | air | 移除 SQLite；world 設 air |
| 有特殊記錄 | 一般方塊 | 移除 SQLite；一般方塊交給下層 extent |
| 無特殊記錄 | 一般方塊 | 原樣交給下層 extent |

舊值必須由 `getFullBlock` 以 proxy 暴露給 history，新的 proxy 又必須能由 `setBlock` 解碼。這才會讓標準 `//undo` / `//redo` 恢復 SQLite 記錄，而不是只對空氣做無效變更。`//copy` 與 schematic 同樣依賴這個讀取代理。

WorldEdit 原始碼建議 block interceptor 同時考慮 `BEFORE_CHANGE` 與 `BEFORE_HISTORY`。實作時應把底層 world/undo 保護與高層 pattern/mask 攔截分開，並避免同一 edit 被雙重套用。

## Mask、replace 與列表

只註冊 BlockFactory parser 足以讓 `//set`、pattern replacement 與大多數標準方塊參數看到 VanillaShape suggestions，但 exact filter 不能只用原版 `BlockMask`：它通常只比較 BlockState，不比較 VanillaShape NBT material。

因此還需要註冊 custom mask parser，例如：

```text
//replace =vanillashape:vertical_slab vanillashape:stairs
```

mask 直接查詢 proxy / `BlockService`，並可選擇只比 shape，或在帶 SNBT 時比完整 material、facing、corner、flags。不要採用 ItemsAdder-WorldEdit 攔截並重寫玩家 `//replace` 字串的做法；它容易破壞引號、expression、混合 pattern 與其他插件 parser。

## FAWE 的必要處理

FAWE 維持 WorldEdit API 相容性，但操作通常非同步且以 chunk queue 批次。VanillaShape 現有 `HashMap`、單一 SQLite connection、Bukkit world 查詢與 plugin-message broadcast 都不能直接從 FAWE worker thread 每格呼叫。

正式整合需：

1. 在偵測到 FAWE 時加入精確 integration package 到 `Settings.settings().EXTENT.ALLOWED_PLUGINS`，並在停用時移除；這與提供的 ItemsAdder 插件做法相同。
2. 每個 EditSession 使用 thread-confined mutation buffer，讀取時先看 buffer，再看一致的 `BlockService` snapshot。
3. edit commit / close 時將 mutation 依世界與 chunk 合併，在單一 SQLite transaction 中寫入。
4. 把需要 Bukkit world 的鄰接重算與 client broadcast 排回主執行緒，並合併重複座標。
5. 不得為每個方塊建立一個 Bukkit task；大量 `//set` 必須是一次或分片批次提交。
6. 確保 FAWE history 在 flush 完成前已取得舊 proxy，且 `EditSession#close` 後 VanillaShape transaction 也已完成。

ItemsAdder 1.1.3 每格 `runTask` 的方式只適合小量 carrier 操作，不能作為 VanillaShape 的效能基準。

## 實作順序與驗收

建議分三階段，避免先做出看似出現在列表、卻會破壞歷史的半套整合：

1. `WorldEditProxyCodec` + parser + suggestions，純單元測試八種 shape、完整 BlockData 與 rotate/flip round-trip。
2. 同步 WorldEdit extent，驗證 set/replace/copy/paste/schematic/undo/redo，並確認 backing world 永遠是 air。
3. FAWE mutation buffer + transaction/broadcast batch，對 1、1,000、100,000 格測試 commit、cancel、undo、伺服器重啟與同時編輯。

最低驗收表：

| 操作 | 必須成立 |
|---|---|
| tab completion | 八個 ID 可見 |
| `//set` | SQLite 正確、backing air |
| `//replace` | shape-only 與完整狀態 filter 都正確 |
| `//copy` / `//paste` | material、facing、corner、flags 保留 |
| `//rotate 90` / `//flip` | 方向與左右 inner/outer 正確轉換 |
| schematic save/load | 重啟後仍可 round-trip |
| `//undo` / `//redo` | SQLite 與客戶端畫面同步恢復 |
| 普通方塊覆蓋 | 特殊記錄移除，普通方塊實際落地 |
| FAWE bulk edit | 無 async Bukkit 呼叫、無逐格 scheduler task、可完整 flush |

在這些條件完成前，不應把 WorldEdit / FAWE 宣告為正式支援。
