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

> **注（2026-07-05）**: 下記 1〜2 の「新しい `LauncherState`（`DRAWER_EDIT`）を追加する」方針は §9.5 で廃止した。App Drawer 由来 drag は state 遷移せず `ALL_APPS` のまま drag する（方針B）。3〜6 は引き続き有効。

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

### 9.5 設計転換: 独立 state を廃止し ALL_APPS のまま drag する方式へ（2026-07-05・決定）

§9.4 の調査再開時にコードを精読した結果、`DRAWER_SPRING_LOADED` という独立 state を維持する方針（方針A）そのものに構造的な問題があると判明し、ユーザー判断により **方針B（独立 state を廃止し、App Drawer 由来 drag では state 遷移を一切行わず ALL_APPS のままにする）** を採用した。

方針転換の根拠:

- **`Launcher.onStateSetEnd()`（`Launcher.java:1206-1212`）**: 直前の安定 state が `ALL_APPS` で遷移先が `ALL_APPS` 以外の場合、無条件に `getAppsView().reset(false)` を呼ぶ。`DRAWER_SPRING_LOADED` への遷移完了時（drag 開始の約150ms後）に App Drawer 全体がリセットされ（RecyclerView 先頭スクロール、ヘッダーリセット、`resetSearch` の post）、drag 中のグリッドが動いて元アイコンの View が recycle される。§9.4 の症状（アイコン透明化・追従停止）の時系列と合致する最有力原因。
- **codebase 中に「drawer が開いている＝ALL_APPS state」という同一性チェックが多数散在**: `LauncherAllAppsContainerView.isInAllApps()`（`isInStableState(ALL_APPS)`）、`ActivityAllAppsContainerView.onInterceptTouchEvent/onTouchEvent`（`:1198`, `:1220`、fast scroller のタッチ処理）、`AllAppsTransitionController.setAlphas()`（`:444`、ヘッダー保護 scrim）など。独立 state 方式ではこれらを1つずつ潰すモグラ叩きになり、未発見の同種箇所が残るリスクが高い。
- 方針Bなら state が `ALL_APPS` のまま変わらないため、`onStateSetEnd` のリセットも同一性チェック群もすべて既存のまま正しく動く。SPRING_LOADED が提供する価値（Workspace の縮小表示）は drawer が全画面を覆っている間は不要。

実装内容（2026-07-05）:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/Workspace.java` | `onDragStart()` の分岐を変更: drag source が `ActivityAllAppsContainerView` の場合は **state 遷移を行わない**（ALL_APPS のまま）。`DRAWER_SPRING_LOADED` の static import を削除 |
| `src/com/android/launcher3/states/DrawerSpringLoadedState.java` | **削除**（git 履歴には残る） |
| `src/com/android/launcher3/LauncherState.java` | `DRAWER_SPRING_LOADED` field と import を削除、`sAllStates` を 12→11 に戻す（Phase A 前の状態へ復元） |
| `src/.../TestProtocol.java` / `shared/src/.../TestProtocol.java` | `DRAWER_SPRING_LOADED_STATE_ORDINAL = 11` と `stateOrdinalToString` の case を両ファイルから削除 |

「編集モードかどうか」の判定は、既存の `dragObject.dragSource instanceof ActivityAllAppsContainerView`（`AddToHomescreenDropTarget` / `DeleteDropTarget` / `Workspace.onDragStart` で使用中）で表現し、新しいフラグや state は導入しない。将来 Phase C〜F で drag 外から編集モード判定が必要になった時点で、必要最小限のフラグを検討する。

Phase A/B の受け入れ条件の読み替え: 「`DRAWER_SPRING_LOADED` へ遷移する」は廃止。新しい受け入れ条件は「App Drawer 由来 drag 中、Launcher state が `ALL_APPS` のまま変化せず、Drawer が開いたままになる」。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 2m 36s`。`DRAWER_SPRING_LOADED` / `DrawerSpringLoaded` への参照が repo 内に残っていないことを grep で確認済み。

未実施 / 次の確認事項:

- 実機確認: App Drawer でアイコンを長押しして動かしたとき、(1) 透明化せず指に追従するか、(2) Drawer が開いたままか、(3) 上部バーの `Uninstall` / `Add to home screen` が Drawer の上に表示されるか（ALL_APPS state のまま drag するのは AOSP に前例がないため、`DropTargetBar` と apps view の z-order は実機で要確認）、(4) Home 由来 drag が従来通り `SPRING_LOADED` に入るか。
- §9.2 で記録した既知ギャップは方針Bでも継続: バー以外で離すと既定 drop target の Workspace が drop を受け取る（Phase D で解消予定）、`DropTargetHandler.onDropAnimationComplete()` がバーのボタン使用後に `goToState(NORMAL)` を呼ぶ（Phase C で見直し予定）。

### 9.6 実機確認結果（2026-07-05）: 透明化バグは解消、新たに2件確認

ユーザーによる実機確認の結果:

| 項目 | 結果 |
|---|---|
| (1) 透明化せず指に追従するか | **解消**。透明化しなくなり、アイコンが指に追従するようになった |
| (2) Drawer が開いたままか | **OK**。裏に Home 画面は表示されない |
| (3) 上部バーが Drawer の上に表示されるか | **NG**。上部バー（`Uninstall` / `Add to home screen`）が **Drawer の後ろに隠れて**見えなくなっている |
| (4) Home 由来 drag が従来通りか | 明示的な問題報告なし |
| 追加報告 | ドラッグしてもアプリ同士の並び替えはできず、指に追従するだけ。指を離すと確認なしに強制的に Home へ戻る |

「並び替えができない」「指を離すと Home に戻る」は §9.2 / §9.5 の「既知ギャップ」として記録済みの内容と一致する（並び替えは Phase D 未実装、Home へ戻る強制遷移は `DropTargetHandler.onDropAnimationComplete()` / Workspace 既定 drop target 経由、Phase C/D で対応予定）。**新規に確認が必要なのは (3) の上部バー z-order のみ**。

#### (3) の原因調査

`res/layout/launcher.xml` の `DragLayer` 内の子 View 宣言順序（前面と背面の順序 = XML の宣言順 = 描画順）を確認した。

```xml
<DragLayer>
  <AccessibilityActionsView />
  <Workspace />
  <include layout="@layout/hotseat" />
  <PageIndicatorDots />
  <include android:id="@+id/drop_target_bar" layout="@layout/drop_target_bar" />   <!-- line 62-64 -->
  <LawnchairScrimView android:id="@+id/scrim_view" />
  <include android:id="@+id/overview_panel" layout="@layout/overview_panel" />
  <include android:id="@+id/apps_view" layout="@layout/all_apps" />                <!-- line 76-80: 最後の子 = 最前面 -->
</DragLayer>
```

`apps_view`（All Apps container）が `DragLayer` の**最後の子**であり、Android のビュー階層では後に宣言された兄弟ビューほど前面に描画される。つまり `drop_target_bar` は常に `apps_view` より**背面**にある。

これは元々問題にならなかった。Home 由来 drag では state が `SPRING_LOADED` に遷移し、`apps_view`（All Apps）は非表示（alpha 0 / 不可視）になるため、背面にある `drop_target_bar` が問題なく見えていた。

今回の方針B（App Drawer 由来 drag では `ALL_APPS` state のまま drag する）により、**`apps_view` が可視のまま drag 中も表示され続ける**ため、この元々の z-order（`drop_target_bar` が `apps_view` より背面）が初めて可視化された。`docs` に事前に記録していた「ALL_APPS state のまま drag するのは AOSP に前例がないため z-order は実機確認が必須」という懸念が的中した形。

対応方針（次回実装予定、未着手）:

- `res/layout/launcher.xml` で `drop_target_bar` の `<include>` を `apps_view` の**後**に移動し、`drop_target_bar` を `DragLayer` の最後の子にする。
- 懸念点: Home 由来 drag / 通常時（drag なし）に `drop_target_bar` は `android:visibility="invisible"` かつ子ボタンも非 active 時は `GONE` になるため、宣言順序を変えても見た目には影響しないはずだが、実機で Home 由来 drag の見た目が変わっていないことを合わせて確認する。
- `scrim_view` / `overview_panel` より前面になる点も、通常 drag 中はこの2つが影響しないはずだが要確認。

→ 詳細な原因確定と修正計画は §9.7 にまとめ直した。

### 9.7 原因調査結果と修正計画（2026-07-05・コード未変更）

§9.6 の実機確認で残った2件（(3) 上部バーが Drawer の裏に隠れる、(5) 指を離すと確認なしに Home へ強制的に戻る）について、コードを変更せずに原因を確定させた。

#### 9.7.1 (3) 上部バー z-order: 原因確定

- `launcher.xml` は **2ファイル存在する**: `res/layout/launcher.xml`（main sourceSet、`build.gradle:283`）と `lawnchair/res/layout/launcher.xml`（lawnchair flavor sourceSet、`build.gradle:299`）。resource merge では flavor 側（`lawnchair/res`）が優先されるため、**実際にビルドで使われるのは `lawnchair/res/layout/launcher.xml`**。ただし両ファイルとも子の宣言順は同一（`drop_target_bar` → `scrim_view` → `overview_panel` → `apps_view` の順）で、どちらも `apps_view` が最後の子＝最前面。
- `DragLayer` の兄弟 View は宣言順に描画される（後の子ほど前面）ため、`drop_target_bar` は常に `apps_view` の背面。Home 由来 drag では `SPRING_LOADED` 遷移で `apps_view` が不可視になるため今まで顕在化しなかった（§9.6 の分析どおり）。
- なお、drop 判定は z-order と無関係（`DragController.findDropTarget()` は登録済み `DropTarget` の hit-rect 判定）なので、**現状でもバーの位置まで持っていけば Uninstall / Add to home screen は機能しているはず**（見えないだけ）。

#### 9.7.2 (5) 指を離すと Home へ強制遷移: 原因確定

grid 上（バー以外）で指を離したときの完全な経路:

1. `DragController.drop()` が `findDropTarget(x, y)` で drop 先を探す。`ActivityAllAppsContainerView` は `DropTarget` 未実装（§3.4）のため、画面全体をカバーする **Workspace が drop 先として選ばれる**。
2. `Workspace.acceptDrop()`（`Workspace.java:2091`）→ `transitionStateShouldAllowDrop()`（`:2082-2085`）→ `workspaceIconsCanBeDragged()`（`:1773`）は「現 state が `FLAG_WORKSPACE_ICONS_CAN_BE_DRAGGED` を持つか」を見る。**`ALL_APPS` はこのフラグを持たないため drop は拒否される**。
   - 副産物として良い知らせ: §9.2 で懸念した「裏の Workspace にアイコンが配置される」は方針Bでは**起きない**（`onDropExternal` まで到達しない）。実機報告で Home にアイコンが増えていないこととも整合する。
3. drop 拒否（accepted=false）→ `DragController.dispatchDropComplete()`（`dragndrop/DragController.java:291-300`）が `exitDrag()` を呼ぶ。
4. `LauncherDragController.exitDrag()`（`dragndrop/LauncherDragController.java:246-250`）: `!mIsInPreDrag && !isInState(EDIT_MODE)` なら**無条件に `goToState(NORMAL, SPRING_LOADED_EXIT_DELAY)`**。これが強制 Home 遷移の正体。
   - back ジェスチャー等での drag キャンセル（`DragController.cancelDrag()` `:276-289`）も同じ `dispatchDropComplete(null, false)` → `exitDrag()` を通るため、同様に Home へ戻る。

つまり (5) は Phase D（App Drawer の DropTarget 実装）を待たなくても、`exitDrag()` の1箇所で止められる。

#### 9.7.3 修正計画（次セッションで実装）

**修正1: 上部バーの z-order（(3) 対応）**

- 対象: `lawnchair/res/layout/launcher.xml` と `res/layout/launcher.xml` の**両方**（TestProtocol と同じく、重複ファイルは同期を保つ）。
- 内容: `drop_target_bar` の `<include>` を `apps_view` の後（`DragLayer` の最後の子）へ移動。
- 影響範囲の見立て: 実行時に addView される View（DragView、フォルダ、ポップアップ等）は常に静的子より後＝前面なので影響なし。バーが `scrim_view` / `overview_panel` より前面になるが、バーは非 drag 時 `invisible` なので Overview / 通常時は不変。Home 由来 drag ではバーが scrim より前面に出る分だけ発色が変わる可能性があるので実機確認。
- 代替案（修正1で Home 側に見た目の回帰が出た場合のみ）: XML は変更せず、App Drawer 由来 drag の `onDragStart` でのみ `DropTargetBar` に `bringToFront()` または高 elevation を適用し、drag 終了で戻す。

**修正2: 強制 Home 遷移の抑止（(5) 対応）**

- 対象: `LauncherDragController.exitDrag()`（`dragndrop/LauncherDragController.java:246-250`）。
- 内容: `mDragObject.dragSource instanceof ActivityAllAppsContainerView`（既存の判定イディオムを再利用）の場合は `goToState(NORMAL)` をスキップし、`ALL_APPS` のままにする。
- 期待挙動: grid 上で指を離す → drop は拒否され、DragView は即時削除（`deferDragViewCleanupPostAnimation = false` → `endDrag()` で `dragView.remove()`）、**Drawer が開いたまま元の状態に戻る**。並び替えが起きないのは Phase D までの仕様どおり。
- 留意点1: DragView が「元の位置へ飛んで戻る」アニメーションは付かない（即消え）。フライバックが欲しければ Phase D で `ActivityAllAppsContainerView` が DropTarget になった際に実装する。
- 留意点2: 元アイコンの可視性（長押しポップアップの `PreDragCondition` が隠した後の復元）が drag 終了後に正しく戻るか実機確認する。
- 留意点3: `Workspace.onDragEnd()`（`Workspace.java:585-609`）は「次に NORMAL へ遷移したとき」に extra empty screen を除去する listener を登録する。修正2で NORMAL へ行かなくなると、drag 開始時に `addExtraEmptyScreenOnDrag()`（§9.2）で追加された空きページの除去が「次回 Home に戻るまで」遅延する。実害は薄いが、あわせて **`Workspace.onDragStart()` で App Drawer 由来 drag では `addExtraEmptyScreenOnDrag()` 自体をスキップ**する変更を修正2に含めることを検討する（App Drawer 由来 drag が Workspace に drop されることは 2. の通りもう無いため、空きページ追加は不要）。

**修正3（要仕様判断・未決定）: バーのボタン使用後の遷移先**

- 現状: `Uninstall` / `Add to home screen` へ drop した場合は accepted=true なので修正2の経路は通らず、`DropTargetHandler.onDropAnimationComplete()`（`DropTargetHandler.kt:26-28`）の無条件 `goToState(NORMAL)` で Home へ戻る（§9.2 の既知事項）。
- 仕様判断が必要: `Add to home screen` 使用後は「配置結果が見える Home へ戻る」のが自然か、「編集を続けられる Drawer に留まる」のが自然か。`Uninstall` 使用後も同様。
- 推奨: MVP では**両ボタンとも使用後は Home へ戻る現状維持**とし（配置/削除の結果が見え、既存コード無変更）、Phase C の専用バー実装時に再検討する。

#### 9.7.4 修正1+2 実装記録（2026-07-05）

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/res/layout/launcher.xml` / `res/layout/launcher.xml`（両方） | `drop_target_bar` の `<include>` を `apps_view` の後（`DragLayer` の最後の子）へ移動 |
| `src/com/android/launcher3/dragndrop/LauncherDragController.java` | `exitDrag()` の先頭で `mDragObject.dragSource instanceof ActivityAllAppsContainerView` なら早期 return し、`goToState(NORMAL)` をスキップ |
| `src/com/android/launcher3/Workspace.java` | `onDragStart()` の `addNewPage` 判定に App Drawer 由来 drag の除外条件を追加し、`addExtraEmptyScreenOnDrag()` をスキップ（留意点3 対応） |

`exitDrag()` は `DragController.dispatchDropComplete()`（`accepted=false` のときのみ呼ばれる、`DragController.java:291-300` で確認済み）経由でのみ呼ばれるため、`Uninstall` / `Add to home screen` ボタンへの drop（`accepted=true`）には影響しない。修正3（ボタン使用後は Home へ戻る現状維持）と競合しないことをコードで確認済み。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 56s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（下記チェックリスト）。

**検証（修正1+2 実装後の実機確認項目）**

1. App Drawer 由来 drag 中、上部バーが Drawer の**上に**見える。
2. grid 上で指を離すと Drawer が開いたまま何も起きない（Home へ飛ばない、Workspace にアイコンが増えない、元アイコンが消えない）。
3. back ジェスチャーで drag をキャンセルしても Drawer に留まる。
4. バーへ drop すると Uninstall / Add to home screen が従来どおり動作する（遷移先は修正3の判断どおり）。
5. Home 由来 drag: バー表示（削除 + Uninstall）、drop、drag 後の NORMAL 復帰がすべて従来どおり。
6. Overview / フォルダ / ウィジェット等、`launcher.xml` の宣言順変更による見た目の回帰がない。

### 9.8 実機確認結果（2026-07-05）: 1〜3 完了、`Add to home screen` が機能しない

修正1+2 導入後の実機確認結果:

| 項目 | 結果 |
|---|---|
| 1. 上部バーが Drawer の上に見える | **完了** |
| 2. grid 上で指を離すと Drawer に留まる | **完了** |
| 3. back ジェスチャーでキャンセルしても Drawer に留まる | **完了** |
| 4. `Uninstall` | 機能する |
| 4. `Add to home screen` | **機能しない**（Home 画面にアイコンが追加されない） |
| 5, 6 | 未確認（今回はスコープ外として保留） |
| 追加報告 | ドラッグでのアプリ位置入れ替え（並び替え）はできない（Phase D 未実装、既知の仕様どおり） |

`Uninstall` は動くのに `Add to home screen` だけ動かないという非対称性から、`AddToHomescreenDropTarget` 固有の問題と判断し、コードを追跡して原因を確定した。

#### 原因確定

`ButtonDropTarget.onDrop()` の共通処理（`ButtonDropTarget.java`、drop アニメーション終了時の `onAnimationEndRunnable`）は、常に次の順で呼ぶ:

```java
completeDrop(d);                              // サブクラス固有の処理
mDropTargetBar.onDragEnd();
mDropTargetHandler.onDropAnimationComplete(); // 全ボタン共通、無条件に goToState(NORMAL)
```

旧実装の `AddToHomescreenDropTarget.completeDrop()` は `LauncherAccessibilityDelegate.addToWorkspace(item, false, null)` を呼んでいたが、この method 自体が内部で `mContext.getStateManager().goToState(NORMAL, true, forSuccessCallback(() -> { /* 実際の配置処理 */ }))` という**アニメーション成功コールバック待ちの遅延処理**になっている（`LauncherAccessibilityDelegate.java:448`）。

問題は、この `goToState(NORMAL, ...)` の直後（同じ `onAnimationEndRunnable` 内）で `mDropTargetHandler.onDropAnimationComplete()` が**再度** `goToState(NORMAL)` を呼ぶこと。`StateManager.goToState()`（`statemanager/StateManager.java:251-284`）は、遷移先が現在の `mState` と異なり、かつ「同じアニメーションの続行」条件（line 270-271）に当てはまらない場合、`cancelAnimation()`（line 284）で**進行中の遷移アニメーションをキャンセルする**。`AnimationSuccessListener`（`anim/AnimationSuccessListener.java`）はキャンセルされたアニメーションでは `onAnimationSuccess`（＝実際の配置処理を含む `forSuccessCallback` の中身）を呼ばない仕様（`mCancelled` フラグで抑制、line 38-42）。

2回の `goToState(NORMAL)` 呼び出しは同一コールスタック内でほぼ同時に発生するため、1回目（`addToWorkspace` 発の、配置処理を含む方）が2回目によってキャンセルされ、**配置処理が実行されないまま state だけ NORMAL（Home）へ遷移する**。見た目上は「ボタンへ drop したのに何も追加されずに Home へ戻る」という今回の症状と一致する。

`Uninstall`（`SecondaryDropTarget.completeDrop()`）はこの罠に引っかからない。`performDropAction()` → `performUninstall()` は state 遷移や成功コールバックに依存せず、`Intent` を即座に `startActivity()` する同期処理のため、後続の `goToState(NORMAL)` と competing しない。

この不具合は今回のセッションで新しく作り込んだものではなく、`AddToHomescreenDropTarget` を最初に実装した時点（`docs/Morrowa_AppDrawer_実装計画.md` §13）から存在した潜在バグである。これまで実機で実際にボタンへ drop する操作まで到達したことがなく（透明化バグ等で drag 自体が完了しなかったため）、今回初めて顕在化した。

#### 対応

`AddToHomescreenDropTarget.completeDrop()` を `LauncherAccessibilityDelegate.addToWorkspace()` 経由から、**`ItemInstallQueue.queueItem(String packageName, UserHandle user)`** 経由に変更した。

- `ItemInstallQueue`（`model/ItemInstallQueue.java`）は Play ストアのアプリインストール完了通知や `SessionCommitReceiver` が「アプリをホームへ自動追加する」際に使う既存の仕組みで、`LauncherState` / state 遷移とは無関係に `MODEL_EXECUTOR` 上でスペース探索・DB書き込み・bind を行う（`ItemInstallQueue.java:219-235`, `AddWorkspaceItemsTask.java`）。
- `d.dragInfo.getIntent()` からパッケージ名を取り出し（`ComponentName` があればそちらを優先、なければ `Intent#getPackage()`）、`ItemInstallQueue.INSTANCE.get(getContext()).queueItem(packageName, info.user)` を呼ぶだけのシンプルな実装に変更。
- `goToState` に一切依存しないため、後続の `onDropAnimationComplete()` の `goToState(NORMAL)` と競合しない。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 15s`。新規/変更ファイル起因のエラーなし。

#### 実機再確認結果（2026-07-05）と追加不具合

`ItemInstallQueue` 版で再確認したところ、アイコンは追加されるようになったが、**4ページ目に追加されてしまい、Drawer の裏で開いていたページに追加されない**という新しい問題が判明した。加えてユーザーから追加要望: 裏のページが Habit / ToDo（Morrowa の専用固定画面 = overlay page）の場合は、そこには配置できないため 1ページ目（Home）に追加してほしい。

原因: `ItemInstallQueue` → `AddWorkspaceItemsTask` → `WorkspaceItemSpaceFinder.findSpaceForItem()` は `workspaceScreens` を**画面 index 0 から順に**走査して最初に空きのある画面を選ぶ（`WorkspaceItemSpaceFinder.java:105-113`）。「現在開いているページ」という概念は考慮されない。

一方、`LauncherAccessibilityDelegate.findSpaceOnWorkspace()`（`accessibility/LauncherAccessibilityDelegate.java:383-421`）は **「まず現在のページを優先して確認する」**（line 388-390: `workspace.getCurrentPage()` を最初にチェック）というロジックを既に持っていた。§9.8 で `addToWorkspace()` 自体を捨てたのは、その `goToState(NORMAL, ..., successCallback)` ラップが `onDropAnimationComplete()` と競合していたためであり、内部の「現在ページ優先」という配置ロジック自体は正しかった。

対応: `ItemInstallQueue` を使うのをやめ、`findSpaceOnWorkspace()` と同じ「現在ページ優先」の考え方を `AddToHomescreenDropTarget` 内に直接実装した（`LauncherAccessibilityDelegate.findSpaceOnWorkspace()` は `protected` で別パッケージから呼べないため、複製ではなく必要分だけ再実装）。

- `Workspace.getCurrentPage()` で現在ページ index を取得。
- `Workspace.getMorrowaPageForPageIndex(pageIndex)` / `MorrowaPage.isOverlayPage`（`app.morrowa.MorrowaPage`、既存の Morrowa 論理ページ基盤）で、現在ページが Habit / ToDo（overlay page）かどうかを判定。
  - overlay page なら `Workspace.getPageIndexForMorrowaPage(MorrowaPage.HOME)` で Home のページ index に差し替える。
  - それ以外（Home / Widget Blank）ならそのまま現在ページを対象にする。
- 対象ページの `CellLayout.findCellForSpan(...)` で空きセルを探し、`ModelWriter.addItemToDatabase(...)` → `Launcher.inflateAndBindItemWithAnimation(...)` で配置・bind する（`goToState` には一切依存しない、`LauncherAccessibilityDelegate.addToWorkspace()` の DB書き込み/bind 部分と同じ処理）。
- 対象ページに空きがない場合は MVP として何もしない（他ページへのフォールバック探索はしない、クラッシュはしない）。

変更ファイル: `src/com/android/launcher3/AddToHomescreenDropTarget.java`（`completeDrop()` 全面書き換え、`resolveTargetPageIndex()` 追加）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 2m 6s`。新規/変更ファイル起因のエラーなし。

#### 実機確認結果（2026-07-05）: 3パターンとも完了

| 裏で開いていたページ | 期待される追加先 | 結果 |
|---|---|---|
| Home | Home | **完了** |
| Widget Blank | Widget Blank | **完了** |
| Habit / ToDo | Home（overlay page には配置できないため） | **完了** |

`Add to home screen` は、裏で開いているページを正しく認識して配置できることを実機で確認した。§9.6〜§9.8 で追跡していた `Add to home screen` 関連の不具合（透明化・強制Home遷移・配置されない・配置先ページ違い）はこれで一通り解消。

残課題（既知、Phase D 以降で対応予定）: アプリ同士のドラッグによる並び替え・フォルダ作成・フォルダ出し入れは未実装（§9.2 / 追加報告の通り）。修正3（ボタン使用後は Home へ戻る）は現状維持のまま。5・6番（Home 由来 drag の回帰、Overview/フォルダ/ウィジェットの見た目回帰）は明示的な問題報告なし。

## 10. Phase D 詳細計画: アプリ同士のドラッグによる並び替え（2026-07-06）

### 10.1 スコープ

Phase D は「App Drawer 内でアプリアイコンをドラッグして別の位置に落とすと、並び順が変わり、再起動後も保持される」まで。フォルダ作成（Phase E）・フォルダ出し入れ（Phase F）はやらない。

前提となる現状（§9.8 時点）: App Drawer 由来 drag は `ALL_APPS` state のまま、上部バーは Drawer の上に見え、grid 上で離すと何も起きずに Drawer に留まる（drop は `Workspace.acceptDrop` に拒否され `exitDrag` の Home 遷移はスキップ済み）。

### 10.2 追加調査で確定した事実（2026-07-06）

**(a) 並び順の決定箇所とフックポイント**

- 表示順は `AlphabeticalAppsList.onAppsUpdated()`（`allapps/AlphabeticalAppsList.java:260`）の `appSteam.sorted(mAppNameComparator)` で決まる。**`mAppNameComparator` は private**（`:119`）なので、Lawnchair サブクラスから comparator を差し替える最小フックが現状存在しない。
- 案: 基底クラスに `protected Comparator<AppInfo> getAppSortComparator()`（既定は `mAppNameComparator` を返す）を1メソッド追加し、line 260/261 がこれを使うようにする。`LawnchairAlphabeticalAppsList` が override して「保存済み手動順 comparator」を返す。`onAppsUpdated()` 全体の複製 override（private space・中国語ロケール処理などを抱え込む）よりはるかに小さく、AOSP 差分も追跡しやすい。
- リスト生成は 3 インスタンス（`ActivityAllAppsContainerView.initContent()`、`:290-298`）: MAIN / WORK / SEARCH すべて `LawnchairAlphabeticalAppsList`。**手動順を適用するのは MAIN のみ**（型は同じなのでコンストラクタ引数などで区別が必要。MAIN は workManager=null + privateProfileManager 付き、という現状の引数差だけでは不明瞭なので、明示フラグを渡す）。
- Lawnchair のリスト組み立て（`LawnchairAlphabeticalAppsList.addAppsWithSections()`、`.kt:92-138`）:
  - `drawerList=true`（通常モード）: 先頭に App Drawer フォルダ群（`getSortedFolders()`、順序は `prefs.drawerListOrder`）→ 残りのアプリを `super.addAppsWithSections()`（渡された順 = `mApps` のソート順）。**comparator 差し替えは「残りのアプリ」の順序にそのまま効く**。
  - `drawerList=false`（自動カテゴリモード）: `categorizeAppsWithSystemAndGoogle` で自動生成フォルダ化。手動並び替えと概念的に両立しない。
- fast scroller のセクション（`AlphabeticalAppsList.addAppsWithSections()`、`:483-518`）は `info.sectionName` が**変わるたびに**新セクションを作る。手動順ではセクション名が非単調になり、レターが乱立する可能性がある（§10.5 リスク参照）。
- `updateAdapterItems()` は既に DiffUtil（`MyDiffCallback`、`:362-365` / `:582-611`）で差分を adapter へ配信するため、**並び替え結果は既存経路で move アニメーションになる**見込み。

**(b) DropTarget 実装要件と登録順**

- `DropTarget` interface（`DropTarget.java:120-165`）: `isDropEnabled` / `acceptDrop` / `onDrop` / `onDragEnter` / `onDragOver` / `onDragExit` / `prepareAccessibilityDrop` / `getHitRectRelativeToDragLayer`。
- 登録順が優先順位を決める: `DragController.findDropTarget()` は**登録の逆順**で hit-rect 判定。`Launcher.setupViews()` では `mWorkspace.setup()`（`:1342`、Workspace 登録）→ `mDropTargetBar.setup()`（`:1360`、バーのボタン登録）の順なので、現状の優先度は「バー > Workspace」。
- **`mAppsView` の DropTarget 登録は `:1342` と `:1360` の間**に入れる。優先度が「バー > appsView > Workspace」になり、(1) バーへの drop は従来通りボタンが取る、(2) grid 上の drop は appsView が取る（Workspace まで到達しない）、(3) 開いたフォルダは動的登録（`Folder.java:847`、常に最後 = 最優先）でフォルダ内 drag を邪魔しない。
- drop 位置→リスト位置の変換: `getActiveRecyclerView().findChildViewUnder(x, y)` + `getChildAdapterPosition()`（標準 RecyclerView API）。DragLayer 座標→RV ローカル座標の変換が必要（`DragLayer.mapCoordInSelfToDescendant` 相当の既存 utility を使う）。

**(c) 永続化基盤**

- `AppDatabase`（`lawnchair/src/app/lawnchair/data/AppDatabase.kt`、version 3、DB名 "preferences"）に既に `FolderItemEntity`（ComponentKey 文字列 + `rank`）の前例がある。
- 新エンティティ `DrawerAppOrderEntity(componentKey: String PRIMARY KEY, rank: Int)` + DAO を追加し、**version 4 + Migration(3,4)** を書く。文字列 preference（`drawerListOrder` 方式）はアプリ数百件分の ComponentKey を1文字列に詰めることになるため採らない。

### 10.3 仕様判断（推奨込み・実装前に確認）

| # | 論点 | 推奨 |
|---|---|---|
| 1 | 並び順モデル | **初回の手動並び替え時に、その時点の表示順（アルファベット順）を rank としてシード保存**し、以後は手動順。並び順テーブルが空の間は従来通り純アルファベット順（挙動不変） |
| 2 | 新規インストールアプリ | 手動順の**末尾に追加**（rank 未登録のアプリは comparator で末尾へ、末尾内はアルファベット順） |
| 3 | 適用範囲 | **メイン（個人）タブのみ**。Work / Private space タブと検索結果はアルファベット順のまま（並び替え drop も受け付けない） |
| 4 | 自動カテゴリモード（`drawerList=false`） | 並び替え**無効**（`acceptDrop=false`）。手動カテゴリと自動カテゴリは両立しない |
| 5 | フォルダ item | Phase D では**動かせない・drop 先にもならない**。アプリの挿入位置はフォルダ領域（リスト先頭）より後ろに clamp |
| 6 | 並び順のリセット手段 | MVP では提供しない（将来、設定画面に「並び順をリセット」を検討） |

### 10.4 実装ステップ

**D1: 永続化 + ソート差し替え基盤（UI 変更なし）**

- 対象: `AppDatabase.kt`（version 4 + migration）、新規 `DrawerAppOrderEntity` / DAO / repository（`lawnchair/src/app/lawnchair/data/appdrawer/` など folder パッケージに倣う）、`AlphabeticalAppsList.java`（`getAppSortComparator()` フック追加、2行変更 + 1メソッド）、`LawnchairAlphabeticalAppsList.kt`（MAIN のみ保存順 comparator を返す + 保存順の observe → `onAppsUpdated()`）。
- やらないこと: DropTarget 実装、drag との接続。
- 受け入れ条件: 並び順テーブルが空なら挙動が完全に従来通り。テーブルに手動で rank を入れると（adb / テストコードで investigation）Drawer の並びが変わり、再起動後も保持される。WORK / SEARCH リストは常にアルファベット順。
- 検証: compile + 実機で従来挙動の不変を確認、rank 手動投入で並び替わることを確認。

**D2: `ActivityAllAppsContainerView` の DropTarget 実装（drop で並び替え確定）**

- 対象: `ActivityAllAppsContainerView.java`（`DropTarget` 実装、Lawnchair 拡張ポイントに置けるなら `LauncherAllAppsContainerView` 側でも可）、`Launcher.setupViews()`（`:1342` と `:1360` の間で `mDragController.addDropTarget(mAppsView)`）。
- 実装内容:
  - `isDropEnabled()` / `acceptDrop()`: App Drawer 由来 drag（`dragObject.dragSource instanceof ActivityAllAppsContainerView` — 既存イディオム）かつ MAIN タブ表示中・非検索・`drawerList=true` のときだけ true。
  - `onDrop()`: drop 座標 → `findChildViewUnder` → adapter position → 挿入先 index（アイコン item のみ対象、フォルダ領域より前は clamp）。並び順が未シードなら現在の表示順をシード → 対象アプリを挿入位置へ move → DAO へ書き込み → `onAppsUpdated()`（DiffUtil が move アニメーションを配信）。
  - アイコン以外（余白・divider 等）への drop、対象外条件での drop は「何もしない」（現状の挙動 = DragView 即時消滅と同じ）。
- やらないこと: drag 中のリアルタイム入れ替えプレビュー、edge auto-scroll、フォルダへの drop。
- 受け入れ条件: アプリ A をアプリ B の位置に drop すると A がその位置へ挿入され、他アプリが繰り下がる。再起動後も保持。バーへの drop（Uninstall / Add to home screen）は従来通り。Home 由来 drag に影響なし。検索・fast scroll・フォルダ表示・Work タブが壊れていない。
- 検証: compile + 実機。

**D3: drag 中の UX 磨き（それぞれ独立に導入可・必要性を実機で判断してから）**

- (i) リアルタイム並び替えプレビュー: `onDragOver()` で hit-test し、Workspace の `ReorderAlarmListener`（250ms）に倣った throttle で暫定順をメモリ上に反映 → `updateAdapterItems()`（DiffUtil アニメ）。DB 書き込みは drop 時のみ。
- (ii) edge auto-scroll: drag 座標が RV 上下端ゾーンに入ったら `scrollBy` で自動スクロール（長いリストの端まで運べないため、実用上ほぼ必須の見込み）。
- (iii) 無効 drop 時のフライバック: §9.7 留意点1（DragView 即消え）の改善。`animateDragViewToOriginalPosition` 相当を drawer 内座標で。
- 受け入れ条件: 各項目単体で導入し、D2 の受け入れ条件が維持されていること。

**D4: 回帰確認（検証のみ）**

- 検索、fast scroll（特に手動順時のレター挙動）、Work / Private profile、App Drawer フォルダ表示（`drawerList` 両モード）、アプリのインストール/アンインストール時のリスト更新、Home 由来 drag、`Uninstall` / `Add to home screen`、Home 画面既存機能。

### 10.5 リスク

- **fast scroller の非単調セクション**: 手動順ではレターが乱立・非単調になる。実機確認のうえ破綻するようなら「手動順が有効な間はセクションを1つに畳む（レター無効化）」をフォールバックとして D2 に含める。
- **`AlphabeticalAppsList` への AOSP 変更**: `getAppSortComparator()` フックは小さいが upstream merge の conflict 点になる。1メソッド + 参照2行に留める。
- **live preview の負荷**: `onAppsUpdated()` はフィルタ・ソート・リスト再構築を全部やる。D3(i) では DB を読まずメモリ上の暫定 rank だけで回し、throttle を必ず入れる。
- **MAIN 判定の明示化**: 3つの `LawnchairAlphabeticalAppsList` インスタンスの区別をコンストラクタ引数の暗黙差に頼らず明示フラグにする（将来の混線防止）。
- **バックアップ**: `AppDatabase`（"preferences" DB）が Lawnchair のバックアップ/リストアに含まれるか未確認。含まれないなら並び順はバックアップ対象外となる（MVP 許容だが要確認・要記録）。
- **RTL**: `findChildViewUnder` は座標ベースなので RTL でもそのまま動く見込みだが、挿入 index の前後判定（drop 位置がセルの左半分/右半分どちらか等を使う場合）は RTL 反転に注意。

### 10.6 Codex への最初のタスク（D1）

```text
目的:
App Drawer の手動並び順を永続化する基盤と、表示ソートを差し替える最小フックを作る。UI/drag は変更しない。

対象:
lawnchair/src/app/lawnchair/data/（新規 entity/DAO/repository、AppDatabase version 4 + migration）、
src/com/android/launcher3/allapps/AlphabeticalAppsList.java（protected getAppSortComparator() フック）、
lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt（MAIN のみ保存順 comparator）。

読むべきファイル:
AppDatabase.kt、folder/FolderEntity.kt、folder/service/FolderDao.kt（前例）、
AlphabeticalAppsList.java:239-285（onAppsUpdated）、LawnchairAlphabeticalAppsList.kt。

実装内容:
DrawerAppOrderEntity(componentKey PRIMARY KEY, rank) + DAO + repository。
AlphabeticalAppsList.onAppsUpdated() の sorted(mAppNameComparator) を sorted(getAppSortComparator()) に変更し、
既定実装は mAppNameComparator を返す。
LawnchairAlphabeticalAppsList は MAIN インスタンスのときだけ、保存順があれば
「rank 順、未登録アプリは末尾（末尾内はアルファベット順）」の comparator を返す。
保存順の変更を observe して onAppsUpdated() を呼ぶ。

やらないこと:
DropTarget 実装、drag との接続、設定画面、WORK/SEARCH リストへの適用。

受け入れ条件:
並び順テーブルが空なら全挙動が従来と同一。rank を手動投入すると MAIN の並びだけが変わり再起動後も保持される。

検証方法:
compileLawnWithQuickstepGithubDebugJavaWithJavac + 実機で従来挙動の不変確認。
```

### 10.7 D1 実装記録（2026-07-06）

変更 / 追加ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/data/appdrawer/DrawerAppOrderEntity.kt`（新規） | `@Entity(tableName = "DrawerAppOrder") data class DrawerAppOrderEntity(@PrimaryKey val componentKey: String, val rank: Int)` |
| `lawnchair/src/app/lawnchair/data/appdrawer/service/DrawerAppOrderDao.kt`（新規） | `getAll(): Flow<List<...>>`、`getAllOnce()`、`insertAll()`、`clear()` |
| `lawnchair/src/app/lawnchair/data/AppDatabase.kt` | version 3→4、`DrawerAppOrderEntity` 追加、`drawerAppOrderDao()` 追加、`MIGRATION_3_4`（`FolderItems`/`Wallpapers` と同じ生 SQL 方式でテーブル作成） |
| `src/com/android/launcher3/allapps/AlphabeticalAppsList.java` | `protected Comparator<AppInfo> getAppSortComparator()`（既定は `mAppNameComparator`）を追加。`onAppsUpdated()` の `appSteam.sorted(...)`（`mApps` 用、旧 line 260）だけをこのフック経由に変更。`privateAppStream.sorted(...)`（private space 用、旧 line 261）は `mAppNameComparator` 直呼びのまま**変更していない**（§10.3 決定3: Private space はアルファベット順のまま） |
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | コンストラクタに `isMainList: Boolean` を追加（デフォルトなし、3呼び出し元すべてで明示指定を強制）。`observeDrawerAppOrder()` で `DrawerAppOrderDao.getAll()` を observe し `drawerAppOrder: Map<String, Int>` を保持。`getAppSortComparator()` を override し、`isMainList && drawerAppOrder が非空 && prefs.drawerList.get()`（手動フォルダモード）のときだけ「rank 順、未登録は末尾＋末尾内アルファベット順」の comparator を返す |
| `src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java` | MAIN/WORK/SEARCH の3箇所のコンストラクタ呼び出しに `isMainList` を明示指定（MAIN のみ `true`） |

実装方針の補足:

- `getAppSortComparator()` フックは §10.2(a) の調査通り、`onAppsUpdated()` 全体を複製 override せず、2行だけ変更する最小差分にした。
- `isMainList` はコンストラクタ必須引数にした（デフォルト値を付けず、Java 側3呼び出し元すべてで明示させることで、将来の呼び出し追加時に指定漏れがコンパイルエラーになるようにした。§10.5 で懸念していた「暗黙差に頼る」リスクを排除）。
- `kotlinx.coroutines.flow.onEach` は `com.patrykmichalik.opto.core.onEach`（同ファイルで既存 preference 監視に使用中）と名前が衝突するため、`import kotlinx.coroutines.flow.onEach as onEachFlow` でエイリアスして明示的に区別した。
- §10.3 決定4（自動カテゴリモードでは並び替え無効）を D1 の時点から `getAppSortComparator()` 内で `prefs.drawerList.get()` チェックとして先取りで組み込んだ（D2 の DropTarget 側でも別途ガードする想定だが、二重の安全策として）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 4m 12s`。新規/変更ファイル起因のエラーなし（Room の KSP annotation processing、Kotlin コンパイルとも成功）。

未実施:

- 実機確認（`DrawerAppOrder` テーブルが空の状態でこれまで通りの挙動になっているか、`drawerAppOrderDao().insertAll(...)` 等で手動投入した場合に MAIN リストだけ並びが変わり、WORK / SEARCH / Private space はアルファベット順のままか、再起動後も保持されるか）。
- DB migration（v3→v4）が既存インストールに対して正しく走るか（クリーンインストールではなく、既存データがある状態でのアップグレード確認）。

### 10.8 D2 実装記録（2026-07-06）

変更 / 追加ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/data/appdrawer/service/DrawerAppOrderDao.kt` | `@Transaction suspend fun replaceAll(items)`（`clear()` → `insertAll()` を1トランザクションにまとめた、`FolderDao.insertFolderWithItems` と同じパターン）を追加 |
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | `getAppAtAdapterPosition(position): AppInfo?`（`mAdapterItems[position]` がアイコンなら `itemInfo` を返す）と `reorderApp(moved: AppInfo, insertBeforeComponentKey: String)`（現在の表示順から `moved` を除去し `insertBeforeComponentKey` の直前へ挿入、残り全件の rank を 0..N-1 に振り直して `replaceAll()` へ書き込み、`onAppsUpdated()` で反映）を追加 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | `DropTarget` を実装。`acceptDrop()` は「App Drawer 由来 drag（`dragSource is ActivityAllAppsContainerView<*>`）かつ dragInfo が `AppInfo` かつ非検索かつ MAIN タブかつ `drawerList=true`」のときのみ true。`onDrop()` は `dragObject.x/y`（`SearchContainerView` 基準、`DragController.findDropTarget()` の座標変換により保証済み）を `Utilities.mapCoordInSelfToDescendant()` で RecyclerView ローカル座標に変換し、`findChildViewUnder()` + `getChildAdapterPosition()` で対象アプリを特定して `reorderApp()` を呼ぶ |
| `src/com/android/launcher3/Launcher.java` | `setupViews()` で `mWorkspace.setup()` と `mDropTargetBar.setup()` の間に、`mAppsView instanceof DropTarget` の場合のみ `mDragController.addDropTarget(appsDropTarget)` を追加 |

実装方針の補足:

- `DropTarget` の実装場所は、当初案の `ActivityAllAppsContainerView.java`（AOSP 由来）ではなく、`R.id.apps_view` の実際の具象クラスである `SearchContainerView.kt`（`lawnchair/` 配下、完全に Lawnchair 独自）にした。`Launcher.java` 側の `mAppsView` の静的型は `ActivityAllAppsContainerView<Launcher>`（`DropTarget` 非実装）のままなので、`instanceof DropTarget` パターンマッチで実行時に安全にダウンキャストしている。これにより AOSP 由来コアクラス（`ActivityAllAppsContainerView` / `LauncherAllAppsContainerView`）は無改造。
- 優先度調査（§10.2(b)）の再検証: 実際には `Workspace` は `mDropTargets` リストに明示登録されておらず、`LauncherDragController.getDefaultDropTarget()` の特殊フォールバック（リスト内のどの `DropTarget` にも該当しない場合の既定値）として機能している。そのため「バー > appsView > Workspace」という意図した優先度は、`mAppsView` をバーボタンより前に登録する（`findDropTarget()` が逆順走査するため、後から登録されたバーボタンが先にヒットする）ことと、Workspace が常に最終フォールバックであることの組み合わせで実現される。当初の計画メモの記述（Workspace も明示登録される前提）は不正確だったが、結論の優先度自体は変わらない。
- 並び替えのデータソースは `mApps`（`private`、既存コードに手を入れず読めない）ではなく、`mAdapterItems`（`protected final`、`LawnchairAlphabeticalAppsList` 自身が既に読み書きしている）から `viewType == VIEW_TYPE_ICON` の項目だけを抽出する方式にした。フォルダ項目は自然に除外され、追加の clamp ロジックが不要になった。
- rank は「対象位置へ挿入 → 全件を 0..N-1 に振り直して丸ごと `replaceAll()`」方式にした（部分更新や分数 rank ではなく）。Room 上のデータは常に密な連番になり、不整合が起きにくい。

やらないこと（D2 スコープ外、計画通り）: drag 中のリアルタイム入れ替えプレビュー、edge auto-scroll、無効 drop 時のフライバック（すべて D3）。フォルダへの drop（D2 では `getAppAtAdapterPosition` がフォルダ項目に対して null を返すため自然に no-op）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 50s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（D1 の未実施項目に加えて）:

- アプリ A をアプリ B の位置に drop すると A がその位置へ挿入され、他アプリが繰り下がる。
- 並び替え結果が再起動後も保持される。
- バーへの drop（Uninstall / Add to home screen）が従来通り動作する（優先度: バー > appsView が壊れていないか）。
- Home 由来 drag に影響がない。
- 検索・fast scroll・Work タブ・フォルダ表示が壊れていない。
- フォルダ項目へ drop しても何も起きない。

### 10.9 インストール確認（2026-07-06）

`installLawnWithQuickstepGithubDebug` で実機（`SC-52C - 16`、既存の "preferences" DB を持つ既存インストール、クリーンインストールではない）へインストール。`adb shell am start` で起動、数秒後もプロセス生存を確認。`adb logcat` を `FATAL EXCEPTION` / `AndroidRuntime` / `SQLiteException` / Room migration 関連で確認したが該当ログなし。**v3→v4 の DB migration（`DrawerAppOrder` テーブル追加）は既存データを持つ実機に対して問題なく適用された。**

D1/D2 の機能的な動作確認（実際にドラッグして並び替え、再起動後の永続化、検索/fast scroll/Work タブ/フォルダ/バー/Home 由来 drag の回帰確認）はまだ実施していない。

### 10.10 実機確認結果（2026-07-06）: 並び替え不成立 + ゴースト DragView・原因確定

実機報告: ドラッグ中はアイコンが指に追従するが、grid 上で指を離しても**並び替えは起きず**、離した場所に**当たり判定のないアイコン画像だけが残る**（Home に戻ってもそのまま見える）。

#### 原因1（確定）: DragView の後始末契約違反 → ゴースト

- `DropTarget.DragObject.deferDragViewCleanupPostAnimation` は**デフォルト true**（`DropTarget.java:73`）。
- `DragController` の契約（`dispatchDropComplete()`、`dragndrop/DragController.java:292-297` のコメント）: **`acceptDrop()` が true を返した場合、DragView の後始末は drop target の責任**。drop target は次のどちらかを必ず行う必要がある。
  - `DragLayer.animateViewIntoPosition(d.dragView, targetView, null)` で着地アニメーション（終了時に DragView が除去される）— `Folder.onDrop()` の `Folder.java:1620` が前例。
  - `d.deferDragViewCleanupPostAnimation = false` を設定（`endDrag()` の `DragController.java:319-327` が即時 `dragView.remove()` する）— `Folder.java:1585` / `:1624` が前例。
- 今回の `SearchContainerView.onDrop()` は**どちらも行っていない**。そのため acceptDrop=true になった drop では、成功・早期 return を問わず **DragView が DragLayer 上に永久に残る**。DragView はタッチを受けないただの浮遊ビューなので「当たり判定のないアイコン画像」になり、DragLayer は state を跨いで存在するので Home に戻っても見え続ける。症状と完全に一致。

#### 原因2（ほぼ確定）: `findChildViewUnder` の early return → 並び替え不成立

- `onDrop()` は `recyclerView.findChildViewUnder(x, y)` が null を返すと**無言で return** する（この経路でも原因1によりゴーストが残る）。
- `findChildViewUnder` は「その座標が子 View の矩形の内側にあるとき」しかヒットしない。App Drawer の grid はアイコン View の間に余白があり、また使っている座標が `d.x/d.y`（**指のタッチ点**。`DragController.findDropTarget():590-596` で drop view ローカル座標へ変換済みだが、視覚中心ではない）なので、余白・行間に落ちると null になる。
- 参考実装の `Folder` はこの問題を**厳密ヒットテストを使わない**ことで回避している: `getTargetRank()`（`Folder.java:1172-1176`）は `d.getVisualCenter()`（DragView の視覚中心）を使い、`findNearestArea(x, y)`（**最近傍セル**の計算）で必ずどこかの rank に解決する。
- 加えて設計上の問題: 位置解決を `onDrop()` でやっているため「accept したのに何もできない」経路が存在する。`Workspace` / `Folder` は **`acceptDrop()` の時点で受け入れ可否を確定**し（`Workspace.acceptDrop()` は `mDropToLayout` 等へ結果をキャッシュ）、`onDrop()` は失敗しない前提で書かれている。

### 10.11 詳細設計: `Folder` の並び替えアルゴリズムを RecyclerView へ移植する

コードベース内で最も近い既存実装は `Folder.java`（`DropTarget` を実装し、rank ベースでアイコンを並び替える）。その構造を RecyclerView 用に対応させる。

#### 10.11.1 参考: Folder のアルゴリズム構造

| 要素 | Folder の実装 |
|---|---|
| ターゲット位置の計算 | `getTargetRank(d)`（`:1172`）: `d.getVisualCenter()` → `mContent.findNearestArea(x, y)`（最近傍セル。null がない） |
| ドラッグ中の空き位置 | `mEmptyCellRank`（ドラッグ元 = 現在の空きセル rank） |
| リアルタイム入れ替え | `onDragOver()`（`:1179`）: target が前回から変わったら `mReorderAlarm`（250ms、`REORDER_DELAY` `:196`）をセット → 発火で `realTimeReorder(mEmptyCellRank, mTargetRank)`（アニメ付きシフト）+ `mEmptyCellRank = mTargetRank`（`:1161-1166`） |
| 端スクロール | visual center がセル幅×係数の端ゾーンに入ったら `showScrollHint`、スクロール中（`mScrollPauseAlarm` `:1180-1182`）は reorder を止め target を巻き戻す（`:1234-1235`） |
| drop 確定 | `onDrop()`: 最終 rank に配置 + DB batch 更新 + **`animateViewIntoPosition(d.dragView, currentDragView, null)`**（`:1620`）または **`deferDragViewCleanupPostAnimation = false`**（`:1585`/`:1624`） |
| 範囲外へ出た | `onDragExit` → `mOnExitAlarm` → `completeDragExit()`（並びを元に戻す/確定） |

#### 10.11.2 新規クラス `AllAppsReorderController`

`SearchContainerView` に直接ロジックを書かず、`app.lawnchair.allapps.reorder.AllAppsReorderController`（新規、Lawnchair 側）に集約する。`SearchContainerView` は `DropTarget` の6メソッドを controller へ委譲するだけの薄い層にする（`Folder` における `Folder`/`FolderPagedView` の分離に相当）。

保持する状態:

```text
pendingOrder: MutableList<AppInfo>?   // drag 中の暫定表示順。onDragEnter でシード、onDrop で確定、onDragExit で破棄
prevTargetIndex: Int                  // 前回計算した挿入先（Folder の mPrevTargetRank）
reorderAlarm: Alarm                   // 250ms（Folder の REORDER_DELAY と同値）
resolvedDropIndex: Int                // acceptDrop() でキャッシュする解決済み挿入先
```

#### 10.11.3 ターゲット位置計算 `computeTargetIndex(d): Int`（Folder の `getTargetRank` 相当）

1. `d.getVisualCenter(recycle)` で **DragView の視覚中心**を取得（`d.x/d.y` の生タッチ座標は使わない。Folder `:1173` と同じ）。DragLayer 座標なので `Utilities.mapCoordInSelfToDescendant(recyclerView, dragLayer, coord)` で RV ローカルへ変換する（`d.x/d.y` は drop view ローカル変換済みだが、visual center は自前で変換が必要な点に注意）。
2. `recyclerView.findChildViewUnder(x, y)` を試す。null の場合は**最近傍走査へフォールバック**: RV の可視子 View（`getChildCount()`/`getChildAt(i)`）のうち adapter item が `VIEW_TYPE_ICON` のものだけを対象に、View 中心と visual center の距離が最小の子を選ぶ（`findNearestArea` の RecyclerView 版。可視子が1つもなければ解決失敗）。
3. 子 View → `getChildAdapterPosition()`（`NO_POSITION` なら `getChildViewHolder().getBindingAdapterPosition()` を試し、それでもダメなら解決失敗）。
4. adapter position → **挿入 index への変換**: ターゲットセルの中心より visual center が「行方向で手前」なら before、「後ろ」なら after（x 比較、RTL では反転。y がセル範囲外の場合は行差で判定）。フォルダ item・divider・検索行など `VIEW_TYPE_ICON` 以外は対象外とし、挿入 index はアイコン領域（Lawnchair のリスト構成では App Drawer フォルダ群より後ろ）に clamp する（§10.3 判断5）。

#### 10.11.4 DropTarget コールバックの責務

- **`acceptDrop(d)`**: 既存のゲート（App Drawer 由来 / `AppInfo` / 非検索 / 個人タブ / `drawerList=true`）に加えて **`computeTargetIndex` まで実行**し、解決できたら `resolvedDropIndex` にキャッシュして true。解決できなければ **false**（→ `DragController` 側の不受理経路が DragView を掃除し、`exitDrag` は §9.7 修正2 により Drawer に留まる。「accept したのに何もしない」経路を構造的に無くす。`Workspace.acceptDrop` が `mDropToLayout` にキャッシュするのと同じパターン）。
- **`onDrop(d, options)`**: 失敗しない前提で書く。
  1. `pendingOrder`（live preview 有効時）または現在表示順をシードに、ドラッグ中アプリを `resolvedDropIndex` へ move。
  2. `DrawerAppOrderDao.replaceAll(...)` へ永続化（既存 D1 の `reorderApp` を「挿入 index 指定」版に改める。`insertBeforeComponentKey` 方式は最近傍フォールバックや末尾 drop で表現しきれないため index 方式へ変更）。
  3. `onAppsUpdated()` → DiffUtil（`AlphabeticalAppsList.java:362-365`）が move を配信。
  4. **DragView の後始末（必須）**: 並び替え後のターゲット位置の子 View が特定できるなら `dragLayer.animateViewIntoPosition(d.dragView, targetChildView, null)`（Folder `:1620`）。DiffUtil のアニメーションと競合して特定できない場合は `d.deferDragViewCleanupPostAnimation = false`（Folder `:1624`）。**どの経路でも必ずどちらかを実行する。**
- **`onDragEnter(d)`**: `prevTargetIndex = -1`。live preview 有効時は `pendingOrder` を現在表示順からシード。
- **`onDragOver(d)`**（live preview = D3(i) を入れる場合のみ実質処理）: `computeTargetIndex` → 前回と変わったら `reorderAlarm` を張り直し（Folder `:1186-1190`）、発火で `pendingOrder` を move → `mainList` に暫定順を渡して `updateAdapterItems()` → DiffUtil の move アニメーションが「アイコンが避ける」フィードバックになる。DB は書かない。
- **`onDragExit(d)`**: `reorderAlarm.cancelAlarm()`。live preview 中なら **`pendingOrder` を破棄して元の順へ戻す**（`onAppsUpdated()` を呼ぶだけ。DB 未書き込みなので戻る）。これを怠ると、バーへ運んで `Uninstall` した場合に途中経過の並び替えが画面に残る。
- **`isDropEnabled()`**: true（ゲートは acceptDrop 側）。`getHitRectRelativeToDragLayer` は現状どおり全体矩形（バーは後登録優先で先に取られる）。

#### 10.11.5 端 auto-scroll（D3(ii)）

Folder の端ゾーン+alarm パターン（`:1201-1236`）を RV 縦スクロールに読み替える: visual center が RV 上端/下端ゾーン（例: 行高1つ分）に入ったら scroll alarm を開始し、発火ごとに `scrollBy(0, ±step)` して再セット（ゾーン内にいる間継続）。スクロール中は reorder alarm をキャンセルし `prevTargetIndex` を無効化する（Folder の `mScrollPauseAlarm` / target 巻き戻し `:1234-1235` に相当）。ゾーンを抜けたら alarm 停止。

#### 10.11.6 実装順（D2 修正 → D3）

1. **D2-fix（最小・バグ修正）**: 位置解決を `acceptDrop` へ移動 + visual center 化 + 最近傍フォールバック + 挿入 index 方式 + **全経路の DragView 後始末**。live preview なし。受け入れ条件: grid 上のどこで離しても（アイコン間の余白含む）ゴーストが残らず、アイコン上/近傍なら並び替えが確定し再起動後も保持される。バー drop・Home 由来 drag は従来どおり。
2. **D3(i) live preview**: reorder alarm + pendingOrder + onDragExit revert。
3. **D3(ii) edge auto-scroll**、**D3(iii)** drop 時の `animateViewIntoPosition` 着地アニメーション磨き。

各ステップごとに compile + 実機確認（ゴースト残留の再確認を必須項目にする）。

### 10.12 D2-fix 実装記録（2026-07-06）

§10.10/§10.11 で確定した2つの原因（DragView 後始末契約違反によるゴースト、`findChildViewUnder` の early return による並び替え不成立）を修正した。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | 位置解決を `onDrop()` から `acceptDrop()` へ移動し、成功時のみ `resolvedInsertIndex` にキャッシュして true を返す（`Workspace.acceptDrop` の `mDropToLayout` キャッシュと同じパターン）。`resolveTargetApp()` で `dragObject.getVisualCenter()`（DragView 視覚中心）を使用し、`findChildViewUnder` が null の場合は可視子 View を距離で走査する最近傍フォールバックを追加。`onDrop()` の先頭で必ず `dragObject.deferDragViewCleanupPostAnimation = false` を設定（アニメーション着地は D3(iii) で検討、まずはゴーストを確実に防ぐことを優先） |
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | `reorderApp(moved, insertBeforeComponentKey: String)` を `reorderApp(moved, insertIndex: Int)` へ変更（挿入位置を component key ではなく index で受け取る。最近傍フォールバックや将来の「末尾へ drop」等を index で統一的に表現できる）。共通の `getOrderedApps()`（フォルダ除外済みの現在表示順）を新設し、`reorderApp` と `SearchContainerView` の対象特定の両方から利用 |

設計上の補足（ユーザー指摘を実装に反映した点）:

- **座標系の訂正**: `dragObject.getVisualCenter()` は `d.x`/`d.y` から計算されるため、`d.x`/`d.y` と同じ座標系（`getDropView()` = `SearchContainerView` ローカル、`DragController.findDropTarget()` で変換済み）になる。`Folder.getTargetRank()` も DragLayer への追加変換なしに `getVisualCenter()` の結果をそのまま使っている（自身の padding を引くだけ）ことから確認した。当初の設計メモ（§10.11.3）にあった「DragLayer 座標としての追加変換」は不要と判断し、`Utilities.mapCoordInSelfToDescendant(recyclerView, this, coord)`（root = `this`）で直接 RecyclerView ローカルへ変換する実装にした。
- **DragView 後始末**: `DragController.dispatchDropComplete()`（`dragndrop/DragController.java:291-297`）のコメント通り、`acceptDrop()` が false を返した場合は `DragController` 自身が `deferDragViewCleanupPostAnimation = false` を設定してくれる（既存動作）。したがって今回の修正対象は「`acceptDrop()` が true を返した場合の後始末」のみで、`onDrop()` の先頭で無条件に `deferDragViewCleanupPostAnimation = false` を設定することで対応した。`Folder` が行っている `animateViewIntoPosition` による着地アニメーションは D3(iii) に先送り。
- `acceptDrop()` は `DragController.drop()`（`dragndrop/DragController.java:526-568`）から `onDrop()` の直前に一度だけ同期的に呼ばれることを確認済みなので、インスタンスフィールドでのキャッシュは安全。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 24s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（ゴーストが残らないか、アイコン間の余白に落としても最近傍で解決されるか、再起動後の永続化、検索/fast scroll/Work タブ/フォルダ/バー/Home 由来 drag の回帰確認）。

### 10.13 実機確認結果（2026-07-06）: ゴースト解消を確認

`installLawnWithQuickstepGithubDebug` で実機（`SC-52C - 16`）へインストール、起動・プロセス生存・crash ログなしを確認。

ユーザー確認: **§10.10 で報告されたゴースト（当たり判定のないアイコン画像が残る）は解消**。`onDrop()` 冒頭での無条件 `deferDragViewCleanupPostAnimation = false` が機能している。

### 10.14 実機確認結果（2026-07-06）: 残り3項目も完了

| 項目 | 結果 |
|---|---|
| 最近傍フォールバック（アイコン間の余白にドロップ） | **完了** |
| 再起動後の永続化 | **完了** |
| 回帰確認（検索 / fast scroll / Work タブ / フォルダ / バー / Home 由来 drag） | **完了** |

D2（アプリ同士のドラッグによる並び替え）は、当初の受け入れ条件（§10.1「App Drawer 内でアプリアイコンをドラッグして別の位置に落とすと、並び順が変わり、再起動後も保持される」）を実機で満たしたことを確認した。ゴースト・並び替え不成立というブロッカーも解消済み。

残りは D3（リアルタイム入れ替えプレビュー、edge auto-scroll、着地アニメーション。§10.4/§10.11.5 参照、いずれも UX の磨きでD2の受け入れ条件には含まれない）と、フォルダ作成（Phase E）・フォルダ出し入れ（Phase F）。

### 10.15 D3(i) 実装記録（2026-07-06）: リアルタイム並び替えプレビュー

§10.4「D3(i) live preview」を実装した。ドラッグ中、指を止めて少し待つと他のアイコンが避けて暫定順が視覚的に反映されるようにする（DB 書き込みは drop 確定時のみ）。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | `pendingOrder: MutableList<AppInfo>?`（drag 中のみ非 null）を追加。`getAppSortComparator()` を、`pendingOrder` があればそれ由来の rank を最優先で使うよう変更（`drawerAppOrder`/アルファベット順はそのフォールバック）。`beginPendingReorder()`（現在表示順からシード、既に開始済みなら no-op）、`previewReorder(moved, insertIndex)`（`pendingOrder` を更新して `onAppsUpdated()`）、`cancelPendingReorder()`（`pendingOrder` を破棄して `onAppsUpdated()`）を追加。`reorderApp()` は `pendingOrder ?: getOrderedApps()` を基準にすることで、確定的の move 計算とプレビュー計算を共通の `movedTo()` ヘルパーに統合 |
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | `onDragEnter()`：`prevTargetIndex = -1` とプレビュー用 alarm キャンセル（`Folder.onDragEnter` の `mPrevTargetRank = -1` に対応）。`onDragOver()`：対象判定（`eligibleMainList()` に共通化）→ `beginPendingReorder()` → target 解決 → 前回と異なる index なら 250ms（`Folder.REORDER_DELAY` と同値）の `Alarm` を張り直し、発火時に `previewReorder()`。`onDragExit()`：`dragObject.dragComplete` が **false のときだけ** `cancelPendingReorder()` を呼ぶ |

設計上の最重要ポイント（ユーザー主導のこのセッションで詰めた点）:

- **`onDragExit` での revert と `onDrop` での commit の競合回避**: `DragController.drop()`（`dragndrop/DragController.java:526-568`）は、同一 target へ drop する場合でも `mDragObject.dragComplete = true`（line 538）を設定した**後**に、無条件で `dropTarget.onDragExit(mDragObject)`（line 553）を呼んでから `acceptDrop`/`onDrop` に進む。つまり「本当に別の場所へ移動した exit」と「これから drop するための exit」を区別する必要があり、`Folder.onDragExit()`（`folder/Folder.java:1267-1270`、`if (!d.dragComplete)` で `mOnExitAlarm` を張るかどうか分岐）が同じ手法を使っていることを確認した上で、本実装でも `!dragObject.dragComplete` の場合だけ `cancelPendingReorder()` を呼ぶようにした。これにより、遅延 revert のための alarm を別途持つ必要がなくなり実装がシンプルになった。
- `onDragOver` 内で `eligibleMainList()`（App Drawer 由来 / `AppInfo` / 非検索 / 個人タブ / `drawerList=true` の判定）に失敗した場合は `cancelPendingReorder()` を呼んで安全側に倒す（タブ切り替えなど想定外の状態遷移でプレビューが残留しないようにする）。
- `acceptDrop()`/`onDrop()` 側の対象判定ロジック（旧`resolveTargetApp`呼び出し部）と `onDragOver()` の対象判定を `eligibleMainList()` に共通化し、判定基準が2箇所に分岐しないようにした。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: 1回目のビルドで `Alarm.setAlarm(long)` に `Int` を渡すコンパイルエラー（`REORDER_PREVIEW_DELAY_MS` を `Int` で宣言していたため）が発生。`250L`（`Long`）に修正し再ビルドして `BUILD SUCCESSFUL in 3m 21s`。

未実施: 実機確認（ドラッグ中に他アイコンが避けて暫定順が見えるか、バーへ寄り道して戻ってきても古いプレビューが残らないか、最終的な drop で正しく確定・永続化されるか、ゴーストが出ないか、検索/fast scroll/Work タブ/フォルダ/Home 由来 drag の回帰）。

### 10.16 実機確認結果（2026-07-06）: D3(i) 完了・追加要望2点

D3(i)（リアルタイム並び替えプレビュー）は実機確認で問題なく動作した（プレビュー表示、バーへの寄り道からの復帰、最終確定・永続化、ゴーストなし、既存機能の回帰なし、すべて確認済み）。

その上でユーザーから、Home 画面の drag/drop 体験と比較して App Drawer 側に**まだ欠けている2点**の指摘があった。

1. **ドラッグ中/drop 時のアニメーションがない**: 現状 `SearchContainerView.onDrop()` は `dragObject.deferDragViewCleanupPostAnimation = false` を設定するだけで、DragView を即座に消している（§10.12 のゴースト対策として導入）。Home 画面では drop 時に `DragLayer.animateViewIntoPosition(...)` でアイコンが着地位置へ滑らかに移動してから消える。
2. **重ねてもフォルダにならない**: 現状はアイコン同士の重なり具合に関わらず常に「並び替え（挿入）」としてのみ処理される。Home 画面では、対象アイコンの中心に十分近づくとフォルダ作成のプレビュー（背景ハイライト）が出て、その状態で drop すると新規フォルダが作られる（重なりが浅い場合は通常の並び替えになる）。

この2点について、Home 画面（`Workspace`/`CellLayout`/`DragLayer`）の実装を参照した再設計方針を以下にまとめる。まだコード変更はしていない。

#### 10.16.1 着地アニメーション（D3(iii) の具体化）

Home 画面の着地アニメーションは `DragLayer.animateViewIntoPosition(...)`（`dragndrop/DragLayer.java:239-244` 他複数オーバーロード）が担っている。

- `Folder.onDrop()`（`folder/Folder.java:1610` 付近）や `Workspace` の一部経路が使う `animateViewIntoPosition(DragView, View child, int duration, View anchorView)`（`DragLayer.java:250`）は、`child.getLayoutParams()` を `CellLayoutLayoutParams` にキャストする（`DragLayer.java:254`）ため、**`CellLayout` 配下の View 専用**であり、`RecyclerView` の子 View（`AllAppsRecyclerView` 配下）にはそのまま使えない。
- 一方、`animateViewIntoPosition(DragView dragView, int[] pos, float alpha, float scaleX, float scaleY, int animationEndStyle, Runnable onFinishRunnable, int duration)`（`DragLayer.java:239-244`）は、**DragLayer 相対の生ピクセル座標 `pos` へアニメーションする**汎用オーバーロードで、レイアウト種別に依存しない。App Drawer 側はこちらを使うのが妥当。
- 実装方針:
  - `SearchContainerView.onDrop()` で `mainList.reorderApp(...)` を呼んで並び順を確定させた**後**、確定した挿入位置に対応する RecyclerView の子 View を再取得する（DiffUtil の move アニメーション後、対象アプリの新しい adapter position → `findViewHolderForAdapterPosition` 等で View を特定）。
  - 対象 View が見つかれば、その View の DragLayer 相対座標を算出し（`Utilities.getDescendantCoordRelativeToAncestor` 等、既存 utility を使う）、`animateViewIntoPosition(dragObject.dragView, pos, ...)` を呼ぶ。
  - 対象 View がまだレイアウトされていない（DiffUtil アニメ中で position が安定しない）等で特定できない場合は、現状どおり `deferDragViewCleanupPostAnimation = false` で即消しにフォールバックする（`Folder.onDrop()` が `d.dragView.hasDrawn()` が false の場合に同様のフォールバックをしているのと同じ考え方、`folder/Folder.java:1610-1622`）。
  - `deferDragViewCleanupPostAnimation` は、着地アニメーションを使うパスでは **true のままにする**（アニメーション終了時に DragView 側が自分で消える。`DragLayer.animateViewIntoPosition` の `onFinishRunnable` 経由、または既存の `deferDragViewCleanupPostAnimation=true` 運用に準拠）。即消しフォールバックのパスでは false にする。

#### 10.16.2 重ねてフォルダ作成（Phase E の着手方針の具体化）

Home 画面のフォルダ作成判定は `Workspace.manageFolderFeedback()`（`Workspace.java:2977-3037`）が担っている。

- 判定の核は**距離ベースの二段階しきい値**: `CellLayout.getFolderCreationRadius(targetCell)`（`CellLayout.java:950-955`）が「アイコン全体が見える半径」と「並び替えが始まる半径」の中間値を返し、この半径より近ければフォルダ作成候補、遠ければ通常の並び替え候補として扱われる。
  - `getFolderCreationRadius()` 自体は `(getReorderRadius(targetCell, 1, 1) + iconVisibleRadius) / 2`（`CellLayout.java:952-954`）で、アイコンサイズと reorder 半径の中間を取っている。
  - `manageFolderFeedback()`（`Workspace.java:2977`）は、この半径内なら `willCreateUserFolder(info, mDragOverView, false)`（`Workspace.java:2170` 系）を見て `DRAG_MODE_CREATE_FOLDER` へ遷移し、`PreviewBackground`（`Workspace.java:2993-3004`）で対象アイコンの背景にフォルダ作成プレビューを表示する。半径外なら `DRAG_MODE_NONE`（通常の並び替え）に戻す（`Workspace.java:2978-2984`）。
- App Drawer（RecyclerView）への移植方針:
  - `SearchContainerView.resolveTargetApp()`（現状は「最も近いアイコン」を返すだけ）を拡張し、**visual center と対象アイコン View の中心との距離**を計算する。この距離をアイコンサイズ相当の半径しきい値と比較し、「フォルダ作成圏内」か「並び替え圏内」かを判定する。RecyclerView の grid セルサイズ（`AllAppsRecyclerView` の cell width/height）から Home 画面の `getFolderCreationRadius()` に相当する値を算出する（アイコンサイズは `mActivityContext.getDeviceProfile().getAllAppsProfile()` 系から取得可能、§3.4 調査時に確認済みの `getCellHeightPx()` 等を参照）。
  - `onDragOver()` にフォルダ作成プレビューの視覚フィードバックを追加する。Home 画面の `PreviewBackground`（`Workspace.java` 内、`CellLayout` 前提のクラス）はそのまま使えないため、対象アイコンの `BubbleTextView` に対する簡易的な拡大/背景ハイライト（例: `setScaleX/Y` や背景 drawable の一時適用）を新設するか、`PreviewBackground` を RecyclerView 環境向けに一般化する必要がある（要調査、着手時に判断）。
  - `acceptDrop()`/`onDrop()` の分岐: フォルダ作成圏内で drop された場合は、既存の並び替え（`reorderApp`）ではなく新規 App Drawer フォルダ作成（`FolderViewModel.createFolder()` 等、既存の Lawnchair App Drawer フォルダ基盤 §3.1 参照）を呼ぶ。§10.3 判断5「フォルダ item は Phase D では動かせない・drop 先にもならない」を、この Phase E 着手をもって見直す。
  - 既存フォルダへの追加（フォルダアイコンへの重ね drop）は Phase F のまま据え置く。

この2点は元計画の D3(iii)（フライバック含む着地演出）と Phase E（フォルダ作成）に相当するが、実装に入る前に「Home 画面の実装をどこまで踏襲するか」を Codex へ渡すタスクとして明確化しておく必要がある。次回セッションでは、まず 10.16.1（アニメーション、影響範囲が小さい）から着手し、その後 10.16.2（フォルダ作成、`PreviewBackground` の扱いなど未決定事項が残る）に進む想定。

→ 実装可能な粒度の詳細設計を §10.17 にまとめた。

### 10.17 追加要望2点の詳細設計（2026-07-06・コード未変更）

§10.16 の2要望を、参照実装の正確な仕様（今回追加調査済み）に基づいて実装タスクに落とせる粒度まで詰めた。

#### 10.17.1 要望1: drop 時の着地アニメーション（D3(iii)）

**参照実装の確定事項:**

- 使うオーバーロードは `DragLayer.animateViewIntoPosition(DragView, int[] pos, float alpha, float scaleX, float scaleY, int animationEndStyle, Runnable onFinishRunnable, int duration)`（`DragLayer.java:239-244`）。DragLayer 相対の生ピクセル座標へ飛ばす汎用版で、`ANIMATION_END_DISAPPEAR`（`DragLayer.java:77`）を渡すとアニメーション終了時に DragView が自動除去される（`:397`）。`CellLayoutLayoutParams` 前提の child 版（`:250-306`）は使えない。
- child 版の内部処理（`:280-303`）が座標・スケール計算とダブり防止の**そのまま流用できる手本**になる:
  - `BubbleTextView` は `DraggableView` を実装しているので、`getWorkspaceVisualDragBounds(destRect)` で「アイコンの視覚矩形」を取得できる（`:286`）。目標座標は View 左上ではなくこの視覚矩形基準で計算する。
  - 目標スケール = `destRect.width() / (dragView.getMeasuredWidth() - dragView.getBlurSizeOutline())`（`:290-291`）+ スケール中心補正（`:295-299`）。
  - **飛行中はターゲットの実 View を `INVISIBLE` にし、完了時に `VISIBLE` へ戻す**（`:302-303`）。これをやらないと、DiffUtil の move アニメーションで動く実アイコンと DragView の**二重表示**になる。

**実装手順（`SearchContainerView.onDrop()` の reorder 確定パスを変更）:**

1. `mainList.reorderApp(...)` で並び順を確定（現状どおり。`onAppsUpdated()` → DiffUtil 配信までは同期だが、**RecyclerView のレイアウトは次フレーム**なのでこの時点で新しい View 位置は取れない）。
2. `dragObject.deferDragViewCleanupPostAnimation` は **true のまま**にし（既定値）、`recyclerView` に `doOnPreDraw`（`androidx.core.view.OneShotPreDrawListener`）を張る。
3. pre-draw コールバック内（新レイアウト確定後）:
   - 移動したアプリの新しい adapter position を `mainList` から逆引き → `findViewHolderForAdapterPosition(pos)` で `itemView`（`BubbleTextView`）を取得。
   - 取得できたら: `dragLayer.getDescendantCoordRelativeToSelf(itemView, coord)` で DragLayer 相対座標を算出し、`getWorkspaceVisualDragBounds` + child 版と同じスケール計算で `toX/toY/toScale` を決定。`itemView.setVisibility(INVISIBLE)` → `animateViewIntoPosition(dragView, pos, 1f, toScale, toScale, ANIMATION_END_DISAPPEAR, { itemView.setVisibility(VISIBLE) }, -1)`（duration -1 = 距離ベース算出に任せる）。
   - 取得できなかったら（画面外へ挿入・ViewHolder 未生成など）: **`dragObject.dragView.remove()` を即時呼ぶ**。
4. **後始末保証がこの設計の生命線**: §10.10 原因1の再発を防ぐため、「defer=true にしたら、pre-draw コールバックが必ず animate か remove のどちらかに到達する」ことを不変条件にする。コールバック内は全経路で処理し、例外時も remove する（try-finally 相当）。eligibility 不成立などで reorder しない経路は従来どおり defer=false の即消しのまま。

受け入れ条件: drop するとアイコンが着地位置へ滑らかに移動して消え、実アイコンと二重表示されない。画面外への挿入・連打・バー drop・Home 由来 drag でゴーストが出ない。

#### 10.17.2 要望2: 重ねてフォルダ作成（Phase E 第1弾）

**参照実装の確定事項:**

- Home の判定は「target セル中心からの距離 ≤ `getFolderCreationRadius()`」（`Workspace.manageFolderFeedback()`、`Workspace.java:2977-2984`）。半径 = `(reorderRadius + ICON_VISIBLE_AREA_FACTOR * iconSizePx / 2) / 2`（`CellLayout.java:950-955`）。半径外に出たら `DRAG_MODE_NONE` に戻す（ヒステリシスなし、同一半径で in/out）。
- **Lawnchair のフォルダ作成 API には「新規フォルダ + 中身を一括作成」がまだ無い**:
  - `FolderService.saveFolderInfo()` は**フォルダ行（title）しか書かない**（`FolderService.kt:54-56`）。中身は `updateFolderWithItems(folderInfoId, ...)`（`:45-52`）だが**既存 id が必要**。
  - `FolderDao.insertFolder()` は戻り値なし（`FolderDao.kt:19`）なので、新規 id を取れない。**`@Insert suspend fun insertFolderReturningId(folder): Long` を DAO に追加**し、`FolderService.createFolderWithItems(title, apps): Int`（insert → 返った id で items を rank=index で書き込み）と `FolderViewModel.createFolderWithApps(title, apps)`（実行後 `reloadHelper.reloadGrid()`、既存メソッドと同じ作法）を新設する必要がある。
  - 既定フォルダ名は設定画面の新規作成と同じ `R.string.my_folder_label`（`AppDrawerFoldersPreference.kt:183`）を使う。リネームは既存の設定画面経由（MVP）。
- 表示への反映は既存経路で完結する: `foldersLiveData` → `LawnchairAlphabeticalAppsList.observeFolders()` → 再構築。`prefs.folderApps`（`pref_hideFolderApps`、**デフォルト true**）が true なら、フォルダ入りしたアプリは A-Z grid から消える（`LawnchairAlphabeticalAppsList.kt:128-133`）。2アプリ入りフォルダは表示条件 `folderApps.size > 1`（`:122`）を満たす。

**実装手順:**

1. **判定（`SearchContainerView` の target 解決を拡張）**: `resolveTargetApp()` が返す「最近傍アイコン + 挿入 index」に加えて **visual center と target View の視覚中心（`getWorkspaceVisualDragBounds` の中心）との距離**を返す。しきい値 `folderCreationRadius = (min(cellW, cellH) / 2 + ICON_VISIBLE_AREA_FACTOR * dp.allAppsIconSizePx / 2) / 2`（`CellLayout.java:952-954` の式の RV 読み替え。`cellW/cellH` は RV の実測セルサイズ）。距離 ≤ 半径 → `CREATE_FOLDER` モード、それ以外 → `REORDER` モード。ドラッグ中アプリ自身は target から除外（現状どおり）。
2. **`onDragOver()` のモード管理（`manageFolderFeedback` 相当）**: モードが `CREATE_FOLDER` に入ったら (a) reorder プレビュー alarm をキャンセル（Workspace が folder モード中に reorder を走らせないのと同じ）、(b) target アイコンに視覚フィードバック。MVP は `PreviewBackground`（CellLayout 前提）を移植せず、**target `BubbleTextView` の scale アニメーション（例: 1.0→1.15、150ms）**で代替する。モードが外れたら scale を戻し、通常の reorder プレビューへ復帰。target が変わった場合も戻してから次へ適用。
3. **`onDrop()` の分岐**: `CREATE_FOLDER` モードなら `reorderApp()` を呼ばず、`mainList` 経由で `FolderViewModel.createFolderWithApps(getString(R.string.my_folder_label), listOf(targetApp, draggedApp))` を呼ぶ。DragView は要望1の着地アニメーションを target アイコン位置に対して再利用（reloadGrid 完了前に target View はまだ画面にあるので座標は取れる。取れなければ即 remove）。scale フィードバックは必ず戻す。
4. **後片付け**: `drawerAppOrder`（手動並び順テーブル）に残る2アプリの rank は**そのまま残してよい**（comparator は存在するアプリしか比較しないため無害。フォルダから出す Phase F でそのまま復元位置として機能する副次メリットもある）。`prefs.drawerListOrder`（フォルダ同士の順序）に新 id を追加しない場合、新フォルダはフォルダ節の**末尾**に並ぶ（`getSortedFolders()` `.kt:74-81` で未登録 id は `Int.MAX_VALUE`）— MVP はこれを仕様とする。
5. **`onDragExit()` / キャンセル経路**: モードを `NONE` に戻し scale フィードバックを解除（`dragComplete=false` のときのみ。§10.15 の revert パターンと同じ分岐）。

**仕様として明示しておく制約（Home との差分、実装前に合意する）:**

- **新規フォルダは drop した位置ではなく、リスト先頭のフォルダ節（の末尾）に現れる。** Lawnchair の App Drawer フォルダは構造上リスト先頭のフォルダ節にまとまって表示されるため（`LawnchairAlphabeticalAppsList.addAppsWithSections()`）、Home のように「その場にフォルダが生まれる」動きにはならない。その場に置きたい場合はフォルダを手動順リストへ interleave する大きな設計変更が必要で、Phase E のスコープ外とする。
- フォルダ作成直後の反映は `reloadGrid()`（全体リロード）経由なので、DiffUtil の部分アニメーションではなく一瞬でリストが組み変わる。
- 既存フォルダへの追加（フォルダアイコンに重ねる = `willAddToExistingUserFolder` 相当）は Phase F のまま。

**実装順**: 要望1（着地アニメーション）→ 要望2の 1〜2（判定+フィードバック、drop はまだ reorder のみ）→ 要望2の 3〜5（フォルダ作成接続）。要望2は DAO/Service/ViewModel の追加（純増・既存無改造）とドラッグ側の分岐が分離できるので、Codex タスクも「基盤」「接続」の2つに分ける。

受け入れ条件（要望2全体）: アイコンを別アイコンの中心近くに重ねると target が拡大表示され、その状態で drop すると2アプリ入りの新規 App Drawer フォルダがフォルダ節に作られる。浅い重なり（半径外）では従来どおり並び替えになる。フォルダ作成後にゴーストが残らず、Room の `Folders`/`FolderItems` に反映され、再起動後も保持される。設定画面のフォルダ一覧にも新フォルダが見える。
