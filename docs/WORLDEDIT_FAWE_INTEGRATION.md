# WorldEdit / FastAsyncWorldEdit 整合

實作版本：VanillaShape 0.4.0（2026-08-27）。目前以 WorldEdit 7.4.5、FastAsyncWorldEdit 2.15.4、Paper 26.2 驗證。

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

只寫 ID 會使用石頭材質與該形狀的預設狀態。標準 WorldEdit 可用 SNBT 指定材質與狀態：

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

Backing world 始終保持空氣。若操作把一般方塊寫到同一格，該一般方塊才會真正進入 Paper 世界。

只枚舉 Minecraft 原生 `BlockType.REGISTRY`、完全不呼叫 WorldEdit parser 的第三方 GUI 仍無法顯示這些虛擬 ID。VanillaShape 不會偽造不存在的 Minecraft internal state ID，因為這會破壞 WorldEdit platform adapter 與 FAWE 的固定 state palette。

## 實作架構

整合由四層構成：

1. `VanillaShapeBlockParser` 將 `vanillashape:*` 字串轉成原版形狀 proxy 加 VanillaShape NBT，並提供 suggestions。
2. `VanillaShapeMaskParser` 直接查詢 `BlockService`，使 shape-only 與精確狀態 mask 不受 backing air 影響；FAWE 另以 `AliasedParser` 接上 rich-mask 路由。
3. `VanillaShapeExtent` 在 `BEFORE_CHANGE`／`BEFORE_HISTORY` 暴露、攔截 proxy；寫入普通方塊時刪除虛擬記錄，寫入 proxy 時把 backing world 改回 air。
4. `VanillaShapeClipboard` 保留 copy/cut 與 schematic 的 proxy NBT。FAWE 會隱藏非容器 carrier 的 tile NBT，因此載入後會從其 tile map 復原 marker。

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

每個 extent 累積座標變更，commit 時呼叫 `BlockRepository.applyBatch`。SQLite connection、世界快取與 mutation 都有同步邊界；Fabric 的增量訊息若由 FAWE worker 觸發，會合併成一個主執行緒 task 廣播。

## ItemsAdder-WorldEdit 1.1.3 研究來源

使用者提供的 JAR SHA-256：

```text
2cd88b02979f27cbeb2d9b240455979288b988b10024e19ad0e38277040c9c31
```

拆包確認該插件同樣採用 custom parser、NBT carrier、`BEFORE_CHANGE` extent 與 FAWE allow-list；這驗證了入口選擇。但 ItemsAdder 的世界本身有 carrier，VanillaShape 是 backing air，因此本實作額外提供讀取代理、正式 mask parser、clipboard wrapper、history NBT 合併與 SQLite transaction batch，而沒有照用其逐格 Bukkit task。

研究依據：

- [WorldEdit 原始碼](https://github.com/EngineHub/WorldEdit)
- [WorldEdit EditSession / extent 文件](https://worldedit.enginehub.org/en/latest/api/concepts/edit-sessions/)
- [FastAsyncWorldEdit 原始碼](https://github.com/IntellectualSites/FastAsyncWorldEdit)
- [FAWE API 使用文件](https://github.com/IntellectualSites/documentation/blob/main/fastasyncworldedit/API/api-usage.md)

## 驗證

在乾淨 Paper 26.2 測試伺服器分別載入 WorldEdit 7.4.5 與 FAWE 2.15.4，驗證：parser、shape/exact mask、replace、普通方塊覆寫、undo/redo、copy/paste、90 度旋轉、Sponge v3 schematic round-trip，以及 backing air。FAWE 額外從背景執行緒建立及清除 1,000 格精確狀態，確認批次提交與 client broadcast 不需要從 worker 直接呼叫 Bukkit world API。
