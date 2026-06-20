# Hotseat・QSB 完全除去 & Workspace 行数拡張

## 問題

Morrowa のホーム画面下部には Hotseat（ドック）と QSB（検索バー）が存在する。
Morrowa はドック不要のシンプルなランチャーを目指しており、この領域をアプリ配置に使いたい。

## 解決方針

- Hotseat オブジェクトは残し、高さ 0・入力無効にする（Candidate A）
  - Hotseat を削除すると `getHotseat()` を前提とする Launcher 内部処理が広く壊れるため
  - QSB は Hotseat 内に inflate されているため、Hotseat を高さ 0 にすれば自動的に消える
- DeviceProfile の Workspace bottom padding を明示的に除去する
  - `hotseatBarSizePx=0` だけでは `workspaceBottomPadding` と `workspacePageIndicatorHeight` 分が残る
- Workspace の行数（`numRows`）を +2 する
  - Hotseat を消しても行は自動で増えない
  - `hotseatBarSizePx` は dock 行 + QSB + spacing を全部含む大きな値のため +2 が妥当
- 既存 Hotseat アイテムは MVP では DB に残したまま表示しない（将来の migration タスク）

---

## Step 1: 影響範囲の最終確認（調査済み、実装前チェック用）

変更着手前に以下を確認する:
- `src/com/android/launcher3/Hotseat.java` — inflate 箇所、mQsb 生成
- `src/com/android/launcher3/Launcher.java` — `getHotseat()` 呼び出し箇所
- `src/com/android/launcher3/DeviceProfile.java` — `updateWorkspacePadding()` の bottom 計算（:1675〜1723）
- `src/com/android/launcher3/InvariantDeviceProfile.java` — `numRows` の定義箇所
- `src/com/android/launcher3/Workspace.java` — `shouldUseHotseatAsDropLayout()`、page indicator margin

---

## Step 2: 仕様ドキュメント更新

**対象**: `docs/Morrowa_仕様書_v0.1.md`、`docs/Morrowa_MVP_実装指示書.md`

変更内容:
- 「ドックは残す」方針を撤回
- 「Home は検索バーと Dock を持たない」
- 「旧 Dock/QSB 領域は通常 Workspace グリッドに統合する」
- 「下部 2 行もアプリ・フォルダ・ウィジェット配置可能」
- 「MVP では Hotseat カスタマイズ設定を表示しない」

---

## Step 3: Hotseat View を高さ 0・入力無効にする

**`lawnchair/res/values/config.xml`**
```xml
<!-- 変更前 -->
<bool name="config_default_show_hotseat">true</bool>

<!-- 変更後 -->
<bool name="config_default_show_hotseat">false</bool>
```

これにより:
- `isHotseatEnabled = false`（`PreferenceManager2.kt:270` の `pref_show_hotseat`）
- `DeviceProfile.java:887` の分岐で `hotseatBarSizePx = 0`
- `Hotseat.java:330` の `lp.height = grid.hotseatBarSizePx` → 高さ 0
- `Hotseat.java:131` の `DisabledHotseat` に差し替え（操作不可）

QSB は Hotseat 内の `inflate(R.layout.search_container_hotseat)` で生成されるため、Hotseat 高さ 0 で自動的に消える。

---

## Step 4: Workspace bottom padding を除去する

**`src/com/android/launcher3/DeviceProfile.java`** の `updateWorkspacePadding()` を修正。

現在の bottom padding 計算（:1705〜1706）:
```java
paddingBottom = hotseatBarSizePx              // → 0（Step 3 で消える）
              + workspaceBottomPadding         // → 残る、明示的に 0 へ
              + workspacePageIndicatorHeight   // → Morrowa は非表示なので 0 へ
              - mWorkspacePageIndicatorOverlapWorkspace
              - mInsets.bottom
```

修正方針:
- Morrowa 固定方針として `!isHotseatEnabled` のとき `workspaceBottomPadding = 0` にする
- `workspacePageIndicatorHeight` も `!isHotseatEnabled` のとき bottom padding に加算しない
  （Morrowa 仕様でページインジケータは非表示のため）

> **注意**: navigation bar や gesture area の inset（`mInsets.bottom`）は除去しないこと。
> システム UI と重ならないよう、`mInsets.bottom` は引き続き考慮する。

---

## Step 5: Workspace グリッド行数を +2 する

**`lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt`**

```kotlin
// 変更前
val workspaceRows = ... defaultValue = 7

// 変更後
val workspaceRows = ... defaultValue = 9
```

根拠: `hotseatBarSizePx` は dock アイコン行 + QSB（64dp）+ spacing（24〜36dp）+ bottom space（76dp）を全部含む。合計は cell 1行（約80〜100dp）より大幅に大きく、+2 行が妥当。

既存 DB への影響: 行数増加は座標への影響が小さい（追加行が増えるだけ、既存アイテムは動かない）。列数変更は避ける。

---

## Step 6: 既存 Dock アイテムの扱い

MVP 方針: **DB に残したまま、表示しない**

- `CONTAINER_HOTSEAT` アイテムは `WorkspaceLayoutManager.java:107` で `getHotseat()` に bind されるが、Hotseat 高さ 0 のため画面には出ない
- ユーザーから見て「Dock アイコンが消えた」状態になるが MVP では許容する
- 将来タスク: 初回起動時に `CONTAINER_HOTSEAT` アイテムを `CONTAINER_DESKTOP` の下部行へ移動する migration を別途実装

---

## Step 7: Drag/drop と配置の動作確認

Workspace CellLayout が下部まで正しく広がった後、以下を検証する:

確認観点:
- `shouldUseHotseatAsDropLayout()` が旧 Hotseat 領域でも false を返すか（Hotseat bounds が 0 なら自動でそうなる）
- 新しい下部 2 行にアイコンをドラッグ配置できるか
- フォルダ作成できるか
- ウィジェット配置時に下部行まで候補になるか
- 旧 Hotseat への `CONTAINER_HOTSEAT` drop が出ないか

---

## 変更対象ファイル一覧

| ファイル | 変更内容 | Step |
|---|---|---|
| `docs/Morrowa_仕様書_v0.1.md` | Dock 削除方針を明記 | 2 |
| `docs/Morrowa_MVP_実装指示書.md` | 同上 | 2 |
| `lawnchair/res/values/config.xml` | `show_hotseat=false` | 3 |
| `src/com/android/launcher3/DeviceProfile.java` | Hotseat 無効時の bottom padding / page indicator 予約を除去 | 4 |
| `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt` | `workspaceRows` デフォルト `7→9` | 5 |

---

## 受け入れ条件

- [ ] 起動後、ドックも検索バーも表示されない
- [ ] ホーム画面のグリッド行数が増え、以前ドックがあった位置（下部 2 行）にアプリを置ける
- [ ] 画面下端（ナビゲーションバー上）との余白が不自然でない
- [ ] 新しい下部行にアイコンをドラッグ配置・フォルダ作成・ウィジェット配置ができる
- [ ] 左右スワイプで Morrowa ページが正常に切り替わる
- [ ] Morrowa の Habit / ToDo ページでも画面下端の余白が揃っている

## リスク

| リスク | 対策 |
|---|---|
| `getHotseat()` を前提とするコードが壊れる | Candidate A で Hotseat オブジェクト自体は残す |
| bottom padding 除去でナビゲーションバーと重なる | `mInsets.bottom` は除去しない |
| +2 行で端末によっては cell が小さすぎる | 実機確認後に調整。`workspaceRows` はユーザー設定でも変更可能 |
| drag/drop が旧 Hotseat 座標で誤動作 | Step 7 の検証で確認。問題があれば `shouldUseHotseatAsDropLayout()` に明示条件を追加 |
