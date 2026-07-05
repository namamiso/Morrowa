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
