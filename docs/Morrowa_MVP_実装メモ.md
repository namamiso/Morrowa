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

### 7.8 レビュー追加反映

レビューで見つかった以下の2点を修正した。

| 指摘 | 対応 |
|---|---|
| フォルダ長押し popup に動作しない `App info` が混入する | `PopupContainerWithArrow.showForFolderIcon()` では通常アプリアイコン用の `getSupportedShortcuts()` を使わず、フォルダ用に `透明化 / 透明解除` と `Remove` だけを明示的に生成するよう修正 |
| `ItemInflater` の透明化状態読み込みが item ごとに再実行される | `bindItems` / async inflate の batch 単位で `MorrowaTransparentItemController.getTransparentKeys()` を1回だけ読み、`ItemInflater.inflateItem()` へ渡すよう修正 |

検証:

```powershell
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac
```

結果:

- どちらも `BUILD SUCCESSFUL`

---

## 8. 2026-07-02 作業履歴: Habit / ToDo 全画面表示修正

### 8.1 背景

実機確認で、Habit / ToDo の専用ページが下方向につぶれて表示される問題があった。

現在の Habit / ToDo は `MorrowaWorkspacePageView` として `CellLayout` に追加されている。`MorrowaWorkspacePageView` 内の `ComposeView` 自体は `MATCH_PARENT` / `fillMaxSize()` で作られていたが、親である `ShortcutAndWidgetContainer.measureChild()` の通常アイコン / フォルダ向け計測処理に入っていた。

そのため、セル内でアイコンを中央寄せするための `cellPaddingY` が Morrowa 専用ページにも付与され、Compose 画面が下へ押し込まれていた。

### 8.2 実装内容

変更ファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/ShortcutAndWidgetContainer.java` | `MorrowaWorkspacePageView` を専用分岐にし、通常アイコン / フォルダ用 padding を付けないようにした |

実装方針:

- `MorrowaWorkspacePageView` は `CellLayout` 全体を使う専用ページとして扱う。
- `NavigableAppWidgetHostView` や QSB、通常アイコン / フォルダの計測処理は変更しない。
- `CellLayoutLayoutParams` の `lp.setup(...)` は維持し、`child.setPadding(0, 0, 0, 0)` だけを Morrowa 専用ページへ適用する。

### 8.3 検証状況

実行済み:

```powershell
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin compileLawnWithQuickstepGithubDebugJavaWithJavac
```

結果:

- `BUILD SUCCESSFUL`

未実施:

- 実機で Habit / ToDo が上下につぶれず全画面表示になることの確認。

### 8.4 追加調整

実機確認で、Habit / ToDo 画面にまだ上下の余白が残っていたため追加修正した。

原因:

- `HabitScreen` / `ToDoScreen` の外側 `Box` に `windowInsetsPadding(WindowInsets.systemBars)` が残っていた。
- 画面全体の `Column` に `vertical` padding が入っていた。

対応:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/morrowa/ui/HabitScreen.kt` | 外側の system bars inset と縦 padding を削除し、画面タイトルを `Morrowa` から `Habit` に変更 |
| `lawnchair/src/app/morrowa/ui/ToDoScreen.kt` | 外側の system bars inset と縦 padding を削除 |

検証:

```powershell
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin
.\gradlew.bat installLawnWithQuickstepGithubDebug
```

結果:

- `BUILD SUCCESSFUL`
- 実機 `R5CT3378XTJ / SC-52C - 16` への install 成功

---

## 9. 2026-07-02 作業履歴: 無限スクロール wrap アニメーション修正

### 9.1 背景

現在デフォルトで有効になっている無限スクロール機能で、最終ページから先頭ページ、または先頭ページから最終ページへ wrap する際のアニメーションが不自然だった。

従来の実装では `snapToPageWithVelocity()` が実ページ上の scroll 位置差分をそのまま使うため、例えば 3 ページ構成で `2 -> 0` に移動する場合、視覚的には `2 -> 1 -> 0` のように全ページを横断する動きに見える。

フォーク元 Lawnchair の PR #6653 `fix: infinite scroll wrap animation direction` を確認し、同 PR の方針を Morrowa の現在の差分に合わせて局所移植した。

参照:

- `https://github.com/LawnchairLauncher/lawnchair/pull/6653`
- ローカル参照ブランチ: `upstream/pr-6653`
- 対象コミット:
  - `d42a4c2421 fix: infinite scroll wrap animation direction #6268`
  - `9beb5c20e1 fix: RTL wrap drag trigger conditions`
  - `de96e7e5ac fix: cancel wrap state on direction reversal, correct scroll progress for wrap target`
  - `435ca61251 fix: wallpaper slides full range during wrap`
  - `94f75fc717 fix: cache enableFeed per-gesture and clamp scroll on wrap cancel`

### 9.2 実装内容

変更ファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/PagedView.java` | wrap-scroll 状態管理、端ドラッグ時の仮想ページ配置、wrap 専用 snap、キャンセル / 中断時の復元、scroll progress 補正を追加 |
| `src/com/android/launcher3/Workspace.java` | ページインジケータ更新時に wrap 補正済み scroll を使うよう変更 |
| `src/com/android/launcher3/util/WallpaperOffsetInterpolator.java` | 壁紙オフセット更新時に wrap 補正済み scroll を使うよう変更 |

実装方針:

- PR #6653 の差分をそのまま上書きせず、Morrowa 側の Workspace 独自変更を保持したまま必要部分だけ移植した。
- wrap 中だけ対象ページの `translationX` を一時的に変更し、先頭 / 最終ページを現在ページの隣に仮配置する。
- `mMinScroll` / `mMaxScroll` を一時的に拡張し、`scrollTo` の clamp で仮想スクロールが潰れないようにする。
- wrap 完了時は target page の本来の scroll 位置へ戻し、`translationX` と scroll bounds を復元する。
- ドラッグを戻した場合、`ACTION_CANCEL`、アニメーション中断時は wrap 状態をキャンセル / finalize して表示状態を残さない。
- Feed 有効時の先頭ページ左方向 wrap、および RTL 条件は PR #6653 の修正内容に合わせた。
- Morrowa の Habit / ToDo 固定ページ、Widget Blank、透明化機能、画面順序保存処理には触れていない。

### 9.3 検証状況

実行済み:

```powershell
git diff --check -- src/com/android/launcher3/PagedView.java src/com/android/launcher3/Workspace.java src/com/android/launcher3/util/WallpaperOffsetInterpolator.java
```

結果:

- `git diff --check` は通過。

ビルド確認（初回）:

- `.\gradlew.bat assembleDebug` は `.gradle` の lock file アクセスで一度失敗した。
- 権限付きで `.\gradlew.bat assembleDebug` を再実行したが、2 分 / 5 分の timeout で完了しなかった。
- `.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain` も timeout。
- その後、ログ出力付きで `compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain --stacktrace --no-daemon` を実行したが、`compileLawnWithQuickstepGithubDebugKotlin` で 10 分以上停止したため、こちらで起動した Gradle / Java プロセスを停止した。

未完了（初回時点）:

- Gradle ビルド完了確認。
- 実機での wrap アニメーション確認。

実機確認で見るべきこと:

- 無限スクロール有効時、最終ページから先頭ページへ移動しても全ページ横断に見えず、隣ページへ 1 ページ分だけ動く。
- 先頭ページから最終ページへの wrap も同様に自然に見える。
- ゆっくり端へドラッグした時、wrap 先ページが隣に見える。
- ドラッグを戻した時にページ位置や alpha が壊れない。
- アニメーション中にタップ / 中断しても `translationX` が残らない。
- ページインジケータと壁紙パララックスが wrap 中に破綻しない。
- Morrowa の Home / Habit / ToDo / Widget Blank のページ順と復帰挙動が崩れない。

### 9.4 追加検証

2026-07-02 に compile 検証を再実行した。

事前対応:

- `platform_frameworks_libs_systemui` submodule が未初期化だったため、`git submodule update --init --recursive` を実行した。
- `local.properties` が存在しなかったため、Android SDK path を `C:\Users\hanpe\AppData\Local\Android\Sdk` に設定した。
- デフォルトの Java 25 では `source release 21` 周辺で Java compile が失敗したため、Android Studio 同梱 JBR `C:\Program Files\Android\Android Studio\jbr` の OpenJDK 21.0.8 を `JAVA_HOME` として使用した。
- SDK Build-Tools 37.0.0 と Android SDK Platform 37.0 は Gradle 実行中に自動インストールされた。

実行コマンド:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果:

- `BUILD SUCCESSFUL`
- `403 actionable tasks: 171 executed, 232 up-to-date`

補足:

- Kotlin / Java の deprecated API や annotation target に関する warning は出ているが、compile は成功している。
- 実機での wrap アニメーション確認は未実施。

### 9.5 追加検証: assembleDebug 完了

2026-07-02 に `assembleDebug` の完了確認を再実行した。

実行コマンド:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug --console=plain
```

結果:

- `BUILD SUCCESSFUL`
- deprecated API / unchecked operation の warning は出ているが、ビルドは成功。

残る確認:

- 実機での wrap アニメーション確認は、端末接続と操作確認が必要なため未実施。
- ローカルで可能な compile / assemble 検証は完了。

---

## 10. 2026-07-02 作業履歴: JSON backup 形式の仕様整合

### 10.1 背景

Morrowa の JSON export / import 実装は存在していたが、出力 JSON が仕様書の外形とずれていた。

仕様書では次のメタ情報をトップレベルに持ち、実データを `data` 配下に置く方針である。

```json
{
  "app": "Morrowa",
  "schema_version": 1,
  "exported_at": "2026-06-02T00:00:00+09:00",
  "data": {}
}
```

従来実装では `schema_version` と `exported_at` はあったが、`app` と `data` ラッパーがなく、`habits` / `todos` / `alarms` などがトップレベルに直接置かれていた。

### 10.2 実装内容

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/morrowa/data/MorrowaBackup.kt` | export JSON に `app: "Morrowa"` と `data` ラッパーを追加 |

実装方針:

- 新規 export は仕様書に合わせて `app` / `schema_version` / `exported_at` / `data` を出力する。
- `data` 配下に `habits` / `habit_rules` / `habit_completions` / `alarms` / `todos` を置く。
- import は新形式を優先して読む。
- 既に出力済みの旧形式 JSON も `data` がない場合は従来通り読めるようにした。
- `app` が存在しない旧形式は Morrowa とみなす。
- `app` が存在し、`Morrowa` 以外なら import を拒否する。

### 10.3 検証状況

実行済み:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin --console=plain
```

結果:

- `BUILD SUCCESSFUL`
- `401 actionable tasks: 2 executed, 399 up-to-date`

---

## 11. 2026-07-02 作業履歴: JSON import 後のアラーム再登録

### 11.1 背景

JSON import は DB の全置換には対応していたが、仕様書の「Android の system alarm id は端末依存のため、インポート後に再スケジュールする」に対する実装が不足していた。

また、全置換前に既存の `PendingIntent` をキャンセルしないと、import 後に存在しない ToDo / Habit の古い通知が残る可能性があった。

### 11.2 実装内容

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/morrowa/MorrowaAlarmRescheduler.kt` | DB 上の既存アラームの一括キャンセル / 有効アラームの一括再登録 helper を追加 |
| `lawnchair/src/app/morrowa/MorrowaBootReceiver.kt` | boot 時の再登録処理を helper 利用へ整理 |
| `lawnchair/src/app/morrowa/ui/BackupScreen.kt` | import 時に旧アラーム一覧を退避 -> DB 全置換 -> 旧 PendingIntent cancel -> import 後アラーム再登録を実行 |

実装方針:

- `MorrowaAlarmRescheduler.cancelAll()` で現在 DB 上の全アラームと未達習慣通知をキャンセルする。
- import では、旧 DB 上の全アラームを import 前に退避する。
- `BackupRepository.importAll()` で DB を全置換する。
- import 成功後に退避済みの旧アラームから `PendingIntent` をキャンセルし、import 失敗時は既存アラームを消さない。
- `MorrowaAlarmRescheduler.rescheduleAll()` で import 後 DB の有効アラームと未達習慣通知を登録する。
- BootReceiver と import 後再登録で同じ helper を使い、再登録ロジックの重複を避ける。

### 11.3 検証状況

実行済み:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin --console=plain
```

結果:

- `BUILD SUCCESSFUL`
- `401 actionable tasks: 2 executed, 399 up-to-date`

補足:

- `BackupScreen.kt` の既存 `Icons.Rounded.ArrowBack` と `Divider` に deprecated warning が出ているが、コンパイルは成功している。

---

## 12. 2026-07-04 作業履歴: App Drawer `Add to Home screen` drop target

詳細は `docs/Morrowa_AppDrawer_実装計画.md` §12〜13 を参照。

### 12.1 背景

App Drawer 内のアプリアイコンを長押しした際、ホーム画面アイコン drag に近い drag/edit 状態に入り、上部に `Uninstall` / `Add to Home screen` の2つの drop target だけを出す機能の実装計画（`docs/Morrowa_AppDrawer_実装計画.md`）が origin に追加されていたため、事前調査 → 実装まで着手した。

### 12.2 事前調査で判明した前提の変化

Explore による調査の結果、想定と異なり以下が既に実装済みだった。

- App Drawer 長押し → 既存 `DragController` ベースの drag 開始（`ItemLongClickListener.onAllAppsItemLongClick()` が `Workspace.beginDragShared()` を呼ぶ経路）
- All Apps -> Workspace への配置（`Workspace.onDropExternal()` 経由）

そのため実装スコープは「`Add to Home screen` 用の小さな `ButtonDropTarget` の新設」と「drag source によるボタン出し分け」の2点に縮小された。

仕様判断（決定済み）: App Drawer 長押し時に今も出る既存のショートカット popup は、抑制せず共存させる。

### 12.3 実装内容

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/AddToHomescreenDropTarget.java`（新規） | App Drawer 由来 drag のときだけ表示される `ButtonDropTarget`。`LauncherAccessibilityDelegate#addToWorkspace()` を呼び出して Workspace に配置する |
| `src/com/android/launcher3/DeleteDropTarget.java` | drag が App Drawer 由来（`dragObject.dragSource instanceof ActivityAllAppsContainerView`）なら Remove/Cancel を非表示にする |
| `res/layout/drop_target_bar.xml` | `AddToHomescreenDropTarget` を3つ目の子として追加（初期 `visibility="gone"`） |
| `res/drawable/ic_add_no_shadow.xml`（新規） | 既存の Remove/Uninstall アイコンと同じスタイルの "+" アイコン |

`ButtonDropTarget` 基底クラス、`DropTargetBar`、`SecondaryDropTarget`（Uninstall）は無改造。文字列は既存の `R.string.action_add_to_workspace`（全ロケール翻訳済み）を再利用し、新規追加していない。

### 12.4 検証状況

実行済み:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果:

- `BUILD SUCCESSFUL in 2m 19s`（`compileLawnWithQuickstepGithubDebugKotlin` も依存タスクとして成功）
- 新規 / 変更ファイル起因のエラーなし

実機 install:

- `adb uninstall app.lawnchair.debug` → `installLawnWithQuickstepGithubDebug` で再インストール成功（`SC-52C - 16`）。
- `adb shell am start` で起動、プロセス生存確認、crash ログなし。
- ドラッグ操作を伴う実際の動作確認（項目は §12.4 未実施欄参照）はタッチ操作が必要なため、ユーザーによる実機確認待ち。

未実施:

- 実機での手動確認（App Drawer 長押し → drag → 上部に `Uninstall` / `Add to Home screen` の2つだけが出る、`Add to Home screen` で Workspace に配置される、`Uninstall` が既存 flow を開く、drag cancel で通常状態に戻る、ホーム画面アイコン drag の既存表示が変わっていない、検索 / スクロール / フォルダ / prediction / profile 表示が壊れていない）。

### 12.5 仕様の見直し（2026-07-04 実機確認後）

ユーザーによる実機確認の過程で、想定挙動が異なることが判明した。

- 現状の実装は、App Drawer 由来の drag でも `Workspace.onDragStart()`（`Workspace.java:558-563`）の無条件 `goToState(SPRING_LOADED)` により Home 画面へ強制的に切り替わってしまう。
- ユーザーが望むのは、App Drawer 由来の drag では Home 画面へ遷移せず、**App Drawer 専用の編集モード**（Home の `SPRING_LOADED` 相当）に入り、その中で並び替え・フォルダ作成・フォルダ出し入れができ、Home 用とは別の専用上部バー（`Uninstall` / `Add to home screen`）を持つ、というもの。

これは当初の「同一バー内でボタンを出し分ける」実装より大きいスコープになるため、詳細な調査結果と実装計画を新しい文書 `docs/Morrowa_AppDrawer_編集モード_実装計画.md` にまとめた。次のセッション以降はそちらの Phase A から着手する。

### 12.6 App Drawer 編集モード Phase A / B 実装（2026-07-04）

`docs/Morrowa_AppDrawer_編集モード_実装計画.md` の Phase A（新 `LauncherState` `DRAWER_SPRING_LOADED` の追加）・Phase B（App Drawer 由来 drag をこの state へルーティング）を実装し、それぞれビルド確認済み。

実機確認の過程で、新 state に正しく遷移するがドラッグ中のアイコンが透明になり動かなくなる不具合が発覚。原因は `DrawerSpringLoadedState` に `AllAppsState` からそのままコピーしていた `FLAG_CLOSE_POPUPS` で、共存させている長押しpopupの自前クローズ処理と競合していたため。このフラグを削除し再ビルド・再インストールしたが、**ユーザー確認の結果、問題はまだ解消していない**。原因は完全には特定できておらず、次セッションでの追加調査が必要。詳細は `docs/Morrowa_AppDrawer_編集モード_実装計画.md` §9 を参照。

### 12.7 App Drawer 編集モード 設計転換と一連の不具合修正（2026-07-05）

前回セッションで積み残した「App Drawer 由来 drag でアイコンが透明化する」問題を継続調査し、独立 `LauncherState`（`DRAWER_SPRING_LOADED`）方式そのものに構造的な欠陥があると判明。`Launcher.onStateSetEnd()` が `ALL_APPS` から別 state への遷移完了時に無条件で `getAppsView().reset(false)` を呼び、drag 中の RecyclerView をリセットしていたことが直接原因。ALL_APPS 前提の同一性チェックが他にも多数あり、独立 state を維持するモグラ叩きのリスクが高いと判断し、**方針転換**: 独立 state を廃止し、App Drawer 由来 drag では `ALL_APPS` state のまま drag する方式へ変更（Phase A の `DrawerSpringLoadedState` は削除）。

この方針転換後、実機確認を繰り返す中で次の不具合を発見・修正した。

1. 上部バー（`Uninstall` / `Add to home screen`）が Drawer の裏に隠れる → `launcher.xml`（`res/` と `lawnchair/res/` 両方）で `drop_target_bar` を `DragLayer` の最後の子（最前面）へ移動。
2. グリッド上で指を離すと確認なしに Home へ強制遷移する → `LauncherDragController.exitDrag()` が drag source に関わらず無条件に `goToState(NORMAL)` していたのが原因。App Drawer 由来 drag ではスキップするよう修正。あわせて `Workspace.onDragStart()` の `addExtraEmptyScreenOnDrag()` も App Drawer 由来では skip。
3. `Add to home screen` が機能しない → `LauncherAccessibilityDelegate.addToWorkspace()` の `goToState(NORMAL, ..., successCallback)` が、直後に呼ばれる `DropTargetHandler.onDropAnimationComplete()` の重複した `goToState(NORMAL)` によってキャンセルされ、配置処理（成功コールバック内）が実行されないまま state だけ Home へ遷移していたのが原因。
4. 修正3で `ItemInstallQueue` 経由に変更した結果、配置は成功するようになったが常に4ページ目（screen index 0 から順に空きを探す挙動）に追加されてしまう新たな不具合が発生。`AddToHomescreenDropTarget` 内に「現在裏で開いているページを優先し、Habit/ToDo（overlay page）の場合は Home にフォールバックする」ロジックを直接実装して解消。

最終確認: Home / Widget Blank / Habit・ToDo の3パターンすべてで `Add to home screen` が意図したページに正しく追加されることを実機で確認済み。上部バーの表示、grid上でのキャンセル、back ジェスチャーでのキャンセルもすべて実機確認済み。

残課題: アプリ同士のドラッグによる並び替え・フォルダ作成・フォルダ出し入れ（Phase D以降、未着手）。ボタン使用後は Home へ戻る現状維持（Phase C で再検討）。詳細な調査ログ・コード根拠は `docs/Morrowa_AppDrawer_編集モード_実装計画.md` §9 を参照。
