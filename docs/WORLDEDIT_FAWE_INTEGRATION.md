# WorldEdit / FastAsyncWorldEdit 整合

實作版本：VanillaShape 0.6.0（2026-08-28）。目前以 WorldEdit 7.4.5、FastAsyncWorldEdit 2.15.4、Paper 26.2 驗證。

## 使用方式

Paper 端安裝 WorldEdit 或 FAWE 後，VanillaShape 會透過 `softdepend` 自動啟用整合。使用者需要 `vanillashape.worldedit`；九個 shape ID 會由 WorldEdit 的 block parser 提供參數補全：

```text
vanillashape:wall
vanillashape:fence
vanillashape:fence_gate
vanillashape:slab
vanillashape:stairs
vanillashape:door
vanillashape:trapdoor
vanillashape:vertical_slab
vanillashape:model
```

`model` 會把任意原版 BlockData 的 baked model 當作幾何，例如按鈕、藤蔓、告示牌、草、珊瑚與壓力板：

```text
//set vanillashape:model{model:"minecraft:oak_button[face=wall,facing=east,powered=false]",material:"minecraft:glass"}
//set vanillashape:model{model:"minecraft:vine[north=true]",material:"minecraft:warped_planks"}
```

只寫 ID 時，會使用主手非空氣方塊物品的完整 BlockData 與該形狀的預設狀態。因此可先拿著想要的材質，再直接輸入 `//set vanillashape:wall`；SNBT 的 `material` 會覆寫主手材質。主控台、指令方塊與空手玩家必須明寫 `material:"minecraft:..."`，不會以石頭替代。`vanillashape:model` 一律要求 `model:"..."`。標準 WorldEdit 可用 SNBT 指定材質與狀態：

```text
//set vanillashape:vertical_slab{material:"minecraft:oak_log[axis=x]",facing:"east",corner:"inner_left",waterlogged:1b}
//replace vanillashape:vertical_slab vanillashape:stairs{material:"minecraft:deepslate",facing:"west",half:"top"}
```

FAWE 的 rich parser 會解讀 BlockData 內的中括號，因此完整狀態必須用 `[{...}]` 包裝：

```text
//set vanillashape:vertical_slab[{material:"minecraft:oak_log[axis=x]",facing:"east"}]
//replace vanillashape:vertical_slab[{material:"minecraft:oak_log[axis=x]"}] minecraft:stone
```

shape-only mask 會匹配該 shape 的任何材質與狀態；帶狀態的 mask 只比較明確指定的欄位。`material` 接受完整原版 BlockData，`flags` 接受已知 bitset，其他名稱由該 shape 的 `StateSchema` 驗證。

## 支援範圍

- `//set`、以 VanillaShape 作為 pattern 的區域命令。
- shape-only 或指定欄位的 mask，以及 `//replace`。
- 一般方塊覆寫特殊方塊，會同時移除 SQLite 記錄。
- `//undo`／`//redo` 保存完整 shape、material、facing、corner 與 flags。
- 一般 `//copy`／`//cut`／`//paste`，包含 `-m` source mask。
- `//rotate`／`//flip` 透過可轉換的原版 proxy state 改變 facing、half、corner、hinge 與連接方向。
- Sponge v3 schematic save/load round-trip。
- FAWE 背景執行緒與 bulk overload；一次 EditSession 以單一 SQLite transaction 提交，不會每格排一個 Bukkit task。
- 對虛擬方塊使用選區棒、遠距選區棒、replacer、cycler、stacker、query、brush、navigation、super-pickaxe，以及其他實作 WorldEdit block/trace tool 介面的工具。

Backing world 始終保持空氣。若操作把一般方塊寫到同一格，該一般方塊才會真正進入 Paper 世界。

只枚舉 Minecraft 原生 `BlockType.REGISTRY`、完全不呼叫 WorldEdit parser 的第三方 GUI 仍無法顯示這些虛擬 ID。VanillaShape 不會偽造不存在的 Minecraft internal state ID，因為這會破壞 WorldEdit platform adapter 與 FAWE 的固定 state palette。

## 實作架構

整合由六層構成：

1. `VanillaShapeBlockParser` 將 `vanillashape:*` 字串轉成原版形狀 proxy 加 VanillaShape NBT，並提供 suggestions。
2. `VanillaShapeMaskParser` 直接查詢 `BlockService`，使 shape-only 與精確狀態 mask 不受 backing air 影響；FAWE 另以 `AliasedParser` 接上 rich-mask 路由。
3. `VanillaShapeExtent` 在 `BEFORE_CHANGE`／`BEFORE_HISTORY` 暴露、攔截 proxy；寫入普通方塊時刪除虛擬記錄，寫入 proxy 時把 backing world 改回 air。
4. `VanillaShapeClipboard` 保留 copy/cut 與 schematic 的 proxy NBT。FAWE 會隱藏非容器 carrier 的 tile NBT，因此載入後會從其 tile map 復原 marker。Bukkit 的 `PlayerCommandPreprocessEvent` 在 WorldEdit fallback listener 執行前保存選區；監看器只在本次命令建立不同的新 clipboard 後包裝 snapshot，`//paste`／`//place` 則在命令消費 clipboard 前做同步補救。`VanillaShapeClipboardHolder` 會接管 FAWE 磁碟 clipboard 的生命週期，避免 holder 替換時提前 unmap，並在 clipboard 真正清除時釋放資源。
5. Fabric 對空氣 backing 執行自己的精確幾何 raycast，並以原版 block outline raycast 遮擋遠處目標，再將虛擬方塊、命中面與局部命中位置送至 Paper。一般 block tool 維持 10 格伺服器驗證；有權限且已綁定的 trace tool 最遠可使用 512 格候選目標。
6. 射線型工具需要從 Bukkit 世界 trace 時，`VirtualTargetPlayer` 只代理 `getBlockTrace*`／`getSolidBlockTrace` 的結果，其餘 actor、session、permission 與 FAWE async queue 行為仍使用原始 WorldEdit player。代理會檢查工具實際傳入的 range；超出 brush／far-wand 自身設定時回傳無命中。相同工具、相同虛擬座標與命中面的快速重複右鍵會被去抖；工具身分也是 key 的一部分，因此切換工具不會誤吞第一次操作。

Proxy NBT 的核心欄位：

```text
id: "vanillashape:proxy"
version: 2
shape: "vertical_slab"
material: "minecraft:oak_log[axis=x]"
model: ""
facing: "east"
corner: "straight"
flags: 0
```

proxy 只存在於 parser、extent、history、clipboard 與 schematic 中，不會寫入 Minecraft 世界。方向性狀態同時映射到相近的原版 BlockState：

| VanillaShape | Proxy |
|---|---|
| stairs / vertical_slab | oak_stairs |
| slab | oak_slab |
| wall | cobblestone_wall |
| fence | oak_fence |
| fence_gate | oak_fence_gate |
| door | oak_door |
| trapdoor | oak_trapdoor |
| model | `model` 欄位指定的真正原版 BlockState |

這讓 WorldEdit 的既有 transform 能正確處理旋轉與鏡像；解碼時材質取自 marker，可轉換狀態則以 transform 後的 BlockState 為準。`model` 使用本身的原版狀態作 carrier，因此按鈕、告示牌、藤蔓等方向屬性也能隨 `//rotate`／`//flip` 轉換。

## FAWE 處理

FAWE 啟用時，VanillaShape 會在執行期將精確的 extent 類名加入 `Settings.EXTENT.ALLOWED_PLUGINS`，停用時移除。FAWE 的 `HISTORY.COMBINE_STAGES` 會在記憶體中暫時關閉，確保 state 與 NBT 不會被拆散；插件停用時恢復原值，不會改寫 FAWE 設定檔。

每個 extent 累積座標變更，commit 時呼叫 `BlockRepository.applyBatch`。SQLite connection、世界快取與 mutation 都有同步邊界；Fabric 的增量訊息若由 FAWE worker 觸發，會合併成一個主執行緒 task 廣播。clipboard 監看最長為 60 秒；較新的 copy/load 指令會使較舊 generation 失效，避免交錯完成時把錯誤選區資料附到較新的 clipboard。FAWE 工具仍透過其 actor keyed async queue 執行，不會從 plugin-message thread 直接改動 EditSession。

## ItemsAdder-WorldEdit 1.1.3 研究來源

使用者提供的 JAR SHA-256：

```text
2cd88b02979f27cbeb2d9b240455979288b988b10024e19ad0e38277040c9c31
```

拆包確認該插件同樣採用 custom parser、NBT carrier、`BEFORE_CHANGE` extent 與 FAWE allow-list；這驗證了入口選擇。但 ItemsAdder 的世界本身有 carrier，VanillaShape 是 backing air，因此本實作額外提供讀取代理、正式 mask parser、clipboard wrapper、history NBT 合併與 SQLite transaction batch，而沒有照用其逐格 Bukkit task。

研究依據：

- [WorldEdit fallback command listener](https://github.com/EngineHub/WorldEdit/blob/version/7.4.x/worldedit-bukkit/src/main/java/com/sk89q/bukkit/util/FallbackRegistrationListener.java)
- [WorldEdit Bukkit 點擊分派](https://github.com/EngineHub/WorldEdit/blob/version/7.4.x/worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditListener.java)
- [WorldEdit EditSession / extent 文件](https://worldedit.enginehub.org/en/latest/api/concepts/edit-sessions/)
- [FAWE 2.15.4 block/trace tool 分派](https://github.com/IntellectualSites/FastAsyncWorldEdit/blob/2.15.4/worldedit-core/src/main/java/com/sk89q/worldedit/extension/platform/PlatformManager.java)
- [FAWE 2.15.4 clipboard commands](https://github.com/IntellectualSites/FastAsyncWorldEdit/blob/2.15.4/worldedit-core/src/main/java/com/sk89q/worldedit/command/ClipboardCommands.java)
- [FAWE API 使用文件](https://github.com/IntellectualSites/documentation/blob/main/fastasyncworldedit/API/api-usage.md)

## 驗證

在乾淨 Paper 26.2 測試伺服器分別載入 WorldEdit 7.4.5 與 FAWE 2.15.4，驗證：parser、shape/exact mask、replace、普通方塊覆寫、undo/redo、90 度旋轉、Sponge v3 schematic round-trip，以及 backing air。另以模擬真實 Bukkit 玩家依序觸發 `PlayerCommandPreprocessEvent` 與兩套插件自己的 command manager，驗證 `//copy` 完成後同 tick 立即 `//paste`，目的座標仍是 VanillaShape 記錄且底層為空氣；同一測試也涵蓋 selection wand 的左右鍵與強制命中虛擬座標的 trace tool。FAWE 額外從背景執行緒建立及清除 1,000 格精確狀態，確認批次提交與 client broadcast 不需要從 worker 直接呼叫 Bukkit world API。
