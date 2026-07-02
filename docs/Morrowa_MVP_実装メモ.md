# Morrowa MVP 実装メモ

作成日: 2026-06-12

## 1. Workspace ページ管理構造

調査対象:

- `src/com/android/launcher3/Workspace.java`
- `src/com/android/launcher3/PagedView.java`
- `lawnchair/src/app/lawnchair/LawnchairLauncher.kt`
- `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt`
- `src/com/android/launcher3/model/DatabaseHelper.java`
- `src/com/android/launcher3/LauncherSettings.java`

### `mWorkspaceScreens`

`Workspace.java` では `mWorkspaceScreens` が `IntSparseArrayMap<CellLayout>` として定義されている。

- 定義: `Workspace.java:203`
- 役割: launcher DB 上の `screenId` と、実際にホーム画面へ表示される `CellLayout` を対応付ける。
- `getScreenWithId(screenId)` はこの map から `CellLayout` を返す。
- `getCellLayoutId(layout)` は逆に `CellLayout` から `screenId` を引く。
- `insertNewWorkspaceScreen(screenId, insertIndex)` は `CellLayout` を inflate し、`mWorkspaceScreens.put(screenId, newScreen)` したうえで `addView(newScreen, insertIndex)` する。

このため、`Workspace` の通常ページは単なる ViewPager 的な child ではなく、DB の screenId と強く結びついた `CellLayout` 群である。

### `mScreenOrder`

`mScreenOrder` は `IntArray` として定義されている。

- 定義: `Workspace.java:206`
- 役割: 表示順に並んだ `screenId` の配列。
- `getScreenIdForPageIndex(index)` は `mScreenOrder[index]` を返す。
- `getScreenOrder()` は現在のページ順を返す。
- 空ページ追加、空ページ削除、2 panel 対応、ページ並び替えのたびに更新される。

`mWorkspaceScreens` が `screenId -> CellLayout` の実体管理で、`mScreenOrder` が page index -> screenId の表示順管理である。

### `snapToPage()` / `setCurrentPage()`

`PagedView.java` 側でページ遷移の共通処理が定義されている。

- `setCurrentPage(currentPage)`: `PagedView.java:438`
- `setCurrentPage(currentPage, overridePrevPage)`: `PagedView.java:445`
- `snapToPage(whichPage)`: `PagedView.java:1737`
- `snapToPage(whichPage, duration)`: `PagedView.java:1745`
- `snapToPage(whichPage, delta, duration, immediate)`: `PagedView.java:1761`

`setCurrentPage()` は scroller を中断し、`mCurrentPage` を `validateNewPage()` 後の index に即時更新し、`notifyPageSwitchListener(prevPage)` を呼ぶ。

`snapToPage()` は `validateNewPage()` 後に `mNextPage` を設定し、`OverScroller.startScroll()` で遷移を開始する。初回 layout 前は `setCurrentPage(whichPage)` にフォールバックする。つまり通常のスワイプ移動は `mNextPage` を経由し、layout / scroll 完了時に現在ページとして確定する。

`Workspace.java` でも配置や drag/drop の都合で `snapToPage()` / `setCurrentPage()` が直接呼ばれている。例:

- widget drag 開始時に空きページへ `setCurrentPage(pageIndex)`: `Workspace.java:547`
- drag/drop 後に対象 screen へ `snapToPage(snapScreen)`: `Workspace.java:2277`
- default home へ `snapToPage(page)`: `Workspace.java:3812`

Morrowa の循環ページを `Workspace` 内に直接実装する場合、この index ベースの遷移と、`mScreenOrder` / DB screenId ベースの通常ページ管理の両方を扱う必要がある。

### `bindAndInitFirstWorkspaceScreen()` のカスタムビュー差し込み前例

`Workspace.java:661` の `bindAndInitFirstWorkspaceScreen()` は first page を初期化し、Smartspace の pinned view を `CellLayout` 内へ差し込む前例になっている。

- first page を `insertNewWorkspaceScreen(Workspace.FIRST_SCREEN_ID, getChildCount())` で作る。
- `enableSmartspace` が false なら pinned item は追加しない。
- `smartspaceMode.getLayoutResourceId()` を inflate して `mFirstPagePinnedItem` に保持する。
- `CellLayoutLayoutParams(0, 0, cellHSpan, 1)` を作り、`lp.canReorder = false` にする。
- `firstPage.addViewToCellLayout(..., R.id.search_container_workspace, lp, true)` で CellLayout の 1 行目に固定配置する。

これは「通常 Workspace の first page に固定カスタム View を載せる」前例である。ただし、あくまで `CellLayout` 内の pinned item であり、Habit/ToDo のような専用フルスクリーン画面を `Workspace` 通常ページとして扱う前例ではない。

---

## 2. Habit/ToDo 差し込み方式候補

### 方式A: Workspace 内ページ追加

`mWorkspaceScreens` に Habit / ToDo 用の `CellLayout` または専用 View を差し込む方式。

メリット:

- 既存の横スワイプ、ページ位置、wallpaper parallax、workspace transition に自然に乗りやすい。
- `snapToPage()` / `setCurrentPage()` の既存操作と統合しやすい。

デメリット:

- `Workspace.onViewAdded()` は `CellLayout` 以外を拒否するため、専用画面も `CellLayout` 化するか `Workspace` を改造する必要がある。
- 空ページ削除、extra empty screen、2 panel、drag/drop、screenId 永続化の処理に Habit/ToDo 例外を入れる必要がある。
- `favorites.screen` と screenId の意味に Morrowa 論理ページが混ざりやすい。
- Habit/ToDo はアプリ、フォルダ、ウィジェットを置けない専用画面なので、`CellLayout` の配置モデルと相性が悪い。

リスク: **高**。ドック、ウィジェット配置、空ページ削除、default home page、grid migration への副作用が大きい。

### 方式B: LawnchairLauncher オーバーレイ（推奨）

`LawnchairLauncher` / `DragLayer` 上に Morrowa 専用のフルスクリーン View を重ね、論理ページとして Habit / ToDo を表示する方式。

メリット:

- `Workspace` の通常ページ、`favorites` DB、drag/drop、widget 配置を壊しにくい。
- Habit/ToDo を `CellLayout` にしなくてよい。
- Morrowa 独自 UI / data / gesture を独立 package に寄せやすい。
- `LawnchairLauncher.kt` は既に `PreferenceManager2`、`stateManager`、`dragLayer` にアクセスしているため、overlay の attach / detach の入口にしやすい。
- `last_page_type` を DataStore に保存し、復帰時に overlay 表示を復元できる。

デメリット:

- 既存 `PagedView` の横スワイプとは別に、Morrowa 論理ページ用の gesture / animation を実装する必要がある。
- overlay 表示中の Back、Home、All Apps、長押し、drag 開始、フォーカス、accessibility の扱いを明確にする必要がある。

リスク: **低**。Workspace 内部構造に手を入れない。

### 方式C: Activity / Fragment 差し込み

Habit / ToDo を別 Activity または Fragment として実装し、Launcher から遷移する方式。

メリット: 通常の Android 画面として UI / lifecycle / back stack を扱いやすい。

デメリット:

- ホーム画面の一部として横スワイプ循環する体験から外れやすい。
- Fragment を Launcher に入れる場合、既存 Launcher は Fragment ベースではないため土台作りが大きくなる。

リスク: **中**。ホーム復帰、ロック解除、Recents との整合が難しい。

### 推奨: 方式B

Phase 1 は方式Bを推奨する。理由:

- Habit / ToDo は通常 Workspace ページではなく固定の専用画面である。
- `Workspace` のページ管理は `favorites` DB と `CellLayout` 配置モデルに密結合している。
- 方式Aは既存ホーム機能への副作用が大きく、MVP 初期実装として重い。
- 方式Bなら Lawnchair の通常ページ機構を保ったまま、Morrowa 独自の論理ページ基盤を小さく追加できる。
- AGENTS.md の「Lawnchair 由来コードへの広範囲な直接改変を避ける」に合う。

---

## 3. `last_page_type` 保存先

保存先は `PreferenceManager2.kt` の DataStore に追加するのがよい。

既存の `defaultHomePage` は次の形で定義されている:

- `PreferenceManager2.kt:365`
- key: `intPreferencesKey(name = "default_home_page")`
- default: `Workspace.DEFAULT_PAGE`

`last_page_type` も同じ `PreferenceManager2` の `preference(...)` として追加するのが自然である。理由:

- 最後にいた論理ページは launcher UI 状態であり、Room の domain data ではない。
- Launcher 起動時 / 復帰時に同期的に参照したい。
- DataStore は既に `PreferenceManager2.getInstance(context)` から Java / Kotlin 双方で参照されている。
- DB migration を増やさずに追加できる。

推奨する保存形式は文字列 enum:

- key: `stringPreferencesKey(name = "morrowa_last_page_type")`
- default: `"home"`
- 値: `"home"`, `"habit"`, `"todo"`, `"widget_blank"`

`defaultHomePage` は Workspace child index を保存するので int が合っている。一方、`last_page_type` は Morrowa の論理ページであり、Workspace の page index と一致しない。将来 Widget Blank の実 screenId や Workspace page index が変わっても壊れにくいよう、論理名で保存する。

---

## 4. Launcher DB の変更要否

`favorites` テーブルは `LauncherSettings.Favorites` に定義されている（`LauncherSettings.java:306`）。主なカラムは `screen`（screenId）、`container`、`cellX`、`cellY`、`spanX`、`spanY`、`itemType` など、launcher item 配置に特化した構造である。

判断: **変更不要**。

- Habit / ToDo / Alarm / completion history は launcher item 配置情報ではない。
- `favorites` に Morrowa 独自データを入れると、配置モデル、backup / restore、grid migration、item id 管理に不要な影響が出る。
- `favorites` schema を増やすには `SCHEMA_VERSION` と migration を扱う必要があり、MVP 初期には過剰。

Morrowa 独自データは Room で別管理する。既存の Lawnchair Room DB は `lawnchair/src/app/lawnchair/data/AppDatabase.kt` にあるが、Morrowa domain data の独立性を保つため、Phase 1 以降では `app.morrowa.data` 配下に Morrowa 専用 Room DB を作る方が追跡しやすい。

---

## 5. 触るべきファイル / 触らないファイル

### Phase 1 で触るべきファイル

| ファイル | 理由 |
|---|---|
| `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` | Morrowa overlay の attach / detach 入口。`dragLayer`、`stateManager`、`PreferenceManager2` にアクセスできる |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | `morrowa_last_page_type` を DataStore preference として追加する |
| `lawnchair/src/app/morrowa/`（新規 package） | MorrowaPage enum、overlay controller、gesture / page state 管理、Habit/ToDo の仮 placeholder View |
| `res/layout` / `res/values`（必要な場合のみ） | overlay の placeholder layout や文字列が XML ベースで必要な場合。Phase 1 では最小限に留める |

### Phase 1 で触らないファイル

| ファイル | 理由 |
|---|---|
| `src/com/android/launcher3/Workspace.java` | drag/drop、empty screen、default page、widget placement が密集。初期実装で触ると影響範囲が大きい |
| `src/com/android/launcher3/PagedView.java` | Launcher 全体のページング基盤。変更すると Workspace 以外にも副作用が出る |
| `src/com/android/launcher3/model/DatabaseHelper.java` | launcher item DB の schema / migration 管理。Morrowa domain data とは責務が違う |
| `src/com/android/launcher3/LauncherSettings.java` | `favorites` schema 定義。Morrowa 用に列や item type を追加しない |
| `src/com/android/launcher3/WorkspaceLayoutManager.java` | item bind / placement の基盤。Morrowa 専用 overlay では不要 |
| `src/com/android/launcher3/CellLayout.java` | Workspace の grid / drag / widget 配置の基盤。Habit / ToDo を CellLayout 化しない限り不要 |
| Hotseat / All Apps 周辺 | Phase 1 では直接変更しない。touch / state 競合が起きた場合のみ調査する |

---

## 6. 既存ホーム機能へのリスク評価

### ドック：低リスク

方式Bなら Hotseat は既存 layout に残る。Home / Widget Blank では従来通り操作できる。

注意: Habit / ToDo overlay 表示中にドックを表示するか隠すかは仕様として固定する必要がある。overlay が touch を全面取得する場合、ドック操作はできなくなる（専用画面としては自然）。

### アプリ一覧：中リスク

All Apps は縦 swipe と LauncherState で管理されている。overlay 表示中の縦 swipe を All Apps に渡すか抑止するかを決める必要がある。

推奨: Phase 1 では Habit / ToDo overlay 表示中の縦 swipe は overlay が消費し、Home / Widget Blank のみ既存 All Apps swipe を許可する。

### アプリ起動：低〜中リスク

Home / Widget Blank 上のアイコン起動は既存 Workspace に残るため壊れにくい。overlay 表示中は Workspace icon が背面にあるため、touch 透過させない限り誤起動しない。

注意: `onResume()` / LauncherState NORMAL 復帰時に overlay 復元を入れる場合、起動直後の bind lifecycle と競合しないよう attach timing の調整が必要。

### ウィジェット配置：低リスク

Widget Blank は通常 Workspace ページとして扱う方針なので、既存の widget placement / AppWidgetHost / favorites DB を使える。Habit / ToDo overlay を `favorites` に混ぜなければ widget 配置ロジックに影響しない。

注意: Widget Blank 用の通常 Workspace ページをどう確保するかは別途設計が必要。方式Aで Habit / ToDo を Workspace に混ぜると、`removeExtraEmptyScreen()` や `stripEmptyScreens()` が専用ページを空ページとして扱うリスクがある。

---

## 結論

Phase 1 は `Workspace` / `PagedView` / launcher DB を直接変えず、`LawnchairLauncher` 上の Morrowa overlay と `PreferenceManager2` の `morrowa_last_page_type` 追加から始める。

この進め方なら、Home / Widget Blank は既存 Lawnchair Workspace として維持し、Habit / ToDo だけを Morrowa 専用画面として切り出せる。MVP の価値に近い部分を小さく実装しつつ、ドック、アプリ一覧、アプリ起動、ウィジェット配置への副作用を最小化できる。

---

## 7. 2026-07-02 作業履歴: 透明化 MVP

### 7.1 背景

Home 画面仕様のうち、Workspace 上のアプリアイコン / フォルダを透明化する MVP が未実装だったため実装に着手した。

仕様上の対象:

- Workspace 上のアプリアイコン
- Workspace 上のフォルダ

対象外:

- ウィジェット
- Habit / ToDo 項目
- Hotseat / Dock
- All Apps

仕様上、透明化済みアイコンは通常時には見えないが、タップ起動、長押し、アクセシビリティ名、配置情報は維持する。編集モード中は薄く見える状態にする。

### 7.2 実装内容

追加 / 変更した主なファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/morrowa/MorrowaTransparentItemController.kt` | 透明化状態の保存、対象判定、View への alpha 適用、Workspace 一括適用を担当 |
| `lawnchair/src/app/lawnchair/ui/popup/LawnchairShortcut.kt` | 長押しメニューに `透明化` / `透明解除` の SystemShortcut を追加 |
| `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` | shortcut 登録、bind 完了時 / state 遷移後の透明化再適用 |
| `src/com/android/launcher3/util/ItemInflater.kt` | アイコン / フォルダ inflate 時に透明化状態を初期反映 |
| `src/com/android/launcher3/Workspace.java` | `mapOverCellLayouts()` の null / tag 型チェックを追加 |
| `res/values/strings.xml` | 英語 fallback 文字列を追加 |
| `res/values-ja/strings.xml` | 日本語文字列を追加 |

保存方式:

- Launcher DB (`favorites`) は変更しない。
- Morrowa 側の `SharedPreferences` (`morrowa_transparent_items`) に `StringSet` として保存する。
- アプリアイコンは `app:<user>:<component>` をキーにする。
- フォルダは component が存在しないため `item:<favorites id>` を fallback キーにする。

この判断の理由:

- アイコン描画 / bind 中に同期的な判定が必要であり、Room / Flow を挟むと初期差分として影響が大きい。
- Launcher DB migration を増やさず、既存配置 DB への副作用を避けられる。
- アプリは再インストールや DB row id 変化に比較的強い component key にできる。
- フォルダは安定した外部 component がないため、MVP では row id fallback とした。

### 7.3 レビュー反映

初回実装後、以下のレビュー指摘を受けて修正した。

| 指摘 | 対応 |
|---|---|
| `applyToView()` が `view.visibility = View.VISIBLE` を無条件に設定し、ドラッグ元アイコンを復活させうる | `visibility` の上書きを削除 |
| `applyToView()` が `isClickable` / `isLongClickable` を無条件に設定していた | clickable / longClickable の上書きを削除 |
| `Workspace` の item 走査で `getChildAt()` null の可能性がある | `mapOverCellLayouts()` に null / tag 型チェックを追加 |
| inflate 時に `editMode=false` 固定だった | `ItemInflater` で `LauncherState.EDIT_MODE` を見て初期適用するよう修正 |
| item ごとに `SharedPreferences#getStringSet()` を読む | `applyToWorkspace()` で一度だけ読み、各 item に渡すよう修正 |
| Workspace に Morrowa 固有メソッドを追加していた | `Workspace.applyMorrowaTransparentItems()` を削除し、`MorrowaTransparentItemController.applyToWorkspace()` に移動 |
| default `strings.xml` に日本語が入っていた | default は英語、`values-ja` に日本語を追加 |

補足:

- 「透明アイコンが空きセルのタッチを横取りする」点は、仕様の「透明化してもタップ起動、長押しを維持する」と衝突するため、透明化中の touch 無効化は行っていない。
- ただし、無条件に clickable / longClickable を強制する処理は削除した。

### 7.4 現在の挙動

期待される MVP 挙動:

- Workspace 上のアプリアイコン / フォルダを長押しすると `透明化` が出る。
- 透明化済みの対象では `透明解除` が出る。
- 透明化すると通常時は `alpha=0f` になる。
- View 自体は残るため、タップ起動と長押し操作は維持される。
- 編集モード中は `alpha=0.28f` で薄く見える。
- bind 完了時と Launcher state 遷移後に透明化状態が再適用される。

### 7.5 検証状況

実行できた検証:

- `git diff --check` は通過。
- JDK は `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` を使用できることを確認。
- 不足していた submodule `platform_frameworks_libs_systemui` は `git submodule update --init --recursive` で取得済み。
- `local.properties` を作成し、SDK path を `C:\Users\hanpe\AppData\Local\Android\Sdk` に設定した。
- `.\gradlew.bat tasks --all` は通過。
- `.\gradlew.bat installLawnWithQuickstepGithubDebug` はビルドと APK 生成まで通過。
- 実機 `SC-52C - 16` / adb serial `R5CT3378XTJ` を認識した。
- 既存 `app.lawnchair.debug` との署名不一致で一度 install に失敗したが、既存 debug package をアンインストール後に install 成功。
- ユーザー実機確認で、アプリアイコンの透明化は動作することを確認済み。

発生した install エラー:

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE:
Existing package app.lawnchair.debug signatures do not match newer version
```

対応:

```powershell
adb uninstall app.lawnchair.debug
.\gradlew.bat installLawnWithQuickstepGithubDebug
```

インストールされた APK:

```text
Lawnchair.16.Dev.(fabeccf).github.debug.apk
```

### 7.6 フォルダ透明化対応

実機確認後、アプリアイコンの透明化は動作したが、フォルダにも透明化を適用したいという要望が出た。

原因:

- `MorrowaTransparentItemController` 側では `ITEM_TYPE_FOLDER` を対象にしていた。
- `ItemInflater` 側でも `FolderIcon` inflate 時に alpha 反映は入っていた。
- しかし、既存の長押し popup は `BubbleTextView` 前提で、フォルダは `FolderIcon` として扱われる。
- そのため、フォルダ長押し時は透明化 shortcut の popup 経路に入らず、通常の drag 開始に流れていた。

対応内容:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/popup/PopupContainerWithArrow.java` | popup の original icon を `BubbleTextView` 固定から `View` に広げ、`FolderIcon` 用の `showForFolderIcon()` を追加 |
| `src/com/android/launcher3/folder/FolderIcon.java` | `startLongPressAction()` を追加し、フォルダから popup を開けるようにした |
| `src/com/android/launcher3/Workspace.java` | drag 開始前の long press action で `FolderIcon.startLongPressAction()` を呼ぶようにした |
| `src/com/android/launcher3/popup/LauncherPopupLiveUpdateHandler.java` | widget shortcut の live update は `BubbleTextView` popup のみ対象にし、フォルダ popup では何もしないようにした |

実装方針:

- フォルダ専用の別 UI は作らず、既存の system shortcut popup を流用する。
- アプリアイコン向け deep shortcut / widget live update は `BubbleTextView` の場合だけ維持する。
- フォルダ popup は system shortcut のみを表示し、`透明化` / `透明解除` を出す。
- popup から drag へ移る既存の pre-drag 動作は壊さない。

### 7.7 最終検証状況

実行済み:

- `git diff --check`
- `.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin compileLawnWithQuickstepGithubDebugJavaWithJavac`
- `.\gradlew.bat installLawnWithQuickstepGithubDebug`
- 実機 `SC-52C - 16` への install 成功

実機確認で見るべきこと:

- 通常アイコンの長押しに `透明化` が出る。
- 透明化後にアイコンとラベルが見えない。
- 透明化後もタップ起動できる。
- 透明化後も長押しで `透明解除` が出る。
- 編集モード中だけ薄く表示される。
- ドラッグ中に元セルのアイコンが復活しない。
- フォルダでも透明化 / 解除できる。
- ウィジェット、Habit / ToDo、Hotseat には透明化が出ない。

残る注意点:

- フォルダ透明化は `item:<favorites id>` を保存キーにするため、Launcher DB の row id が変わるような再作成 / layout import では状態が引き継がれない可能性がある。
- 透明化済みフォルダも仕様通りタップ領域は残り、タップでフォルダを開ける。
