# Morrowa App Drawer 編集モード（並び替え・フォルダ作成）実装計画

作成日: 2026-07-04
位置付け: `docs/Morrowa_AppDrawer_実装計画.md`（App Drawer 由来 drag で `Uninstall` / `Add to home screen` バーを出す機能、実装・ビルド確認済み）の後続タスク。

## 1. 背景

`docs/Morrowa_AppDrawer_実装計画.md` の機能を実装した後、実機確認の過程でユーザーから仕様の追加要望が出た。

現状（実装済み）:

- App Drawer 内のアプリアイコンを長押しすると drag が始まる（これは元々 AOSP/Lawnchair にあった機能）。
- drag 中は `Workspace.onDragStart()`（`Workspace.java:558-563`）が無条件に `mLauncher.getStateManager().goToState(SPRING_LOADED)` を呼ぶため、**App Drawer 由来の drag でも強制的に Home 画面（SPRING_LOADED state）へ切り替わってしまう**。
- 画面上部には `Uninstall` / `Add to home screen` の2ボタンが出るが、これは Home 用の `DropTargetBar`（`res/layout/drop_target_bar.xml`）をそのまま流用し、ボタンの表示可否だけを drag source で出し分けている。

ユーザーが望む挙動はこれと異なり、**App Drawer 由来の drag では Home 画面へ遷移せず、App Drawer が開いたまま独自の編集モードに入る**というもの。

## 2. 確定した仕様（このセッションでの合意事項）

- **Home 画面側は無変更。** Home のアイコンをドラッグしたときの挙動、`SPRING_LOADED` state、上部バー（`削除` + `Uninstall`）は今まで通り。
- App Drawer でアプリアイコンを長押しして**指を動かし始めると**、Home の `SPRING_LOADED` とは別の、**App Drawer 専用の編集 state** に入る。
  - この間、Launcher の見た目は **App Drawer が開いたまま**（Home 画面には切り替わらない）。
- この編集 state の中では、Home の `SPRING_LOADED` で Workspace 上でできることと同等の操作が **App Drawer の中で** できる。
  - アプリの並び替え（ドラッグして順序変更）
  - アプリ同士をドラッグして **App Drawer 用フォルダ** を作成
  - 既存の App Drawer フォルダへの出し入れ（フォルダに追加 / フォルダから出す）
- 画面上部には **Home 用の上部バーとは別の、App Drawer 専用の上部バー** が表示され、`Uninstall` と `Add to home screen` の2つを出す。
  - グリッド上（バー以外の App Drawer 内）でアイコンを離した場合 → 並び替え / フォルダ作成 / フォルダ挿入として処理される。
  - 上部バーまで持っていって離した場合のみ `Uninstall` / `Add to home screen` が実行される。

## 3. 事前調査で判明した事実

Explore による調査結果（2026-07-04）を要約する。詳細な file:line は各項目末尾を参照。

### 3.1 既存の「App Drawer 用フォルダ」機能（Room ベース、設定画面のみ）

- 永続化は Workspace の `favorites` / `FolderInfo` とは完全に別の Room テーブル。
  - `lawnchair/src/app/lawnchair/data/folder/FolderEntity.kt`: `FolderInfoEntity`（`Folders` テーブル: `id`, `title`, `hide`, `rank`, `timestamp`）と `FolderItemEntity`（`FolderItems` テーブル: `id`, `folderId`, `rank`, `item_info`＝`ComponentKey` の文字列, `timestamp`）。**`rank` が並び順の永続化フィールド。**
  - `lawnchair/src/app/lawnchair/data/folder/service/FolderDao.kt`: 通常の Room DAO。
  - `lawnchair/src/app/lawnchair/data/folder/service/FolderService.kt`: `FolderWithItems` → 実行時の `com.android.launcher3.model.data.FolderInfo` へ変換（`mapToFolderInfo`）。`updateFolderWithItems` が membership + order をまとめて `rank = index` で書き込む。
  - `lawnchair/src/app/lawnchair/data/folder/model/FolderViewModel.kt`: `createFolder` / `renameFolder` / `updateFolderItems` / `deleteFolder`。各操作の後 `reloadHelper.reloadGrid()`（全体リロード、部分更新ではない）。
  - フォルダ**同士**の並び順は別途 `prefs.drawerListOrder`（カンマ区切り文字列 preference）で管理。
- **編集は現状すべて設定画面経由。App Drawer 本体でのドラッグ編集は存在しない。**
  - `lawnchair/src/app/lawnchair/ui/preferences/destinations/AppDrawerFoldersPreference.kt`: フォルダ一覧の Compose 設定画面（フォルダの並び替えのみ、ReorderablePreferenceGroup 使用）。
  - `lawnchair/src/app/lawnchair/ui/preferences/destinations/SelectAppsForDrawerFolder.kt`: フォルダの membership 編集画面。`PositionalReorderer` を使い、有効/無効セクション間のドラッグと並び替えを行う。
- `PositionalReorderer.kt`（`lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/PositionalReorderer.kt`）は **Compose 専用**（`sh.calvin.reorderable` の `LazyColumn` ベース）。パターン（フラットリスト + 仮想境界線でカテゴリ分け）は参考になるが、View/RecyclerView ベースの App Drawer グリッドにはそのまま使えない。
- `LawnchairAlphabeticalAppsList.kt` の並び順は、基底クラス `AlphabeticalAppsList.onAppsUpdated()`（`src/com/android/launcher3/allapps/AlphabeticalAppsList.java:239-260`）が **常にアルファベット順ソートを先に適用**してから `addAppsWithSections`（サブクラスの override ポイント）を呼ぶ構造。**「手動並び替え」を個々のアプリに適用するには、`addAppsWithSections` より上流の `onAppsUpdated()` 自体を override してソートを差し替える必要がある。**

### 3.2 Home 画面の SPRING_LOADED とフォルダ作成ロジック（参考モデル）

- state 定義: `src/com/android/launcher3/states/SpringLoadedState.java`。`FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED` などのフラグを持つ。
- `Workspace.onDragStart()`（`Workspace.java:558-563`）が drag source に関係なく無条件で `goToState(SPRING_LOADED)` を呼ぶ（`EDIT_MODE` 中だけ除外）。これが今回の問題の根本原因。
- フォルダ作成の決定ロジックはすべて `CellLayout` のセル座標ベース（`Workspace.java`）。
  - `manageFolderFeedback()`（`Workspace.java:2967`）: `getDistanceFromWorkspaceCellVisualCenter()` と `target.getFolderCreationRadius(targetCell)` で「他のアイコンに十分近いか」を判定。
  - `willCreateUserFolder(...)` が true → `setDragMode(DRAG_MODE_CREATE_FOLDER)`（`Workspace.java:2996`）。
  - `willAddToExistingUserFolder(...)` が true（`FolderIcon` へのドロップ）→ `setDragMode(DRAG_MODE_ADD_TO_FOLDER)`（`Workspace.java:3012`）。
  - それ以外は `ReorderAlarmListener.onAlarm()`（`Workspace.java:3029`）→ `CellLayout.performReorder(...)` → `DRAG_MODE_REORDER`。
  - `onDragExit()`（`Workspace.java:2598`）が最後の `mDragMode` を `mCreateUserFolderOnDrop` / `mAddToExistingFolderOnDrop` に確定。
  - 実際の `onDrop()`（`Workspace.java:2296`）で `createUserFolderIfNecessary(...)`（`Workspace.java:2211`）または `addToExistingFolderIfNecessary(...)` を実行。
  - `FolderIcon`（`src/com/android/launcher3/folder/FolderIcon.java`）は **`DropTarget` を実装していない**（`DraggableView, Reorderable` のみ）。フォルダ作成/挿入の判断は全て `Workspace` 側が握っている受動的な協力者に過ぎない。
- **重要な示唆**: このパターンは完全に `CellLayout` のセル座標に依存している。App Drawer（`RecyclerView`）へ移植するには、セル座標の代わりに RecyclerView の item 位置/矩形を使う同等のステートマシン（`DRAG_MODE_NONE/CREATE_FOLDER/ADD_TO_FOLDER/REORDER` 相当）を作る必要があり、**移植ではなく新規実装**になる。

### 3.3 `LauncherState` に新しい state を追加する方法

- `LauncherState.java`: `sAllStates = new LauncherState[11]`（固定長配列、line 119）。新 state には新しい ordinal が必要（`shared/src/.../TestProtocol.java` の `NORMAL=0 … EDIT_MODE=10` に続けて `11` を追加し、配列サイズも 12 に拡張）。
- state 遷移のディスパッチは `StateManager`（`src/com/android/launcher3/statemanager/StateManager.java`）が `mStateHandlers` に対して行うが、**`StateHandler` は `AllAppsTransitionController` と `Workspace` の2つだけ**（`Launcher.java:2625-2629` の `collectStateHandlers()`）。つまり新 state を追加しても新規 `StateHandler` 登録は不要で、既存2クラスが新 state 用の値を返すよう対応すればよい。
- **「All Apps が見えたまま drag できる」state は現状存在しない。** `AllAppsState`（`quickstep/src/com/android/launcher3/uioverrides/states/AllAppsState.java`、および `src_no_quickstep` に同名ファイルあり＝**2つの build flavor で同期が必要**）は `getVerticalProgress()=0`（drawer全開）だが `FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED` は持たない。新 state はこれと `SpringLoadedState` の中間のような性質（drawer 全開 + drag 許可）を持つ必要がある。
- 遷移の差し込み点は2箇所考えられる。
  - (a) `ItemLongClickListener.onAllAppsItemLongClick()`（`touch/ItemLongClickListener.java:138-179`）で drag 開始前に新 state へ `goToState`。
  - (b) `Workspace.onDragStart()` の無条件 `goToState(SPRING_LOADED)`（line 561-563）に、`dragObject.dragSource instanceof ActivityAllAppsContainerView` の場合は別 state へ行くよう条件分岐を追加。
  - (a) の方が「drag が実際に始まる前、long-click 時点で source が確定している」ため確実性が高い。

### 3.4 App Drawer（RecyclerView）側の drag/drop 受け入れ余地

- `BaseAllAppsAdapter`（`src/com/android/launcher3/allapps/BaseAllAppsAdapter.java`）は素の `RecyclerView.Adapter`。`AdapterItem` に drag/reorder 用のフィールドはない。
- `AllAppsRecyclerView`（`src/com/android/launcher3/allapps/AllAppsRecyclerView.java`）に `ItemTouchHelper` や drag scaffolding は **一切ない**（443行全読了で確認）。
- `ActivityAllAppsContainerView`（`src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java`）は `DragSource` は実装しているが **`DropTarget` は実装していない**。`onDropCompleted()` も no-op。つまり **App Drawer は drag を発生させることはできるが、drop を受け取ることは現状一切できない。**
- **drag 機構の衝突リスク（最重要論点）**: 既存の long-press drag は `DragController` ベースの drag（`Workspace.beginDragShared` 経由、`DragLayer` 上で `DragView` が指に追従する）。これは Workspace drag や Uninstall/Add-to-home-screen バーと**同一の `DragController` インスタンス**を使う。RecyclerView の一般的な並び替え手段である `ItemTouchHelper` は、これとは別の touch interception モデル（RecyclerView 自身の `OnItemTouchListener`）で動く。**両者を同時に使うのは技術的に噛み合わない。**
  - 結論: `ItemTouchHelper` は使わない。既存の `DragController` drag をそのまま使い続け、drag 中の座標を `RecyclerView.findChildViewUnder(x, y)` + `getChildAdapterPosition()` で継続的にヒットテストし、Workspace の `onDragOver`/`manageFolderFeedback`/`ReorderAlarmListener` 相当のロジックを RecyclerView 版として自前実装する。
  - `ActivityAllAppsContainerView` が新たに `DropTarget` を実装し、`DragController.addDropTarget(...)` へ自己登録する必要がある。

### 3.5 2つ目の DropTargetBar（Home 用とは別）

- `res/layout/drop_target_bar.xml` は1つの `DropTargetBar` に3ボタン（`delete_target_text` / `uninstall_target_text` / `add_to_homescreen_target_text`）が入っている構造（現行実装）。
- `res/layout/launcher.xml` で `<include layout="@layout/drop_target_bar" />` が1箇所だけ存在し、`Launcher.java` で `mDropTargetBar.setup(mDragController)` される（`DragController` は**アプリ全体で1インスタンスのみ**）。
- `DragController.findDropTarget(x, y)`（`dragndrop/DragController.java:577-604`）は登録順の逆順で hit-rect 判定するだけで、drag source によるスコープ分けの仕組みは**存在しない**。よって「2つのバーの共存」は完全に **表示制御（可視性）側の責任** になる。両バーのボタンが同時に `VISIBLE` かつ矩形が重なることがないよう、各バー自身の `onDragStart` で drag source を見て自分自身を出すか出さないか決める必要がある。
- **`DropTargetBar` をそのまま2つ目として使うのは不適切**: `onLayout`/`onMeasure`（`DropTargetBar.java:142-292`）が Workspace のジオメトリ（`getWorkspaceSpringLoadScale`, `ws.getLeft()/getRight()`, `dp.workspacePadding` 等）を直接参照して中央寄せしており、この計算は Home 画面の spring-loaded 表示に強く結びついている。App Drawer 用には、この配置ロジックを override した別クラス（または App Drawer のジオメトリを参照する新規クラス）が必要。
- 現行実装（`Morrowa_AppDrawer_実装計画.md` で作った `AddToHomescreenDropTarget` / `DeleteDropTarget` のフィルタリング）は「同じバーの中でボタンを出し分ける」方式だったが、今回の仕様（Home 用バーと App Drawer 用バーは別物）に合わせて **整理し直す**（後述 §5 Phase C）。

### 3.6 未検証・要フォローアップ

- `AllAppsSwipeController`（`Launcher.java:2631-2633` の `createTouchControllers()`）は All Apps の開閉スワイプを担う既存 `TouchController`。新 state の touch 処理と競合しないか、実装前に確認が必要。
- `src_no_quickstep` の `AllAppsState.java` が quickstep 版と挙動差分を持つか未確認。新 state を追加する場合、両 flavor での同期が必要になる可能性が高い。
- Lawnchair の App Drawer フォルダが使う `FolderInfo`（`FolderService.mapToFolderInfo`）が Workspace の `FolderInfo` と型として完全に独立しているか、`id`/`container` を「Workspace に存在しない」状態で流用しているだけかは未確認。フォルダ作成/挿入の永続化設計前に確認が必要。

## 4. アーキテクチャ設計方針（案）

1. **新しい `LauncherState`** を追加する（仮称 `DRAWER_EDIT`）。`AllAppsState` 相当の見た目（drawer 全開）に、drag 許可の意味を持たせる。`sAllStates` 配列拡張、`TestProtocol` ordinal 追加、両 build flavor（quickstep / no_quickstep）での対応。
2. **状態遷移の差し込み**: `ItemLongClickListener.onAllAppsItemLongClick()` で drag 開始前に `DRAWER_EDIT` へ `goToState`。`Workspace.onDragStart()` の無条件 `goToState(SPRING_LOADED)` は、drag source が App Drawer の場合はスキップする条件を追加（`mIsAppDrawerDrag` 相当の判定は `AddToHomescreenDropTarget`/`DeleteDropTarget` で既に使っているものと同じ判定方法）。
3. **`ActivityAllAppsContainerView` に `DropTarget` を実装**し、`DragController.addDropTarget(...)` へ登録。drag 中の座標を RecyclerView の子ビュー hit-test に変換し、Workspace の `DRAG_MODE_*` ステートマシンに相当するロジックを RecyclerView item 位置ベースで実装する（並び替え / フォルダ作成 / 既存フォルダへの挿入）。
4. **App Drawer 専用の新しい DropTargetBar 相当クラス**（Home 用とは別インスタンス・別レイアウト）を追加し、`Uninstall` / `Add to home screen` を表示する。可視性は「drag source が App Drawer のときだけこちらを出し、Home 用バーは出さない」という排他制御にする。**現行実装の「同一バー内でボタンを出し分ける」方式はここで置き換える。**
5. **永続化は既存の Lawnchair App Drawer フォルダ基盤（`FolderEntity`/`FolderItemEntity`/`FolderDao`/`FolderService`/`FolderViewModel`）を再利用**する。並び替えは `rank` フィールドへの書き込み、フォルダ作成も同じ Room テーブルへの insert。
6. **個々のアプリ（フォルダ外）の手動並び替え**は、`LawnchairAlphabeticalAppsList` が `AlphabeticalAppsList.onAppsUpdated()` 自体を override してアルファベットソートを差し替える必要がある。ここは App Drawer の検索・高速スクロール・work profile 表示など基本機能に影響しうる高リスク領域として扱う。

## 5. フェーズ分割

大きな変更なので、1タスクずつ小さく進める。各フェーズは独立してビルド確認・検証できるようにする。

### Phase A: 新 `LauncherState` の追加のみ（振る舞いはまだ繋がない）

対象: `LauncherState.java`、`TestProtocol.java`、新規 state class（`AllAppsState` を参考に）、quickstep / no_quickstep 両 flavor。
実装内容: `DRAWER_EDIT` state を定義するだけ。まだ drag とは連動させない。既存 state 遷移には一切影響しないことを確認する。
受け入れ条件: 新 state を追加してもビルドが通り、既存の Home / All Apps / Overview 遷移が壊れていない。
検証方法: Kotlin/Java compile、実機で通常の Home ⇔ All Apps ⇔ Overview 遷移が壊れていないことを確認。

### Phase B: App Drawer 由来 drag でこの新 state へ入るようにする

対象: `ItemLongClickListener.java`、`Workspace.java`（`onDragStart` の条件分岐）。
実装内容: App Drawer 長押し drag 開始時に `DRAWER_EDIT` へ遷移。`Workspace.onDragStart()` の無条件 `SPRING_LOADED` 遷移を App Drawer 由来では skip。
やらないこと: 並び替え・フォルダ作成はまだ実装しない。
受け入れ条件: App Drawer でアイコンを長押しして動かしても Home 画面へ切り替わらず、Drawer が開いたままになる。Home 画面からの drag は今まで通り `SPRING_LOADED` に入る。
検証方法: 実機で、App Drawer 由来 drag と Home 由来 drag の両方を確認。

### Phase C: App Drawer 専用の上部バー

対象: 新規レイアウト/クラス、`Launcher.java`（2つ目のバーの setup）、既存 `AddToHomescreenDropTarget`/`DeleteDropTarget` のフィルタリングの整理。
実装内容: Home 用バーとは別の、App Drawer 専用バー（`Uninstall` / `Add to home screen`）を追加。表示は排他制御。
やらないこと: 並び替え・フォルダ作成はまだ実装しない。
受け入れ条件: App Drawer 由来 drag では App Drawer 専用バーだけが出る。Home 由来 drag では Home 用バー（削除 + Uninstall）だけが出る。両方が同時に出ることはない。
検証方法: 実機で両方の drag を確認。

### Phase D: RecyclerView 座標ベースの並び替え

対象: `ActivityAllAppsContainerView.java`（`DropTarget` 実装）、`BaseAllAppsAdapter`、永続化（`FolderViewModel`/`FolderDao` 相当、非フォルダアプリの順序は要検討）、`LawnchairAlphabeticalAppsList.onAppsUpdated()`。
実装内容: フォルダ以外の単純な並び替えのみ実装。
やらないこと: フォルダ作成・フォルダ挿入はまだ実装しない。
受け入れ条件: App Drawer 内でアプリをドラッグして並び替えでき、並び順が再起動後も保持される。検索・高速スクロールが壊れていない。
検証方法: 実機確認 + 既存の検索/スクロール回帰確認。

### Phase E: フォルダ作成

対象: Phase D の基盤 + `FolderEntity`/`FolderDao`/`FolderService`。
実装内容: アプリ同士をドラッグして新規 App Drawer フォルダを作成。
受け入れ条件: アプリ A を アプリ B にドラッグすると、両方を含む新しい App Drawer フォルダが作られる。
検証方法: 実機確認。

### Phase F: 既存フォルダへの出し入れ

対象: Phase E の基盤。
実装内容: 既存の App Drawer フォルダへアプリをドラッグして追加、フォルダ内からドラッグして出す。
受け入れ条件: フォルダへの追加・除去ができ、Room 側に反映される。
検証方法: 実機確認。

### Phase G: 回帰確認・実機総合検証

対象: 全体。
実装内容: なし（検証のみ）。
受け入れ条件: `docs/Morrowa_AppDrawer_実装計画.md` 側の受け入れ条件（Uninstall/Add to home screen）と、本計画の Phase A〜F の受け入れ条件がすべて同時に満たされている。Home 画面の既存機能（配置、フォルダ、透明化、ウィジェット）が壊れていない。
検証方法: 実機での総合確認。

## 6. リスク

- `LauncherState.sAllStates` 配列サイズ変更・`TestProtocol` ordinal 追加は AOSP 由来コードへの変更であり、影響範囲の見極めが必要（テストコードや計装コードが ordinal を参照している可能性）。
- 新 state を `quickstep` / `src_no_quickstep` 両 flavor に反映する必要が生じる可能性が高く、片方だけ直すと flavor 間で挙動が割れるリスクがある。
- `DragController` はアプリ全体で単一インスタンスのため、2つのバーの可視性制御を誤ると同時表示・取り合いが起きるリスクがある（§3.5参照）。
- `AlphabeticalAppsList.onAppsUpdated()` のソートロジック変更は、検索・高速スクロール・work/private profile 表示など App Drawer の基本機能全体に影響しうる高リスク領域。
- RecyclerView 座標ベースのフォルダ作成/並び替えは既存に参考実装がなく、ほぼ新規実装（`Workspace` の `CellLayout` 版ロジックの移植ではなく再設計）になる。

## 7. 今回やらないこと（Non-goals）

- Home 画面の `SPRING_LOADED`、上部バー、`createUserFolderIfNecessary` など既存フォルダ作成ロジックの変更。
- Workspace 上のフォルダと App Drawer 用フォルダのデータ統合。
- クラウド同期、複数デバイス間の並び順共有。
- Habit / ToDo 固定画面への影響（本計画は Home / App Drawer のみが対象）。

## 8. 次のアクション

このセッションでは Phase A から着手する。Phase A は最小差分（新 state の追加のみ、既存動作へ未接続）にとどめ、ビルド確認後に Phase B へ進む。

## 9. 実装記録

### 9.1 Phase A: 新 `LauncherState` の追加のみ（2026-07-04・完了）

変更ファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/states/DrawerSpringLoadedState.java`（新規） | `LauncherState` を継承する新 state。Phase A では `ALL_APPS` と視覚的に等価（`getVerticalProgress()=0`, `getVisibleElements()=ALL_APPS_CONTENT\|FLOATING_SEARCH_BAR`）にとどめ、まだ drag には未接続 |
| `src/com/android/launcher3/LauncherState.java` | `sAllStates` 配列サイズを 11→12 に拡張、`DRAWER_SPRING_LOADED` state を追加 |
| `src/com/android/launcher3/testing/shared/TestProtocol.java` / `shared/src/com/android/launcher3/testing/shared/TestProtocol.java`（内容が完全に重複する2ファイル、両方に同じ変更を適用） | `DRAWER_SPRING_LOADED_STATE_ORDINAL = 11` を追加、`stateOrdinalToString()` に case 追加 |

設計判断:

- `AllAppsState`（`quickstep`/`src_no_quickstep` の2 flavor に分かれて存在）を継承・複製する代わりに、`SpringLoadedState` / `EditModeState` と同じ flavor 非依存パッケージ（`src/com/android/launcher3/states/`）に配置し、`LauncherState` を直接継承した。これにより両 flavor 間の同期不要という調査時点のリスクを回避した。
- `TestProtocol.java` が `src/` と `shared/src/` に内容の完全に重複する2ファイルとして存在する（既存の重複、原因未調査）。どちらが実際にビルドで使われているか切り分けられなかったため、両方に同一の変更を適用して同期を保った。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 33s`。新規/変更ファイル起因のエラーなし。

未実施: 実機での動作確認（この Phase では見た目の変化がないはずなので、既存の Home ⇔ All Apps ⇔ Overview 遷移が壊れていないことの確認が中心になる）。

### 9.2 Phase B: App Drawer 由来 drag をこの新 state へ入れる（2026-07-04・完了）

変更ファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/Workspace.java` | `onDragStart()`（line 558-563 相当）の無条件 `goToState(SPRING_LOADED)` を、`dragObject.dragSource instanceof ActivityAllAppsContainerView` の場合は `goToState(DRAWER_SPRING_LOADED)` へ分岐するよう変更。`ActivityAllAppsContainerView` の import と `DRAWER_SPRING_LOADED` の static import を追加 |

実装方針:

- 当初計画の「(a) `ItemLongClickListener` で drag 開始前に新 state へ遷移」「(b) `Workspace.onDragStart` の条件分岐」の2案のうち、(b) を採用した。`onDragStart(DragObject dragObject, ...)` は `dragObject.dragSource` を直接受け取れるため、事前に state を切り替えてから `!isInState(EDIT_MODE)` のような間接的な除外条件を書くより、その場で drag source を見て分岐する方が確実で差分も小さい。
- `ItemLongClickListener.java` は変更していない。
- `AddToHomescreenDropTarget` / `DeleteDropTarget` で使っているのと同じ `dragObject.dragSource instanceof ActivityAllAppsContainerView` の判定方法を再利用し、判定方法を1箇所に増やさないようにした。

既知の未対応事項（この Phase の意図的なスコープ外）:

- App Drawer 由来 drag 中に、上部バー（Uninstall/Add to home screen、現状はまだ Home 用バーと共用）以外の場所で指を離した場合、`ActivityAllAppsContainerView` はまだ `DropTarget` を実装していないため、`LauncherDragController.getDefaultDropTarget()` のフォールバックにより **Workspace が既定の drop target として drop を受け取ってしまう**。つまり、見た目は Drawer が開いたままでも、実際には裏の Workspace の該当座標にアイコンが配置される可能性がある。これは Phase D（App Drawer 自身が `DropTarget` を実装し、並び替え/フォルダ作成を担当するようになる）まで解消されない、既知の暫定的なギャップである。
- `Workspace.onDragStart()` 内の `addExtraEmptyScreenOnDrag(dragObject)` 呼び出し（line 536-556 相当）は drag source に関わらず実行され続ける。App Drawer 由来 drag でも Workspace に空きページが追加され得るが、これは本 Phase 開始前から存在した挙動であり、今回変更していない。
- drag 終了時に `NORMAL` へ強制的に戻す既存コード（`DropTargetHandler.onDropAnimationComplete()` が無条件で `goToState(NORMAL)` を呼ぶ、`ButtonDropTarget.onDrop()` 経由）も未変更。Uninstall / Add to home screen ボタンを使った場合、ドロップアニメーション完了後に Home へ遷移してしまう。Drawer 専用バー実装（Phase C）と合わせて見直す。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 4s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（App Drawer 由来 drag で Home 画面へ切り替わらず Drawer が開いたままになるか、Home 由来 drag は今まで通り `SPRING_LOADED` に入るか）。

### 9.3 Phase B 実機確認で発覚したバグ修正: `FLAG_CLOSE_POPUPS`（2026-07-04）

実機確認の結果、`DRAWER_SPRING_LOADED` state へは正しく遷移するものの、アイコンを実際に動かそうとするとアイコンが透明になり、指に追従しなくなる不具合が判明した。

原因:

- `DrawerSpringLoadedState` の `STATE_FLAGS` に、`AllAppsState`（quickstep 版）からそのままコピーした `FLAG_CLOSE_POPUPS` が含まれていた。
- `FLAG_CLOSE_POPUPS` を持つ state へ遷移すると、`StatefulContainer.onStateSetStart()`（`statemanager/StatefulContainer.java:80`）が即座に `AbstractFloatingView.closeAllOpenViews(...)` を呼ぶ。
- 一方、共存させている長押しショートカット popup（`PopupContainerWithArrow`）は、`createPreDragCondition()`（`popup/PopupContainerWithArrow.java:635-685`）の `PreDragCondition` と、`onDragStart()`（同 line 691-696、`animateClose()` を呼ぶ）という**自前の**タイミングで popup を閉じ、隠していた元アイコンの可視性を復元する仕組みを持っている。
- 今回の drag 開始（`Workspace.onDragStart()` → `goToState(DRAWER_SPRING_LOADED)`）は、まさに popup が持つこの自前のクローズ処理と同じタイミングで発生するため、`FLAG_CLOSE_POPUPS` 経由の強制クローズが popup 自身のクローズ処理と競合し、元アイコンの可視性復元（`onPreDragEnd` 相当の処理）が正しく走らないまま popup だけが消え、アイコンが `INVISIBLE` のまま固まっていた。
- Home 用の `SpringLoadedState`（`Workspace.onDragStart()` から同じタイミングで遷移する既存 state）は、この理由から**意図的に `FLAG_CLOSE_POPUPS` を持っていない**。今回はこの前例を見落としてコピーしてしまっていた。

対応:

- `DrawerSpringLoadedState.java` の `STATE_FLAGS` から `FLAG_CLOSE_POPUPS` を削除（`FLAG_WORKSPACE_INACCESSIBLE | FLAG_HOTSEAT_INACCESSIBLE` のみに変更）。
- 理由をコメントとして残し、同じ轍を踏まないようにした。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
.\gradlew.bat installLawnWithQuickstepGithubDebug --console=plain
```

結果: 両方 `BUILD SUCCESSFUL`。実機 `SC-52C - 16` へ再インストール成功、起動・プロセス生存確認、crash ログなし。

未実施: ユーザーによる実機再確認（App Drawer でアイコンを長押しして動かした際、透明にならず指に追従するか）。

### 9.4 実機再確認結果（2026-07-04）: 問題継続中・原因未特定

`FLAG_CLOSE_POPUPS` 削除後に再インストールして確認したが、ユーザー報告によると**問題はまだ継続している**（透明になり動かない状態が再発、または解消していない）。

現時点のステータス:

- `FLAG_CLOSE_POPUPS` の除去は正しい修正だと考えられるが（Home 用 `SpringLoadedState` の前例と整合し、理論的な原因説明とも合致するため）、それだけでは症状が解消しなかった、または他に少なくとも1つ別の原因が存在する。
- 未調査の候補:
  - `Workspace.onDragStart()` 内の他の処理（`addExtraEmptyScreenOnDrag` 等、§9.2 で「未変更」と記録した箇所）が `DRAWER_SPRING_LOADED` state 中に Workspace 側で何らかの副作用を起こしている可能性。
  - `AllAppsTransitionController`（`StateHandler` としてもう1つの当事者）が `DRAWER_SPRING_LOADED` に対して想定外の値（alpha/scale）を返している可能性。`AllAppsState` と全く同じ `getVerticalProgress()`/`getVisibleElements()` を返しているが、`AllAppsTransitionController` 側が state の型やその他のプロパティ（フラグの組み合わせ等）を見て分岐している場合、`AllAppsState` 以外の state では想定外の分岐に入る可能性がある。
  - `DragView` 自体の alpha 制御（`ButtonDropTarget`/`DropTargetBar` 側ではなく、drag 開始時の `DragView` 生成・表示ロジック）が、state ではなく `dragOptions.preDragCondition` の別の経路で影響を受けている可能性。
  - 複数の原因が重なっている可能性（`FLAG_CLOSE_POPUPS` は実際に一因だったが、単独の原因ではなかった）。
- 次のセッションでは、実機ログ（`adb logcat`）や `Workspace`/`AllAppsTransitionController`/`DragController` の該当コードをより詳細に追跡する調査から再開する。

このセッションはここで一旦区切り、詳細レポートを残した上でコミット・プッシュする。
