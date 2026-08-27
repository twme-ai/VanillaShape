# VanillaShape 同步協定 v1

通道：`vanillashape:sync`

所有整數均使用 big-endian。每個 payload 開頭為：

| 欄位 | 型別 | 說明 |
|---|---|---|
| version | u8 | 目前為 `1` |
| action | u8 | `1=HELLO`、`2=RESET`、`3=UPSERT`、`4=REMOVE` |

字串格式為 `u16 byteLength + UTF-8 bytes`。

## 流程

1. Fabric 客戶端在 play connection 建立後送出 `HELLO`。
2. Paper 對該玩家目前世界送出一個 `RESET`，接著逐筆送出 `UPSERT`。
3. 編輯特殊方塊時，Paper 對同世界的線上玩家廣播 `UPSERT` 或 `REMOVE`。
4. 玩家切換世界時，Paper 重複 `RESET + UPSERT`。

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

enum ordinal 與 flags 的權威定義位於 `common` 子專案。協定版本不相符時必須拒絕訊息，不能猜測欄位。
