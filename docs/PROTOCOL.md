# VanillaShape 同步協定 v3

通道：`vanillashape:sync`

所有整數與浮點數均使用 Java `DataOutputStream` 的 big-endian 格式。每個 payload 開頭為：

| 欄位 | 型別 | 說明 |
|---|---|---|
| version | u8 | 目前為 `3` |
| action | u8 | 動作編號 |

字串格式為 `u16 byteLength + UTF-8 bytes`。解碼器會拒絕版本不符、未知 enum、截斷字串、非有限命中座標、超出 `0..1` 的命中座標，以及動作資料後的多餘 bytes。

## 動作

| 編號 | 名稱 | 方向 | 資料 |
|---:|---|---|---|
| 1 | `HELLO` | Fabric → Paper | 無 |
| 2 | `RESET` | Paper → Fabric | world |
| 3 | `UPSERT` | Paper → Fabric | 完整特殊方塊 |
| 4 | `REMOVE` | Paper → Fabric | world + x/y/z |
| 5 | `DEBUG_SELECT` | Fabric → Paper | x/y/z + reverse |
| 6 | `DEBUG_CYCLE` | Fabric → Paper | x/y/z + reverse |
| 7 | `PLACE_ITEM` | Fabric → Paper | 放置內容 |
| 8 | `PICK_ITEM` | Fabric → Paper | x/y/z + 保留 boolean |
| 9 | `AXIOM_PLACE` | Fabric → Paper | 放置內容 |
| 10 | `AXIOM_REPLACE` | Fabric → Paper | x/y/z + 保留 boolean |
| 11 | `AXIOM_DELETE` | Fabric → Paper | x/y/z + 保留 boolean |

## 流程

1. Fabric 客戶端在 play connection 建立後送出 `HELLO`。
2. Paper 對該玩家目前世界送出一個 `RESET`，接著逐筆送出 `UPSERT`。
3. 編輯特殊方塊時，Paper 對同世界的線上玩家廣播 `UPSERT` 或 `REMOVE`。
4. 玩家切換世界時，Paper 重複 `RESET + UPSERT`。
5. `DEBUG_SELECT` / `DEBUG_CYCLE`、物品放置與 Axiom 動作只送出操作意圖；Paper 重新驗證距離、世界、權限與主手物品後才寫入。

## UPSERT 資料

| 欄位 | 型別 |
|---|---|
| world | string |
| x, y, z | i32 × 3 |
| shape | u8 enum ordinal |
| material BlockData | string |
| facing | u8 enum ordinal |
| corner | u8 enum ordinal |
| flags | i32 bitset |

## 放置資料

`PLACE_ITEM` 與 `AXIOM_PLACE` 共用以下格式：

| 欄位 | 型別 | 說明 |
|---|---|---|
| x, y, z | i32 × 3 | 要建立特殊方塊的目標格 |
| face | u8 enum ordinal | 被點擊的支撐面：N/E/S/W/U/D |
| hitX, hitY, hitZ | f32 × 3 | 相對於支撐方塊的命中位置，皆須位於 `0..1` |

Paper 使用 face 與命中位置決定直立半磚佔據哪一半。側面放置會靠向支撐方塊；頂面或底面依命中位置選半，中央區域使用 Paper 上的玩家 yaw。方向由伺服器重新判定，不能由客戶端直接指定結果。

enum ordinal 與 flags 的權威定義位於 `common` 子專案。協定 v3 不向下相容，Paper 與 Fabric 必須一起更新。
