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

### 10.18 要望1（着地アニメーション）実装記録（2026-07-06）

§10.17.1 の設計に沿って `SearchContainerView` に drop 着地アニメーションを実装した。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | `onDrop()` を書き換え。`reorderApp()` 確定後、`OneShotPreDrawListener.add(recyclerView) { ... }`（`androidx.core.view`、次のレイアウトパス後に1回だけ発火）で新しいレイアウトが確定するのを待ってから `animateDropLanding()` を呼ぶ。新設した `animateDropLanding()` が、移動先アプリの新 adapter position → `findViewHolderForAdapterPosition()` で対象 `BubbleTextView` を取得し、`getWorkspaceVisualDragBounds()` + `DragLayer.getDescendantCoordRelativeToSelf()` で着地座標・スケールを算出し、対象 View を `INVISIBLE` にしてから `DragLayer.animateViewIntoPosition(dragView, toX, toY, ..., ANIMATION_END_DISAPPEAR, ...)` を呼ぶ（アニメーション終了で対象 View を `VISIBLE` に戻す）。対象 View が特定できない場合は `dragView.remove()` で即時除去にフォールバック |

実装方針の補足:

- **`onDrop()` の2択契約を維持**: 「`deferDragViewCleanupPostAnimation = false` にして即除去」か「デフォルトの true のまま着地アニメーションで自動除去させる」のどちらか一方に必ず到達するようコメントで明示した。§10.10/§10.12 のゴースト再発防止の不変条件をコード上に残す形にした。
- **`ANIMATION_END_DISAPPEAR` を渡す経路の自動除去を確認済み**: `DragLayer.playDropAnimation()` がこのフラグ時に `clearAnimatedView()` を animation end listener として登録し、`clearAnimatedView()` が `DragController.onDeferredEndDrag(dragView)` を呼んで `dragView.remove()` と保留していた `callOnDragEnd()` を実行する（`dragndrop/DragLayer.java:388-420`, `dragndrop/DragController.java:363-373`）。既存の `Folder`/`Workspace` の着地アニメーションと同じ自動除去経路に乗せているため、新たな除去ロジックを自前で書く必要がなかった。
- **座標計算は CellLayout 版から必要な部分だけ移植**: `DragLayer.animateViewIntoPosition(DragView, View child, int, View)`（`:250-306`）の `lp.x`/`translationProvider` によるセル位置の事前計算は、RecyclerView 側では不要（`OneShotPreDrawListener` で実レイアウト後の View を直接使うため）と判断し省略。`getWorkspaceVisualDragBounds()` によるスケール/オフセット計算部分（`:280-300`）のみを移植した。
- **DragView の型**: `DropTarget.DragObject.dragView` フィールドは Java 側で raw 型 `DragView`（`DropTarget.java:58`）だが、`DragView` 自体は `DragView<T extends Context & ActivityContext>`（`dragndrop/DragView.java:77`）というジェネリクスクラスのため、Kotlin 側関数シグネチャでは `DragView<*>` と明示する必要があった（1回目のビルドでコンパイルエラー、`DragView<*>` に修正して解消）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: 1回目のビルドで `DragView` の型引数エラー（`DragView<*>` 不足）が発生、修正して再ビルドし `BUILD SUCCESSFUL in 2m 20s`。

未実施: 実機確認（着地アニメーションが滑らかに表示されるか、対象アイコンとの二重表示がないか、ゴーストが出ないか（通常drop・対象Viewが画面外・バーへのdrop・ドラッグキャンセルの各ケース）、並び替えの確定・永続化、検索/fast scroll/Work タブ/フォルダ/Home 由来 drag の回帰）。

### 10.19 実機確認結果（2026-07-06）: 3症状の報告と、Home 編集画面モデルへの全面再設計

実機報告（§10.18 実装後）:

1. **二重表示**: ドラッグ中、指に追従する DragView と、並び替えプレビューで動く実アイコンの両方が見える。
2. **フォルダが出ない**: 重ねてもフォルダにならない（§10.17.2 は設計のみで未実装のため想定どおりだが、要望としては未達）。
3. **不安定**: 他のアイコンに重なっているときの挙動が不安定（プレビューが暴れる）。

Home 編集画面（SPRING_LOADED）のアニメーション・並び替え・フォルダ作成のコードを精読し、現行 drawer 実装との構造差を特定した上で再設計する。

#### 10.19.1 Home 編集画面の構造（精読結果）

**(a) ドラッグ元アイコンは drag 開始時に非表示になり、動くのは常に「空きスロット」**

- `Workspace.startDrag()`（`Workspace.java:1930-1954`）は drag 開始と同時に `child.setVisibility(INVISIBLE)`（`:1934`）。以後、画面上でアイコン本体は一切見えず、指に追従するのは DragView だけ。
- 並び替えプレビュー（`CellLayout.MODE_SHOW_REORDER_HINT`）で動くのは**周囲のアイコン**であり、ドラッグ中アイテムの実 View ではない。ドラッグ中アイテムは「空きセル」として表現される（`Folder` では `mEmptyCellRank`、`Folder.java:243`）。
- drop 失敗/キャンセル時は `cell.setVisibility(VISIBLE)` で復帰（`Workspace.java:2515` ほか）。drop 成功時は着地アニメーション完了時に VISIBLE へ戻す（`DragLayer.java:302-303`）。
- **現行 drawer 実装との差**: All Apps 由来 drag には `startDrag():1934` に相当する非表示化が存在しない（従来は drawer が閉じるので不要だった）。さらに現行の `previewReorder()` は「ドラッグ中アプリ自身を pendingOrder 内で移動」させるため、**可視状態の実アイコンが DiffUtil move でホバー位置へ動いてしまう** — これが症状1の正体。

**(b) 並び替えの励振防止は「セル単位のターゲット + 変化時のみ再アーム + 3層ゾーン + 650ms」の組み合わせ**

- ターゲットは最近傍**セル**（`findNearestArea`、`Workspace.java:2796-2798`）。ドラッグ中アイテム自身の空きセルもターゲットになり得る（その場合は何も起きない）。
- reorder alarm（`REORDER_TIMEOUT = 650`、`Workspace.java:271`。`Folder` の 250ms より長い）は **ターゲットセルが前回から変わったときだけ**再アームされる（`mLastReorderX/Y` 比較、`:2841`）。同じセル上に居続ける限り何も再発火しない。
- 距離による3層ゾーン: セル中心からの距離が `getFolderCreationRadius()` 以内 → フォルダ判定、`getReorderRadius()` 以内 → reorder 判定（`:2842`）、それより外 → **何もしない dead zone**。
  - `getReorderRadius()`（`CellLayout.java:960-981`）はフォルダ作成可能な 1x1 ターゲットに対しては「セル中心から（スペーシング込み）セル矩形の最近辺までの距離」（`:968-976`、コメント: "don't start reordering too soon before accepting a folder drop"）。
  - `getFolderCreationRadius()` = `(reorderRadius + ICON_VISIBLE_AREA_FACTOR * iconSizePx / 2) / 2`（`CellLayout.java:950-955`）。
- フォルダモード優先: `manageFolderFeedback()` が先に評価され（`Workspace.java:2807`）、`DRAG_MODE_CREATE_FOLDER` 中は reorder 分岐に入らない（`:2840` の mode 条件）。フォルダモードに入ると reorder の一時状態は revert される（`:2816-2821`）。
- **現行 drawer 実装との差**: ターゲットが「ドラッグ中アプリを除く最近傍の他アプリ」なので、プレビュー適用後に自分のスロット上をホバーしていると隣のアプリが次のターゲットに解決され、target 変化 → alarm → プレビュー → また target 変化…と**発振する**。ゾーンも1層（全域 reorder）で、alarm も 250ms — これが症状3の正体。

**(c) フォルダ判定には「アニメーション中の View を除外する」ガードがある**

- `willCreateUserFolder()`（`Workspace.java:2170-2193`）の除外条件: ①ターゲット View が reorder アニメーションの一時座標にいる間は判定しない（`lp.useTmpCoords` チェック、`:2172-2176`）②ターゲットがドラッグ元自身（`hasntMoved`、`:2179-2182`）③drop 時は「ホバー中にフォルダモードへ入っていたか」を要求（`considerTimeout && !mCreateUserFolderOnDrop`、`:2184`。drop 座標での再計算をしない）。
- ホバー演出は `PreviewBackground.animateToAccept()`（`Workspace.java:2993-3004`）、drop 確定は `createUserFolderIfNecessary()`（`:2221-`）→ `FolderIcon.performCreateAnimation()`（DragView を吸い込む）。

#### 10.19.2 再設計: 空きスロットモデルへの移行

**R1: ドラッグ元アイコンの非表示化（症状1の修正・最優先）**

- `LawnchairAlphabeticalAppsList` に `draggedComponentKey: String?` を追加。drag 開始（`SearchContainerView` を `DragController.addDragListener` に登録して `onDragStart` で検知。App Drawer 由来 drag のみ）でセットし、`onDragEnd` で必ずクリア。
- 非表示化の適用は **RecyclerView の `OnChildAttachStateChangeListener` 方式**（AOSP の adapter 無改造で完結）:
  - attach 時: その child の adapter item が dragged key と一致 → `INVISIBLE`、それ以外 → `VISIBLE`（recycle された INVISIBLE View の復帰漏れを防ぐため必ず両方向を設定）。
  - drag 開始時にも既に attach 済みの可視 children を1回走査して適用。
- `previewReorder()` は現行どおり「ドラッグ中アプリを pendingOrder 内で移動」でよい。**動く View が INVISIBLE になるため、見た目は「空きスロットが移動し、隣接アイコンが DiffUtil move で避ける」= Home と同じ表現になる。**
- 復帰経路（Home の `Workspace.java:2515` 相当）:
  - reorder drop 成功 → 既存の着地アニメーション（§10.18）のターゲットがドラッグ中アプリ自身の（INVISIBLE な）View になるだけで、完了時 VISIBLE 復帰は実装済みコードがそのまま機能する。
  - キャンセル / バー（Uninstall・Add to home screen）への drop / Workspace など他 target への drop → `onDragEnd` で dragged key をクリアし、可視 children を走査して VISIBLE 復帰。Uninstall 成功時はリスト更新で item 自体が消えるので復帰は無害な no-op。

**R2: スロットベースのターゲット解決 + 3層ゾーン + 650ms（症状3の修正）**

- `resolveTargetApp()`（アプリ単位・自分除外）を廃止し、`resolveTargetSlot()` に置き換える: 最近傍の**アイコンスロット**（`VIEW_TYPE_ICON` の child。**ドラッグ中アプリ自身のスロットを含む**）を返す。自分のスロットが最近傍なら「ターゲット変化なし」として扱い、何もしない（Home でドラッグ元の空きセルにホバーしている状態に相当）。
- `lastTargetSlotIndex` を保持し、**スロットが変わったときだけ** reorder alarm を再アーム（`Workspace.java:2841` の `mLastReorderX/Y` 比較に相当）。alarm は **650ms**（`REORDER_TIMEOUT`、`Workspace.java:271` と同値）へ変更。
- 距離3層ゾーン（target スロットの視覚中心からの距離 `dist`）:
  - `dist ≤ folderRadius` かつ target が他アプリ → **FOLDER 候補**（R4 実装まではゾーンとして予約し、何もしない = reorder が発火しない dead zone として先に入れる。これだけでも「深く重ねたときの不安定」が消える）。
  - `folderRadius < dist ≤ reorderRadius` → **REORDER**（上記 alarm 条件で preview）。
  - `dist > reorderRadius` → 何もしない。
  - 半径の算出（`CellLayout.java:950-981` の RV 読み替え）: `cellW/cellH` は target child の実測 width/height、`iconVisibleRadius = ICON_VISIBLE_AREA_FACTOR * dp.allAppsIconSizePx / 2`、`reorderRadius = min(cellW, cellH) / 2`（セル中心から最近辺まで）、`folderRadius = (reorderRadius + iconVisibleRadius) / 2`。
- アニメーション中ガード（`Workspace.java:2172-2176` の `useTmpCoords` 相当）: target child が DiffUtil の move アニメーション中（`recyclerView.itemAnimator?.isRunning == true` の間、または `child.translationX/Y != 0`）は**フォルダ判定もターゲット更新もスキップ**する。プレビュー直後の座標が不安定な瞬間に判定しない。

**R3: フォルダ作成基盤（§10.17.2 のまま・純増のみ）**

- `FolderDao.insertFolderReturningId(): Long` + `FolderService.createFolderWithItems(title, apps): Int` + `FolderViewModel.createFolderWithApps(title, apps)`（完了後 `reloadHelper.reloadGrid()`）。既定タイトル `R.string.my_folder_label`。変更なしの詳細は §10.17.2 参照。

**R4: FOLDER モードの接続（症状2の解消）**

- R2 の FOLDER 候補ゾーンに入ったら（かつ target child がアニメーション中でない・target が自分でない）: `dragMode = CREATE_FOLDER`、reorder alarm cancel、target `BubbleTextView` を scale アップ（1.0→1.15、150ms。`PreviewBackground.animateToAccept()` の代替）。ゾーンから出るか target が変わったら scale を戻して `NONE` へ。
- drop: **ホバーで FOLDER モードに入っていた場合のみ**フォルダ作成（`mCreateUserFolderOnDrop` 相当のフラグを acceptDrop/onDrop へ引き継ぐ。drop 座標での再判定はしない、`Workspace.java:2184` と同じ思想）。`createFolderWithApps(my_folder_label, [target, dragged])` → DragView は target アイコン位置への着地アニメーション再利用（取れなければ即 remove）→ `reloadGrid()` で反映。scale は必ず復元。
- REORDER モードで drop した場合は従来の `reorderApp()` 経路。

**実装順とタスク分割**: R1 → R2 →（ビルド+実機で症状1/3の解消を確認してから）→ R3 → R4 → 総合検証。R1/R2 は `SearchContainerView` + `LawnchairAlphabeticalAppsList` に閉じ、R3 は data 層の純増、R4 が両者を接続する。各ステップの受け入れ条件:

- R1: ドラッグ中、実アイコンが見えるのは DragView の1つだけ。プレビューは「空きが移動して他アイコンが避ける」見た目になる。キャンセル・バー drop 後にアイコンが欠けたまま/二重のまま残らない。
- R2: 同じアイコンの上に指を置き続けてもプレビューが発振しない。浅い重なり（reorder ゾーン）でのみ並び替えが起き、深い重なり（folder ゾーン）と遠い位置では何も起きない。
- R4: 深い重なりで target が拡大表示され、その状態の drop で2アプリ入りフォルダがフォルダ節に作られる（§10.17.2 の受け入れ条件を継承）。拡大表示が残留しない。

### 10.20 R1+R2 実装記録（2026-07-07）

§10.19.2 の R1（ドラッグ元アイコンの非表示化）と R2（スロットベースのターゲット解決 + 3層ゾーン + 650ms）を実装した。R3（フォルダ作成基盤）・R4（フォルダモードの接続）は未着手（設計順序どおり）。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/Launcher.java` | `setupViews()` で、`mAppsView` が `DragController.DragListener` を実装している場合に `mDragController.addDragListener(...)` を追加登録（`DragController` の import 追加） |
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | `draggedComponentKey: String?` プロパティを追加（R1）。純粋な View 可視性トグル用の状態であり、意図的に `onAppsUpdated()`/DiffUtil を経由しない（adapter の並び順自体は変わらないため） |
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | `DragController.DragListener` を追加実装。`onDragStart`/`onDragEnd` で `draggedComponentKey` の設定/クリアと、`RecyclerView.OnChildAttachStateChangeListener` によるドラッグ元アイコンの `INVISIBLE`/`VISIBLE` 切り替えを行う（R1）。`resolveTargetApp`（自分除外・距離無制限）を `resolveTargetSlot`（自分含む最近傍スロット）+ `classifyZone`（FOLDER/REORDER/NONE の3層判定）+ `isSlotAnimating`（アニメーション中ガード）に置き換え、`acceptDrop`/`onDragOver` の両方でこれらを使うよう統一（R2）。`prevTargetIndex`（アプリ単位の index 比較）を `lastTargetSlotKey`（スロット単位の component key 比較）に置き換え、`REORDER_PREVIEW_DELAY_MS` を 250ms → 650ms に変更 |

実装方針の補足:

- **R1 の適用範囲**: `draggedComponentKey` はメインタブ（`getPersonalAppList()`）のみに適用し、Work タブからの drag には非表示化を適用していない。理由: `LawnchairAlphabeticalAppsList` インスタンス自体はメイン/ワーク/検索の3つ存在するが、並び替え機能自体がメインタブ限定（§10.3 決定3）であり、「どのタブの RecyclerView が対象か」を安全に特定する公開 API（`ActivityAllAppsContainerView.mAH`/`AdapterHolder.mRecyclerView` はいずれもパッケージプライベートで別パッケージの `SearchContainerView` から参照不可）が存在しないため。ドラッグ開始時点の `activeRecyclerView`/`isPersonalTab` を使い、メインタブ表示中の drag だけを対象にした。
- **R2 のゾーン境界とヒステリシス**: `classifyZone()` は Home の `CellLayout.getFolderCreationRadius()`/`getReorderRadius()` の式をそのまま踏襲し、`min(cellW, cellH) / 2` を reorder 半径、`(reorderRadius + iconVisibleRadius) / 2` を folder 半径とした。Home 同様ヒステリシスは設けていない（同一半径で in/out）。
- **アニメーション中ガード**: `Workspace.willCreateUserFolder()` の `useTmpCoords` チェック（`Workspace.java:2172-2176`）に相当する判定として、`isSlotAnimating()` で `child.translationX/Y != 0` または `recyclerView.itemAnimator?.isRunning == true` を見ている。後者は RecyclerView 全体のアニメーション実行状態（個々の child 単位ではない）だが、DiffUtil の move アニメーションはリスト全体でまとまって発生するため実用上十分と判断した。
- **FOLDER ゾーンの現状**: R4 未実装のため、`onDragOver`/`acceptDrop` とも FOLDER ゾーンでは何もしない（reorder プレビューを発火させない）dead zone として扱う。深く重ねた状態で drop しても `acceptDrop()` が false を返し、drop 自体が不成立になる（§9.7 の修正により、Drawer 由来 drag はこの場合も強制的に Home へは戻らず、Drawer 内に留まる）。
- **コンパイルエラーの修正**: 初回ビルドで `mainList.getOrderedApps().indexOfFirst { it.toComponentKey() == slotKey }` が `ComponentKey` と `String` の比較になりコンパイルエラー（`slotKey` は `String`）。`it.toComponentKey().toString() == slotKey` に修正して解消。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: 1回目のビルドで型不一致によるコンパイルエラー、修正して再ビルドし `BUILD SUCCESSFUL in 9m 42s`。

未実施: 実機確認（症状1: ドラッグ元アイコンが非表示になり DragView との二重表示が解消されているか、症状3: 同じアイコンに指を置き続けてもプレビューが発振しないか、深い重なり（folder ゾーン）が静かな dead zone になっているか、症状2: フォルダ自体はまだ作られない（R4 未実装のため想定通り）、検索/fast scroll/Work タブ/フォルダ/バー/Home 由来 drag/通常の並び替えと永続化の回帰確認）。

### 10.21 実機確認結果（2026-07-07）: 症状3のみ解消・症状1の真の根本原因を特定

実機報告: R1+R2 導入後、**症状3（不安定）は解消**したが、**症状1（二重表示）は解消していない**。症状2（フォルダ）も出ない。

#### 症状2について（バグではない）

R3/R4 は設計順序どおり**未実装**（§10.20 冒頭に記載。R1/R2 の実機確認を先に通す計画だった）。現状の FOLDER ゾーンは「何も起きない dead zone」として動いており、これは §10.20 の想定どおりの挙動。フォルダが作られるようになるのは R3+R4 実装後。

#### 症状1の真の根本原因: `AdapterItem` の DiffUtil 識別が「アプリの同一性」を見ていない

R1 の「ドラッグ元 View を INVISIBLE にする」実装自体は正しく動いているが、前提が崩れていた。

- `AdapterItem.isSameAs()`（`allapps/BaseAllAppsAdapter.java:154-156`）は **`viewType` と class しか比較しない**。つまり DiffUtil（`AlphabeticalAppsList.updateAdapterItems()` の `MyDiffCallback`）から見ると、**アイコン item はどの位置のどのアプリでも「同一 item」**である。
- `isContentSame()`（`:162-164`）は `itemInfo == null && other.itemInfo == null` なので、アイコン item（itemInfo 非 null）は**常に「内容が違う」**。
- さらに `DiffUtil.calculateDiff(new MyDiffCallback(...), false)`（`AlphabeticalAppsList.java:362-364`）は第2引数 `detectMoves = false`。
- 帰結: `previewReorder()` でリスト順を変えたとき、DiffUtil は **MOVE を一切発行せず、影響範囲の全 position に CHANGE（rebind）を発行**する。つまり「View が動く」のではなく「**各 View に表示されるアプリが再割り当てされる**」:
  - drag 開始時に INVISIBLE にした View（当時ドラッグ中アプリを表示していた）は、プレビュー後は**隣のアプリを表示する INVISIBLE な View** になる（無関係なアプリが1つ消える）。
  - ドラッグ中アプリの情報は、プレビュー先の position にある**別の VISIBLE な View に rebind** される → **二重表示はここから来ている**。
  - `OnChildAttachStateChangeListener` は rebind では発火しない（attach/detach が起きない）ため、R1 の仕掛け全体が素通りされる。
- これは「Home のコードを参考にしたのに直らない」ことの説明でもある: Home の `CellLayout` は **View とアイテムの対応が固定**（`startDrag():1934` で View を隠せばそのアイテムはずっと隠れている）なのに対し、RecyclerView は**識別ベースの diff がなければ rebind で対応関係が動く**。R1 は「View を隠す」という Home の手段だけを移植し、その前提（View⇔アイテムの固定対応）が RV 側に存在しなかった。

なお症状3が解消したのは、R2 の修正（スロット化・3層ゾーン・650ms・アニメ中ガード）が geometry 側の問題であり、この識別問題と独立だったため。

#### 修正方針: `AdapterItem` に識別ベースの diff を入れる（LauncherState 追加は不要）

「App Drawer 専用の編集 state を追加するか」という論点への回答: **不要**。この問題は `LauncherState` と無関係の RecyclerView adapter の識別問題であり、state を追加しても1行も変わらない。方針B（ALL_APPS のまま drag）は引き続き妥当。

修正内容（次セッションで実装）:

1. `AdapterItem.isSameAs()` を識別ベースへ変更（`BaseAllAppsAdapter.java:154-156`、AOSP への最小差分）:
   - `viewType`/class 一致に加え、`VIEW_TYPE_ICON` は `itemInfo.componentName` + `itemInfo.user` の一致を要求する（null は不一致扱い）。
   - `VIEW_TYPE_FOLDER` は `folderInfo.title` の一致を要求（LC の drawer フォルダは rebuild ごとに `FolderInfo` を新規生成し id 未設定のため、title が実用的な識別子）。
   - その他の viewType は従来どおり（viewType 一致のみ）。
2. `isContentSame()` はアイコンについて `itemInfo == other.itemInfo`（参照一致）へ変更。モデル更新（アプリ更新・ラベル変更）では `AppInfo` が別インスタンスになるので rebind が走り、単なる並び替えでは rebind されない。
3. 期待される効果:
   - 並び替え（preview/確定とも）が **本物の位置移動**として配信され、`detectMoves=false` でも「ドラッグ中アプリの remove+insert + 他アプリの自動スライド」になる（DiffUtil は identity が安定していれば他 item を動かさない）。**Home と同じ「隙間が動き、隣が滑って避ける」アニメーションが RecyclerView 標準の ItemAnimator で実現される。**
   - R1 の INVISIBLE 化が成立する: INVISIBLE な View はドラッグ中アプリを表示し続け、rebind で別アプリに割り当てられない。挿入で新しく attach される View は既存の `OnChildAttachStateChangeListener` が捕捉して INVISIBLE を適用する。
4. リスク / 回帰確認ポイント: `AdapterItem` の diff 挙動は App Drawer の**全更新経路**（アプリのインストール/アンインストール、ラベル・アイコン変更、検索結果リスト、Work/Private タブ、フォルダ表示、fast scroll の行計算）に影響する。識別ベース diff は RecyclerView の標準作法であり全面的に安全側の変更だが、上記の各経路を回帰確認に含める。特に検索結果（`SearchAdapterItem`、class 比較で従来どおり分離される）と private space ヘッダー周りを確認する。

実装順: この修正(1)(2) → 実機で症状1の解消を確認 → R3（フォルダ基盤）→ R4（フォルダ接続、§10.19.2 のまま）。

### 10.22 `isSameAs`/`isContentSame` 修正 実装記録（2026-07-07）

§10.21 の診断に沿って `AdapterItem`（AOSP 由来）を最小差分で修正した。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/allapps/BaseAllAppsAdapter.java` | `AdapterItem.isSameAs()`: `VIEW_TYPE_ICON` は `itemInfo.componentName` + `itemInfo.user` の一致（`Objects.equals`、null は不一致）を追加要求。`VIEW_TYPE_FOLDER` は `folderInfo.title` の一致を追加要求。それ以外の viewType は従来どおり viewType/class 一致のみ。`isContentSame()`: 両方 `itemInfo == null` なら true（従来どおり、フォルダ等はここで完結）、片方だけ null なら false、両方非 null（アイコン）なら参照一致 `itemInfo == other.itemInfo` に変更。`java.util.Objects` の import を追加 |

実装方針の補足:

- **AOSP への差分は診断どおり最小**: `isSameAs()`/`isContentSame()` の2メソッドのみ変更、シグネチャ・呼び出し側（`AlphabeticalAppsList.MyDiffCallback`）は無改造。
- **`SearchAdapterItem` への影響なし**: `lawnchair/src/app/lawnchair/search/adapter/SearchAdapterItem.kt` が独自に `isSameAs`/`isContentSame` を override しており、`getClass() != getClass()` チェック（今回変更していない）で他の `AdapterItem` とは元々分離されている。
- **フォルダの `isContentSame` は現状維持**: フォルダ item は `itemInfo` が常に null（`AdapterItem.asFolder()` は `itemInfo` を設定しない）ため、修正後も「両方 null → true」の分岐に入り、内容比較は従来と同じ粗さのまま（title 一致していれば常に「内容同じ」）。今回のスコープは識別（`isSameAs`）のみで、フォルダの内容変化検知の精緻化は対象外（既存の粗さを継承するだけで新規の劣化ではない）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 2m 34s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（症状1: 並び替えドラッグ中に二重表示が解消しているか、ドラッグ元 View が別アプリに rebind されず INVISIBLE のまま保たれるか、並び替えの確定・永続化、検索/fast scroll/Work タブ/フォルダ表示/アプリのインストール・アンインストール時のリスト更新/private space ヘッダーの回帰確認）。

### 10.23 実機確認結果（2026-07-07）: 症状1解消を確認

ユーザー確認: **§10.19〜§10.21 で追跡していた症状1（並び替えドラッグ中の二重表示）は `AdapterItem.isSameAs()`/`isContentSame()` の識別ベース diff 修正（§10.22）により解消**。

これで §10.16 で報告された3症状すべてに決着がついた。

| 症状 | 状態 |
|---|---|
| 1. 二重表示 | **解消**（§10.22 の識別ベース diff 修正） |
| 2. フォルダが出ない | 未解決だが仕様どおり（R3/R4 未実装、意図的な dead zone） |
| 3. 不安定（発振） | **解消**（§10.20 R2 の3層ゾーン + 650ms + アニメ中ガード） |

残る回帰確認項目（検索/fast scroll/Work タブ/フォルダ表示/インストール・アンインストール時のリスト更新/private space ヘッダー）は次回以降に持ち越し。次のステップは R3（フォルダ作成基盤）→ R4（フォルダモードの接続）。

### 10.24 並び替え閾値の修正（2026-07-07）: reorder 半径が Home の式と一致していなかった

ユーザーから「アプリ入れ替えの閾値を下げたい、Home のコードと同じにしてほしい」との指摘。`classifyZone()` の `reorderRadius` 計算を確認したところ、Home の実式と異なっていた。

原因: `CellLayout.getReorderRadius()`（`CellLayout.java:960-981`）は、対象セルが `canCreateFolder` かつ 1x1 の場合（App Drawer のアイコンは常にこれに該当）、中心からの最短距離を **`cellBoundsWithSpacing`**（セル本来の矩形を `mBorderSpace` の半分だけ外側へ拡張した矩形、`CellLayout.java:517` の `inset(-mBorderSpace.x / 2, -mBorderSpace.y / 2)`）の最近辺までで計算する。つまり **隣接セルとの隙間（gutter）の半分まで reorder 半径が食い込む**。

これに対し、これまでの実装は `min(cellW, cellH) / 2`（対象 View 自身の幅/高さの半分のみ、gutter 分を含まない）だったため、Home の実際の reorder 半径より**狭く**なっていた（＝並び替えを発火させるには Home よりも正確に中心へ寄せる必要があった＝ユーザーの言う「閾値が高い」状態）。

対応: `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` の `classifyZone()` を修正。`mActivityContext.getDeviceProfile().getAllAppsProfile().getBorderSpacePx()`（`Point`、Kotlin から `borderSpacePx.x`/`.y` でアクセス）を取得し、`reorderRadius = min(cellW / 2 + borderSpace.x / 2, cellH / 2 + borderSpace.y / 2)` に変更。`folderRadius` の式（`(reorderRadius + iconVisibleRadius) / 2`）は変更なし（`reorderRadius` が広がった分、連動して広がる）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 44s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（並び替えが Home 相当の緩さで発火するか、フォルダゾーンの dead zone が引き続き自然か、症状1/3の再発がないか）。

### 10.25 感度修正2件目（2026-07-07）: 距離計算の基準点がアイコン画像の中心とズレていた

§10.24 の reorder 半径修正後もユーザーから「感度がやや悪い」との報告があり、追加調査した。

原因: `resolveTargetSlot()` の距離計算が `child.left + child.width / 2f` / `child.top + child.height / 2f`（**RecyclerView の子 View 全体の幾何中心**）を基準点にしていた。しかし App Drawer のアイコンセルは `BubbleTextView` の縦レイアウト（アイコン画像が上部、ラベルがその下）で構成されており、`BubbleTextView.getIconBounds()`（`BubbleTextView.java:953-964`）を見ると、縦レイアウト時は `outBounds.offset((getWidth() - iconSize) / 2, getPaddingTop())` — **アイコン画像自体は View 全体の上寄りに位置し、View 全体の幾何中心とは一致しない**（ラベル分だけ View の実際の中心が下にずれる）。

ユーザーは自然にアイコン画像そのものへ指を近づけるが、距離判定は「アイコン+ラベルを含むセル全体」の中心を基準にしていたため、実際に意図した位置とシステムが測る位置にズレが生じ、結果として「Home より感度が悪い」ように感じられていた。

対応: `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` に `iconVisualCenter(child: View)` を追加。`child` が `DraggableView`（`BubbleTextView` が実装、着地アニメーション §10.18 で既に使っている `getWorkspaceVisualDragBounds()` と同じ API）なら、そのアイコン画像の視覚的矩形の中心を返す（`Rect.exactCenterX()`/`exactCenterY()`、`child` の RV 内座標へオフセット）。`DraggableView` でない場合や矩形が空の場合は従来どおり View 全体の幾何中心にフォールバックする。`resolveTargetSlot()` はこの `iconVisualCenter()` を基準に距離を計算するよう変更。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 10s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（並び替えの感度が Home と同等に感じられるか、既存修正（二重表示解消・発振解消・フォルダゾーンの dead zone）の再発がないか）。

ユーザー確認（2026-07-07）: **感度・既存修正の再発なし、すべて完了**。

### 10.26 R3（フォルダ作成基盤）実装記録（2026-07-07）

§10.17.2 の設計に沿って、新規フォルダ + 中身を一括作成するデータ層を追加した。UI/drag との接続（R4）はまだ行っていない、純増のみの変更。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/data/folder/service/FolderDao.kt` | `insertFolderReturningId(folder): Long`（`insertFolder` と同じ `@Insert(onConflict = REPLACE)` だが生成された row id を返す）と `createFolderWithItems(folder, items: (Int) -> List<FolderItemEntity>): Int`（`@Transaction`。`folder.copy(id = 0)` で必ず新規行として insert し、返った id を使って `items` ラムダから item 一覧を生成して `insertFolderItems` へ渡す）を追加 |
| `lawnchair/src/app/lawnchair/data/folder/service/FolderService.kt` | `createFolderWithItems(title, appInfos): Int`（`FolderInfoEntity(title = title)` を作り、`appInfos` を `rank = index` で `FolderItemEntity` に変換して DAO の `createFolderWithItems` を呼ぶ） |
| `lawnchair/src/app/lawnchair/data/folder/model/FolderViewModel.kt` | `createFolderWithApps(title, appInfos)`（既存の `createFolder`/`updateFolderItems` と同じパターン: `viewModelScope.launch` 内で repository 呼び出し後 `reloadHelper.reloadGrid()`） |

実装方針の補足:

- **既存 API との使い分け**: `insertFolder`（title のみ、既存の「設定画面から新規フォルダ作成」フロー用）、`updateFolderWithItems`（既存フォルダへの id 指定 upsert、フォルダ編集画面用）はどちらも変更していない。今回追加した `createFolderWithItems` 系だけが「新規フォルダ + 初期メンバーを1回で作る」経路。
- **`insertFolderReturningId` が必要だった理由**: `FolderInfoEntity.id` は `@PrimaryKey(autoGenerate = true)` だが、既存の `insertFolder(folder: FolderInfoEntity)` は戻り値 `Unit` のため、Room が生成した新しい id を呼び出し元が知る手段がなかった。返り値を `Long` にした `insertFolderReturningId` を追加することで解決。
- **既定タイトル**: `FolderViewModel.createFolderWithApps()` の呼び出し側（R4 実装時）で `R.string.my_folder_label`（`lawnchair/res/values/strings.xml:146`、既存の「新規フォルダ」既定名と共用）を渡す想定。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 5m 12s`。新規/変更ファイル起因のエラーなし。

未実施: 実機での動作確認（R3 は UI/drag と未接続のため、この時点では実機で直接観察できる変化はない。R4 実装後にまとめて確認する）。

### 10.27 R4（フォルダモードの接続）実装記録（2026-07-07）

§10.19.2 R4 の設計に沿って、R2 の FOLDER ゾーンと R3 のフォルダ作成基盤を接続した。

変更ファイル:

| ファイル | 内容 |
|---|---|
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | `createFolder(title, apps)` を追加。既存の `viewModel`（このインスタンスが `observeFolders()` 等で既に保持している `FolderViewModel`）の `createFolderWithApps()` へ薄く委譲するだけ |
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | 大幅拡張。`hoverFolderTargetApp`（ホバー中に FOLDER ゾーンへ入った対象アプリ、`acceptDrop` が読む永続状態）、`scaledFolderTargetView` + `applyFolderHoverScale()`/`clearFolderHoverScale()`（対象アイコンを 1.0→1.15、150ms で拡大/復元）を追加。`lastTargetSlotKey`（スロットのみ）を `lastSlotSignature`（`"$slotKey:$zone"`、スロット+ゾーンの組）に置き換え。`acceptDrop`/`onDrop` は `hoverFolderTargetApp` が非 null なら `mainList.createFolder(...)` を呼ぶ分岐を追加し、着地アニメーションの座標計算を `animateDragViewOnto()` として reorder/フォルダ作成の両方から共有する形に整理 |

実装方針の補足:

- **ゾーンを識別子に含めた理由**: 当初の設計メモは「スロットが変わったときだけ再アーム」（Home の `mLastReorderX/Y` 相当）だったが、そのままではFOLDERゾーンとREORDERゾーンを同じアイコン上で行き来した場合（一度FOLDERに入って reorder プレビューをキャンセルした後、指を少し離してREORDERゾーンへ戻る等）に、スロットキーが変化しないため reorder プレビューの再スケジュールが発火しない問題があった。`slotKey` と `zone` の両方を識別子に含めることで、Home の `manageFolderFeedback()` が **毎回無条件に評価される**（`Workspace.java:2807`）という実際の挙動によりよく合わせつつ、reorder alarm の再アームは「識別子（スロット+ゾーン）が変わったときだけ」という制約を維持した。
- **FOLDER ゾーンに入った際の reorder プレビュー取り消し**: `mainList.cancelPendingReorder()` を呼ぶことで、Home の `setDragMode()` が `DRAG_MODE_CREATE_FOLDER` へ入る際に `cleanupReorder(true)` を呼ぶのと同じ意図（フォルダモードとreorderの視覚状態が同時に出ないようにする）を実現した。
- **drop 時の判定は「ホバー時に確定した状態」を使う**: `acceptDrop()` は `hoverFolderTargetApp`（`onDragOver` で設定され、`onDragExit` の `dragComplete=true` 分岐では消されない、`dragComplete=false` の genuine exit でのみクリアされる）を読むだけで、drop 座標での再判定はしない。これは Home の `mCreateUserFolderOnDrop`（`Workspace.java:2184`）と同じ設計思想。
- **着地アニメーションの共通化**: `animateDropLanding()`（reorder 用）と `createFolderAndAnimateDrop()`（フォルダ作成用）が同じ `animateDragViewOnto(dragView, targetView)` を呼ぶよう整理した。どちらも「target の位置に着地→target を一瞬 INVISIBLE→アニメ終了で VISIBLE 復元」という同じパターンのため。
- **フォルダ作成後の DragView 着地先**: フォルダ作成時、`targetApp` の現在の View 位置へ着地させる（`reloadGrid()` によってフォルダ節にまとめて表示されるようになるまでの間、視覚的な連続性を保つため）。取得できなければ即 `remove()`。
- **新規フォルダのタイトル**: `resources.getString(R.string.my_folder_label)`（`com.android.launcher3.R`、既存の「新規フォルダ」既定名と共用、§10.17.2 で確認済み）。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 35s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（§10.17.2/§10.19.2 の受け入れ条件: 深く重ねると target が拡大表示されるか、drop で2アプリ入りの新規フォルダがフォルダ節に作られるか、設定画面のフォルダ一覧にも見えるか、浅い重なりは従来どおり並び替えになるか、拡大表示がドロップ/キャンセル/バーへの寄り道いずれの経路でも残留しないか、ゴーストが出ないか、検索/fast scroll/Work タブ/Home 由来 drag の回帰）。

### 10.28 R4 インストール記録（2026-07-07）

`installLawnWithQuickstepGithubDebug` で実機（`SC-52C - 16`）へインストール。通常より大幅に時間がかかり（`BUILD SUCCESSFUL in 19m 35s`）、原因は未調査だが、ビルド自体は正常終了・成果物のインストールも成功した。`adb shell am start` で起動、プロセス生存・crash ログなしを確認済み。

§10.17.2/§10.19.2 の受け入れ条件（拡大表示、フォルダ作成、設定画面との整合、浅い重なりでの従来動作、拡大表示の残留なし、ゴーストなし、検索/fast scroll/Work タブ/Home 由来 drag の回帰）の実機確認はユーザーへ依頼中、結果は未取得。次回セッション以降、確認結果が得られ次第この節に追記する。

### 10.29 実機確認結果（2026-07-07）: フォルダ未作成の報告 + 2件の追加要望（コード未変更・次回対応）

ユーザーから実機確認結果と2件の追加要望があった。**このセッションではコードを一切変更していない**（指示どおり、原因調査と要望内容を記録するのみ）。

#### 報告1: フォルダ作成が発火しなかった（バグ、要調査）

深い重なりで drop してもフォルダが作られなかった。コードを変更せず、既存実装（§10.27）を再点検し、原因になり得る箇所を2つ特定した。優先度の高い順に記載する。

**仮説A（最有力）: フォルダ/並び替えゾーンの境界にヒステリシスがなく、release 直前の指の微小な揺れで `hoverFolderTargetApp` が null に戻り得る**

- `classifyZone()`（`SearchContainerView.kt`）は距離に対する単純な閾値判定で、in/out に別の閾値を使うヒステリシスを持たない（§10.19.2 の設計時点で「Home 同様ヒステリシスは設けていない」と明記済み）。
- `onDragOver()` は、解決したスロット+ゾーンの識別子（`lastSlotSignature`）が**前回と変わるたびに** `hoverFolderTargetApp = null` を無条件で実行してから、ゾーンが FOLDER の場合だけ改めてセットし直す構造になっている。
- 人間の指は静止しているつもりでも微小に動き続けるため、ちょうど FOLDER/REORDER の境界付近で保持していると、`onDragOver` が呼ばれるたびに判定がFOLDER⇄REORDERの間で揺れ得る。
- **指を離す直前の最後の `onDragOver` 呼び出しでたまたま REORDER 側に倒れていた場合、`hoverFolderTargetApp` はその時点で null に戻っている。** その直後の release で `DragController.drop()` が呼ぶ `onDragExit`（`dragComplete=true` のため状態はクリアされない）→ `acceptDrop()` は、この時点で既に null になっている `hoverFolderTargetApp` を読むため、フォルダ作成分岐に入らず通常の並び替えとして処理される（もしくは reorder 条件も満たさなければ何も起きない）。
- 「深く重ねているつもりで長く保持してから離す」という操作は、境界付近での指の微動が起きやすい操作であり、この仮説と矛盾しない。

**仮説B（副次的、あり得る）: `isSlotAnimating()` が RecyclerView 全体のアニメーション状態を見ているため、直前の並び替えプレビューのアニメーションが終わるまで新しいゾーン判定が一切スキップされる**

- `isSlotAnimating()`（`SearchContainerView.kt`）は `recyclerView.itemAnimator?.isRunning == true` を見ており、これは RecyclerView 全体の対象で、ホバー中の特定 icon のアニメーション状態ではない。
- 深く重ねる直前まで REORDER ゾーンにいた場合、`previewReorder()` が呼ばれて DiffUtil の move アニメーションが走っている可能性が高く、その最中に指を FOLDER ゾーンまで一気に動かすと、`onDragOver()` はアニメーション終了までその回の判定を丸ごとスキップする（`isSlotAnimating(...)` が true を返し即 return）。
- その後、指がぴたりと止まった場合、次の `onDragOver` は（タッチが実際に動かない限り）呼ばれない可能性があり、アニメーションが終わったタイミングを逃すと FOLDER ゾーンへの遷移判定が一度も行われないまま release を迎える。

いずれの仮説も、`onDragOver` が「識別子が変わったときだけ」状態を更新するという設計（§10.19.2 R2 由来）と、境界判定にヒステリシスや猶予時間がないことに起因する。次回対応の方向性（未実装、判断待ち）:

- 境界にヒステリシス（FOLDER に入る閾値と抜ける閾値を分ける）を持たせる、または
- release 直前の一定時間（例: 最後の signature 変化から数十〜100ms程度）だけ直前の FOLDER 状態を保持する猶予（debounce）を入れる、または
- `isSlotAnimating()` の判定を RecyclerView 全体ではなく、実際にホバー中の特定 child だけを見るよう絞り込む。

#### 要望2: 並び替え時に「隣と入れ替わったことが分かる」アニメーションを追加してほしい（Home の編集画面の同処理を参考に）

Home の実際のアニメーション実装を確認した。

- Home 編集モード（Workspace の SPRING_LOADED / Folder 内の並び替え）の隣接アイテムシフトは `CellLayout.animateChildToPosition()`（`CellLayout.java:1085`）が担う。中身は **`ValueAnimator`（`duration = REORDER_ANIMATION_DURATION = 150ms`、`CellLayout.java:206`）で、対象 View の並進オフセット（`MultiTranslateDelegate` の `INDEX_REORDER_PREVIEW_OFFSET`）を旧位置→新位置へ線形補間するだけ**の単純なスライドアニメーションであり、拡大・縮小・色変化などの装飾は一切ない。
- Folder 内の実装（`FolderPagedView.realTimeReorder()`、`FolderPagedView.java:587`）はこれをさらに拡張し、影響を受ける複数アイテムを**段階的に遅延させながら連鎖的にスライドさせる**（`START_VIEW_REORDER_DELAY` による staggered delay）。1アイテムずつ「押し出される」ような見た目になる。
- 現状の App Drawer 側実装は、`AdapterItem.isSameAs()`/`isContentSame()` の識別ベース diff 修正（§10.22）により、並び替え時に RecyclerView の DiffUtil が実際の MOVE を配信するようになっている（rebind ではない）ため、**RecyclerView 標準の `ItemAnimator`（`DefaultItemAnimator`）による移動アニメーション自体は既に走っているはず**であり、Home の仕組み（並進スライド）と概念的には同種のもの。それでも「分かりやすさ」が不足していると感じられる場合、次のような点が原因になり得る（未検証、次回調査対象）:
  - `previewReorder()` は 650ms のスロットル付きで発火するが、ホバー中に何度も呼ばれるたびに DiffUtil 差分が再計算される。前回の move アニメーションが終わりきる前に次の move が入ると、RecyclerView 標準アニメーターが前のアニメーションを中断して新しい移動を割り込ませる可能性があり、Home の「1ステップずつ滑らかに送る」感触より「カクつく／飛ぶ」ように見える可能性がある。
  - 最終確定（`onDrop()` の `reorderApp()`）時の挿入位置が、最後にプレビューしていた位置と厳密に一致しない場合（release 座標での再計算 `acceptDrop()` が、直前の `onDragOver` の評価と微妙に異なる可能性がある）、確定時に想定より大きな一括シフトが発生し、「隣とだけ入れ替わった」という見た目にならない可能性がある。
  - Home の `FolderPagedView.realTimeReorder()` のような**段階的な delay 付き連鎖アニメーション**は、RecyclerView の `DefaultItemAnimator` の既定動作には存在しない（既定は全 move アイテムがほぼ同時にアニメーションする）。「分かりやすさ」を Home によりよく近づけるなら、RecyclerView 用に独自の `ItemAnimator`（またはカスタム move アニメーション）を用意し、150ms 程度の短い並進アニメーションや、複数アイテムが動く際の staggered delay を実装する必要がある可能性が高い。

次回対応の方向性（未実装、判断待ち）: まず RecyclerView 標準アニメーターの挙動を実機ログ/レイアウトインスペクタ等で確認し、「本当にアニメーションが走っていない」のか「走っているが分かりにくい」のかを切り分けてから、必要なら Home に寄せたカスタム `ItemAnimator`（またはアニメーション時間・イージングの調整）を検討する。

#### 要望3: フォルダ作成の重なり閾値をもう少し下げてほしい（より深い重なりでしか発火しないように）

現状の式（`SearchContainerView.classifyZone()`、§10.24/§10.25 で調整済み）:

```text
reorderRadius = min(cellW/2 + borderSpace.x/2, cellH/2 + borderSpace.y/2)
folderRadius  = (reorderRadius + iconVisibleRadius) / 2
```

`folderRadius` は `reorderRadius`（かなり広め、隣接セルとの隙間の半分まで食い込む）と `iconVisibleRadius`（アイコン画像自体の可視半径、狭い）の**単純平均**になっており、`reorderRadius`側に引っ張られて相対的に広めの値になっている。ユーザーの要望は、この `folderRadius` をもっと `iconVisibleRadius` 側に近い狭い値にして、「かなり深く重ねたときだけ」フォルダ判定に入るようにしたい、というもの。

次回対応の方向性（未実装、判断待ち）: `folderRadius` の式を平均ではなく `iconVisibleRadius` によりウエイトを置いた式に変更する案（例: `folderRadius = iconVisibleRadius * K`、`K` は 1.0 前後の調整可能な係数、または `(reorderRadius * w1 + iconVisibleRadius * w2) / (w1 + w2)` で `w2 > w1` とする加重平均）が考えられる。具体的な係数は実機で試しながら調整する必要がある。なお、報告1の「フォルダが一度も発火しない」バグを先に解消しないと、閾値を下げても検証できない点に注意（バグ修正 → 閾値調整の順で対応する）。

**次回セッションでの対応順（提案）**: 報告1（バグ調査・ヒステリシス/debounce導入）→ 要望3（閾値調整、報告1の修正と合わせて実機で追い込む）→ 要望2（アニメーションの分かりやすさ、優先度は前2つよりやや低い UX 磨き）。

→ 詳細設計を §10.30 で確定した（仮説の一部は棄却・具体化されている）。

### 10.30 §10.29 の3件の詳細設計（2026-07-07・コード未変更）

§10.29 の記録を起点にコードを再調査し、設計を確定した。調査の結果、**§10.29 の仮説を修正する決定的な事実が2つ**見つかった。

#### 10.30.1 追加調査で判明した事実（§10.29 の仮説の修正）

**事実1: All Apps の RecyclerView は ItemAnimator が無効（null）だった**

- `ActivityAllAppsContainerView.AdapterHolder.setup()` が `mRecyclerView.setItemAnimator(null)` を設定している（`allapps/ActivityAllAppsContainerView.java:1780-1781`、コメント: "No animations will occur when changes occur to the items in this RecyclerView."）。
- 帰結1（要望2の根本原因）: §10.21 の「識別ベース diff により RecyclerView 標準の move アニメーションが走るはず」という想定は**誤り**。DiffUtil は正しく move/remove/insert を配信しているが、animator が null のため**並び替えプレビューは常にテレポート（瞬間移動）**していた。「入れ替わったことが分かるアニメーションがない」のは当然で、調整や stagger 以前に**アニメーションが1本も存在しない**。
- 帰結2（§10.29 仮説Bの棄却）: `isSlotAnimating()` の `recyclerView.itemAnimator?.isRunning == true` は animator が null のため**常に false**。per-child の `translationX/Y` も animator がなければ常に 0。つまり仮説B（アニメーション中ガードによる判定スキップ）は**現状では成立し得ない**。ただし修正5で animator を有効化すると仮説Bが現実化するため、修正3（ガードの縮小）は animator 有効化と**必ずセット**で行う。

**事実2: FOLDER 進入時の `cancelPendingReorder()` は Home の挙動の誤読であり、テレポートと合わさって「指の下の地形が瞬時に書き換わる」フィードバックループを作っていた（報告1の本命）**

- §10.27 は FOLDER 進入時の `mainList.cancelPendingReorder()` を「Home の `setDragMode(DRAG_MODE_CREATE_FOLDER)` 時の `cleanupReorder(true)` と同じ意図」と説明したが、Home の `cleanupReorder` が巻き戻すのは **reorder alarm と hint（`revertTempState()`、小さな押しのけ予告アニメ）だけ**で、**alarm 発火済みの committed reorder（実際に動いた配置）は巻き戻さない**。一方 drawer 側の `pendingOrder` は「alarm 発火済みの committed reorder」に相当するので、これを巻き戻すのは Home より過剰。
- 具体的な故障シーケンス（§10.29 仮説Aの具体化）:
  1. アイコンに近づく過程で REORDER ゾーンを通過し、650ms 後に `previewReorder()` 発火 → グリッドが**テレポートで**並び替わる。
  2. さらに深く重ねて FOLDER ゾーンに入る → `cancelPendingReorder()` がプレビューを**テレポートで**巻き戻す → **指の真下にあるアイコンの位置・対応が瞬時に変わる**。
  3. 指のわずかな移動で次の `onDragOver` が走ると、巻き戻った地形で slot/zone を再解決するため signature が変わる → `onDragOver` は signature 変化のたびに `hoverFolderTargetApp = null` を**無条件実行**（`SearchContainerView.kt:354`）→ REORDER 判定なら 650ms 後にまたプレビュー → 2. へ戻る。
  4. この振動の中で release すると、最後の状態が FOLDER である確率は低く、`acceptDrop()` は `hoverFolderTargetApp = null` を読む → フォルダは作られない。
- つまり報告1は「指の微動」だけの問題ではなく、**FOLDER 進入自体が地形を書き換えて自分の状態を壊す**構造的なフィードバックループ。ヒステリシスだけ入れても FOLDER 進入時の巻き戻しがある限り再発する。
- なお slot 解決の geometry（`child.left/top` ベース）は RecyclerView の move アニメーション（translation 方式）では**最終レイアウト位置**を返すため、animator を有効化しても解決座標は安定している。Home の `getWorkspaceCellVisualCenter()`（セル座標ベース、`CellLayout.java:931-945`）と同じ性質が既に成り立っている。

#### 10.30.2 修正1（報告1・本命）: FOLDER 進入時にプレビューを巻き戻さない

- `onDragOver()` の `HoverZone.FOLDER` 分岐から `mainList.cancelPendingReorder()` を**削除**する。reorder alarm のキャンセル（既存）だけを行う。これが Home の `cleanupReorder(true)`（alarm + hint のみ）の正しい対応物。
- `pendingOrder` の巻き戻し/破棄タイミングは次の3つに限定: ①genuine exit（`onDragExit` の `dragComplete=false` 分岐、既存）②reorder 確定（`onDrop` → commit）③フォルダ作成確定（`createFolderAndAnimateDrop()` 内で `cancelPendingReorder()` を呼んで破棄。直後の `reloadGrid()` で再構築されるため視覚的な巻き戻しは実質見えない）。
- 効果: FOLDER 進入時に指の下の地形が変わらなくなり、フィードバックループの起点が消える。

#### 10.30.3 修正2（報告1）: FOLDER 状態の維持/解除をヒステリシス付きの明示的な遷移にする

`onDragOver()` の「signature 変化 → 全状態クリア → ゾーン別に再設定」（`SearchContainerView.kt:349-376`）を、Home 型の「毎回評価・明示的な enter/exit」へ再構成する:

```text
毎 onDragOver（signature ゲートの外で無条件に評価）:
  slot = resolveTargetSlot(...)（従来どおり）
  FOLDER 保持中（hoverFolderTargetApp != null）:
    exit 条件 = 「slot が hoverFolderTargetApp と別のアプリになった」
             or 「同一アプリだが distance > folderExitRadius」
    exit したら: scale 解除、hoverFolderTargetApp = null（REORDER 側の処理へ落ちる）
    exit しなければ: 何もせず return（FOLDER 維持。reorder alarm は張らない
                     = Home の mDragMode gating、Workspace.java:2840 相当）
  FOLDER 非保持:
    zone == FOLDER（enter 半径で判定）かつ target が自分でない
      → enter: scale 適用、hoverFolderTargetApp = slot.app、reorder alarm cancel
    zone == REORDER → 従来どおり signature（slot+zone）変化時のみ alarm 再アーム
    zone == NONE → 何もしない
```

- `folderEnterRadius` < `folderExitRadius`（後述 §10.30.5 の式）のヒステリシスにより、境界上の微動では FOLDER 状態が落ちない。release 直前の最後の評価が REORDER 側に振れても、exit 半径を超えない限り `hoverFolderTargetApp` は保持される。§10.29 で挙げた debounce（時間猶予）は、修正1+この構造変更で不要になる見込みのため入れない（実機で不足が確認されたときの第2弾とする）。
- `lastSlotSignature` は reorder alarm の再アーム判定専用に残す（FOLDER の enter/exit 判定には使わない）。

#### 10.30.4 修正3: `isSlotAnimating()` の縮小（修正5とセットで必須）

- whole-RV の `recyclerView.itemAnimator?.isRunning == true` チェックを**削除**する。現状は無意味（常に false）だが、修正5で animator を有効化した瞬間に「プレビューのたびに全判定が数百 ms 凍る」という §10.29 仮説Bが現実化してしまうため。
- per-child の `translationX/Y != 0`（対象 child が move アニメーション飛行中）チェックのみ残し、適用箇所を **FOLDER の enter 判定だけ**に限定する（Home の `useTmpCoords` ガードが `willCreateUserFolder()` にだけ効いているのと同じ、`Workspace.java:2172-2176`）。slot 解決・REORDER 判定はブロックしない（`child.left/top` は最終レイアウト位置なので飛行中でも安定、§10.30.1 事実2の末尾参照）。

#### 10.30.5 修正4（要望3）: フォルダ半径の式を「アイコン視覚半径基準」へ変更

現行の `folderRadius = (reorderRadius + iconVisibleRadius) / 2`（`classifyZone()`、`SearchContainerView.kt:533-535`）を次に置き換える:

```text
folderEnterRadius = min(iconVisibleRadius * FOLDER_ENTER_FACTOR, reorderRadius * 0.8f)
folderExitRadius  = folderEnterRadius * FOLDER_EXIT_HYSTERESIS
FOLDER_ENTER_FACTOR    = 1.0f   // 調整用定数。iconVisibleRadius ≈ 0.46 * iconSizePx
FOLDER_EXIT_HYSTERESIS = 1.3f   // 修正2のヒステリシス
```

- enter 半径が `iconVisibleRadius`（`ICON_VISIBLE_AREA_FACTOR(0.92) × iconSizePx / 2`）基準になるため、「DragView の視覚中心が相手のアイコン画像にほぼ載ったときだけ」FOLDER に入る。要望3の「かなり深い重なりでのみ発火」に一致。
- `reorderRadius * 0.8f` の clamp で、アイコンが極端に小さい設定でも REORDER 帯が必ず残る。
- 係数は実機で追い込む前提の定数とし、magic number をコード中に散らさない。

#### 10.30.6 修正5（要望2）: drag 中だけ ItemAnimator を有効化する

- **常時有効化はしない**: `setItemAnimator(null)`（`:1780-1781`）は通常運用（検索の打鍵ごとの結果更新、アプリ更新、work タブ切替）でのちらつき防止としてそこにあるため、これを常時変更すると App Drawer 全体の見た目に影響する。App Drawer 由来 drag の間だけ有効化し、終わったら戻す。
- 実装: `SearchContainerView` は既に `DragController.DragListener`（§10.20 R1）なので、`onDragStart()` で対象 RecyclerView に animator を設定し、`onDragEnd()` で `setItemAnimator(null)` へ戻す（R1 の attach listener と同じライフサイクル）。
- 第1弾（まずこれで実機確認）: `DefaultItemAnimator` に次を設定。
  - `moveDuration = 150ms`（Home の `CellLayout.REORDER_ANIMATION_DURATION`、`CellLayout.java:206` と同値）
  - `supportsChangeAnimations = false`（drag 中の change はほぼ発生しないが、crossfade による二重描画の芽を摘む）
  - add/remove は既定のまま（ドラッグ中アプリの remove+insert は INVISIBLE な View 同士なので見えない）
- 第2弾（第1弾で「分かりやすさ」が不足する場合のみ）: `FolderPagedView.realTimeReorder()` の staggered 演出（`FolderPagedView.java:72-74`: duration 230ms、`START_VIEW_REORDER_DELAY = 30ms`、`VIEW_REORDER_DELAY_FACTOR = 0.9`）を参考に、`DefaultItemAnimator` を継承したカスタム animator で move の開始 delay を「空きスロットからの index 距離」に比例させる。
- **WYSIWYG 確定（§10.29 要望2の「確定時の一括シフト」対策）**: preview が発火済み（`pendingOrder != null`）の drop では、release 座標から `resolvedInsertIndex` を再計算して `reorderApp()` する現行経路をやめ、**最後にプレビューされていた `pendingOrder` をそのまま確定する** `commitPendingOrder()` を `LawnchairAlphabeticalAppsList` に追加して使う（Home が「drop は既に動いた配置を確定するだけ」なのと同じ）。preview 未発火の素早い drop だけ従来の `resolvedInsertIndex` 経路を使う。確定時に予期しない一括シフトが起きなくなり、着地アニメーションのターゲット位置も安定する。

#### 10.30.7 実装順・受け入れ条件

実装順（各ステップでビルド+実機確認）:

1. **修正1+2+3**（報告1、1タスク）: FOLDER 進入でプレビューを巻き戻さない + enter/exit 状態機械 + ガード縮小。
2. **修正4**（要望3、小差分）: 半径式の置換。1. と同時にビルドしてよいが、確認は分けて行う。
3. **修正5**（要望2、1タスク）: drag 中 animator + WYSIWYG 確定。第2弾（stagger）は実機を見てから判断。

受け入れ条件:

- 報告1: アイコンに深く重ねて保持 → 拡大表示が出て**安定して維持され**、release で2アプリ入りフォルダが作られる。境界付近で指を静止していてもフォルダ状態が明滅しない。浅い重なりからの release は従来どおり並び替え。
- 要望3: フォルダ判定に入るのは視覚中心が相手アイコン画像にほぼ載ったときだけ。REORDER 帯が全アイコンサイズ設定で残っている。
- 要望2: プレビューで隣接アイコンが**滑って**避ける（テレポートしない）。drop 確定時に最後のプレビューと違う配置に飛ばない。drag 終了後、検索・アプリ更新などの通常更新の見た目が従来どおり（animator が null に戻っている）。
- 全体: ゴースト DragView なし、Home 由来 drag・バー・検索・fast scroll・Work タブの回帰なし。

### 10.31 修正1+2+3+4 実装記録（2026-07-07）

§10.30 の設計どおり、報告1（フォルダ未発火）と要望3（閾値を下げる）をまとめて実装した。修正5（要望2: アニメーション + WYSIWYG 確定）は別タスクとして次に着手する。

変更ファイル: `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt`（このみ）

| 修正 | 内容 |
|---|---|
| 修正1 | `onDragOver()` の `HoverZone.FOLDER` 分岐から `mainList.cancelPendingReorder()` を削除。フォルダ作成が確定した場合のみ `createFolderAndAnimateDrop()` の冒頭で `cancelPendingReorder()` を呼び、既存プレビューを破棄する（直後の `reloadGrid()` で再構築されるため視覚的な巻き戻しは見えない） |
| 修正2 | `onDragOver()` を「signature 変化で全状態クリア」から「FOLDER 保持状態を毎回無条件評価 → 保持中なら return、非保持なら signature ベースで zone 別処理」という構造に再構成。`hoverFolderTargetApp` が非 null の間は、同じアプリかつ `isWithinFolderExitRadius()`（enter 半径 × `FOLDER_EXIT_HYSTERESIS`(1.3)）以内であれば何もせず維持し、それ以外（別アプリ、または exit 半径を超えた）で初めて解除する |
| 修正3 | `isSlotAnimating()` から `recyclerView.itemAnimator?.isRunning` チェックを削除（§10.30.1 事実1より常に false だったため無意味、かつ修正5で animator を有効化すると全判定が凍る危険があった）。残った per-child `translationX/Y` チェックは FOLDER **進入時のみ**のガードとして `onDragOver()` 内で個別に適用する形に変更（呼び出し箇所を `isSlotAnimating(recyclerView, slot)` → `isSlotAnimating(slot)` へ、REORDER 側の判定はブロックしない） |
| 修正4 | `classifyZone()`/新設の `computeZoneRadii()` を、`folderRadius = (reorderRadius + iconVisibleRadius) / 2` から `folderEnterRadius = min(iconVisibleRadius * FOLDER_ENTER_FACTOR(1.0), reorderRadius * FOLDER_ENTER_CLAMP_FACTOR(0.8))` へ置換。`folderExitRadius = folderEnterRadius * FOLDER_EXIT_HYSTERESIS(1.3)` を追加し、`isWithinFolderExitRadius()` として公開 |

実装方針の補足:

- `ZoneRadii`（`folderEnterRadius`/`folderExitRadius`/`reorderRadius` を持つ private class）を新設し、`classifyZone()`（enter 半径を使う）と `isWithinFolderExitRadius()`（exit 半径を使う）の両方が `computeZoneRadii()` を共有する形に整理した。
- `onDragOver()` の制御フロー: ①`resolveTargetSlot` で slot 解決 → ②**FOLDER 保持中なら毎回無条件で exit 判定**（signature を経由しない）→ ③保持継続なら即 return（reorder alarm 等の状態は一切触らない）→ ④保持解除 or 非保持なら self 判定 → ⑤FOLDER **進入**のみ `isSlotAnimating()` でガード → ⑥signature（slot+zone）が変化していれば zone 別処理（FOLDER: scale 適用+`hoverFolderTargetApp`セット、REORDER: alarm 再アーム、NONE: 何もしない）。
- これにより、FOLDER に一度入ってから境界付近で指が微動しても、地形（pendingOrder）が書き換わることも `hoverFolderTargetApp` が無条件でクリアされることもなくなり、§10.30.1 事実2で特定したフィードバックループの起点が構造的になくなった。

検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果: `BUILD SUCCESSFUL in 1m 9s`。新規/変更ファイル起因のエラーなし。

未実施: 実機確認（§10.30.7 の受け入れ条件のうち報告1・要望3: 深く重ねて保持すると拡大表示が明滅せず安定して維持されるか、release で確実にフォルダが作られるか、フォルダ判定に入るのがアイコン画像にほぼ載ったときだけになっているか、浅い重なりは従来どおり並び替えになるか、ゴースト・回帰なし）。要望2（修正5）はこの節では未着手。

### 10.32 実機確認結果（2026-07-08）+ 作業ツリー巻き戻し事故と復旧・再構築計画

#### 10.32.1 §10.31 ビルドの実機確認結果（2026-07-08）

§10.31（修正1+2+3+4）を適用したデバイスビルド（`297de14` ベース + 未コミット変更）で確認した結果:

| 項目 | 結果 | 解釈 |
|---|---|---|
| 報告1（明滅） | **明滅は解消** | 修正1+2（フィードバックループ除去・ヒステリシス）が有効 |
| 要望3（閾値） | **OK** | 修正4（`iconVisibleRadius` 基準の enter 半径）が有効 |
| 要望2（アニメ） | **テレポートのまま** | 修正5 は §10.31 に未着手なので当然 |
| 報告1（作成） | **その場で作られず、再起動後に作られる** | 新しい真因。§10.30 では未想定 |

→ 修正1〜4 の方向性は実機で正しさが裏付けられた。残るのは要望2（修正5）と、**新たに判明した「フォルダが再起動後にしか作られない」= drawer 再描画の問題**（§10.32.3）。

#### 10.32.2 作業ツリー巻き戻し事故と復旧（2026-07-08）

**事象**: 作業ディレクトリが `namamiso/Morrowa.git` から**クローンし直された直後の状態**になっていた（`git reflog` はクローン1件のみ）。ローカル `16-dev` は `origin/16-dev` より **14コミット手前の `fabeccfb84`** を指しており、App Drawer 実装コードが一切入っていない状態だった。dangling commit は1件あったが無関係（別作者の古い PagedView 修正）。

**判明した損失範囲**: 前セッション開始時点の HEAD は `297de14`（Phase D 並び替え完了）で、その上に**未コミットの変更**として以下が乗っていた。これらは push されておらず、巻き戻しで失われた（doc の設計記録のみ残存）:

- §10.24 / §10.25: reorder 閾値・感度修正（`SearchContainerView`）
- **§10.26 R3: フォルダ作成データ層**（`FolderViewModel.createFolderWithApps` / `FolderService.createFolderWithItems` / `FolderDao.insertFolderReturningId` / `LawnchairAlphabeticalAppsList.createFolder`）
- **§10.27 R4: フォルダモード接続**（`hoverFolderTargetApp` / `createFolderAndAnimateDrop` / `applyFolderHoverScale` / `onDrop` フォルダ分岐 / `resolvedCreateFolder` の使用側）
- §10.30 / §10.31: 修正1〜4

**origin/16-dev @ 297de14 に生きているもの（復元済み）**:

- Phase D 並び替え + 永続化（`DrawerAppOrderEntity` / `DrawerAppOrderDao` / `LawnchairAlphabeticalAppsList` の `drawerAppOrder` 観測・`reorderApp`・`previewReorder`・`beginPendingReorder`・`cancelPendingReorder`・`commit` 前提の `getOrderedApps`）
- R1+R2: 不可視ソース + ライブプレビュー + ゾーン判定（`SearchContainerView` の `HoverZone` / `classifyZone` / `reorderAlarm`）。ただし `HoverZone.FOLDER` 分岐は「R4 not yet implemented」のスタブ（`SearchContainerView.kt:270` 付近）で、フォルダは作らない。

**復旧手順（実施済み）**:

1. untracked の設計 doc（§10.30/§10.31 を含む 194KB）をスクラッチパッドへ退避。
2. `git reset --hard origin/16-dev`（ローカルは origin に対し「14 behind・独自コミットなし・追跡ファイルの変更なし」だったため、履歴の損失なし）。
3. reset が origin committed 版（158KB・§10.25 まで）で doc を上書きしたため、退避版（194KB・§10.31 まで、origin 版の完全上位互換であることを `comm` で確認済み）で復元。

**今後の防止策（合意事項）**: フォルダ作業は R3/R4 を分割して**都度コミット**し、実機確認前に必ず push する。未コミットのまま実機ビルド・検証を繰り返さない。

#### 10.32.3 新真因: フォルダが再起動後にしか作られない（drawer 再描画の race）

**再描画経路（現行コードで確認）**:

- `LawnchairAlphabeticalAppsList.observeFolders()`（`:224-229`）が `FolderViewModel.foldersLiveData` を観測し、`folderList` 更新 + `updateAdapterItems()` を呼ぶ**ライブ再構築**がある。
- 手動フォルダモードの構築 `addAppsWithSections()`（`:274-292`）は、各フォルダについて `folder.getContents()` を `appsStore.getApp(componentKey)` で解決し、**`folderApps.size > 1` の時だけ `AdapterItem.asFolder(...)` を追加**する（`:279`）。

**真因**: フォルダ作成直後に `foldersLiveData` が「**contents がまだ 2 未満のフォルダ**」を emit すると、`:279` の `size > 1` ガードで**除外され表示されない**。DB には書けているので、再起動で contents 付きに再読込されると初めて表示される。失われた R3 の `createFolderWithApps` が「フォルダ本体と items を atomic に書いてから emit / reloadGrid する」順序を守っていなかったための race と判断する（デバイス挙動と `:279` ガードで説明が付く）。

**修正設計（R3 再実装時に作り込む）**: 既存で drawer 更新の実績がある `FolderViewModel.updateFolderItems()`（`:60-69`、`repository.updateFolderWithItems(...)` → `reloadGrid()` の順）を手本にする。

- `FolderService.createFolderWithItems(title, apps)` を**単一 suspend で atomic に**書く: ①`FolderInfoEntity` を insert して id 取得（`insertFolderReturningId`）→ ②その id で全 `FolderItemEntity`（rank 付き）を insert → ③（`prefs.folderApps` が true のとき）メイン一覧から隠す状態の更新。①②が完了してから関数を return する。
- `FolderViewModel.createFolderWithApps(title, apps)` は 1 つの `viewModelScope.launch { ... }` 内で `repository.createFolderWithItems(...)` を **await してから** `reloadHelper.reloadGrid()` を呼ぶ（`createFolder`/`updateFolderItems` と同じ順序）。
- これにより `getFoldersFlow()` の emit は「contents が 2 揃ったフォルダ」になり、`observeFolders → updateAdapterItems → addAppsWithSections` の `:279` ガードを通過して**その場で表示**される。`reloadGrid()` はモデル全体の再読込としての保険（既存パターン踏襲）。
- 受け入れ条件: フォルダ作成 release の**直後**（再起動なし）に、2 アプリ入りフォルダが drawer に出現する。フォルダに入れた 2 アプリがメイン一覧から消える（`pref_hideFolderApps` 既定 true 準拠）。

#### 10.32.4 再構築タスクの分解と順序

origin/16-dev @ 297de14（Phase D 並び替えは無傷）を土台に、**設計は §10.24〜§10.31 + §10.32.3 に全て残っている**ので、以下は「新規設計」ではなく「記録済み設計の再実装 + 新 reload 修正 + 修正5」である。都度コミット・push する。

1. **タスクA（感度）**: §10.24 reorder 閾値修正 + §10.25 距離基準点修正（`SearchContainerView` の `classifyZone`/距離計算）。小差分。→ commit。
2. **タスクB（R3 フォルダ層 + reload 修正）**: §10.26 の R3 データ層を再実装し、その際 §10.32.3 の atomic + reloadGrid 順序を**必ず**守る。`FolderDao.insertFolderReturningId` / `FolderService.createFolderWithItems` / `FolderViewModel.createFolderWithApps` / `LawnchairAlphabeticalAppsList.createFolder`。UI 未接続でも、単体でフォルダが作られ drawer に出ることをテスト用導線で確認できると理想。→ commit。
3. **タスクC（R4 接続 + 修正1〜4）**: §10.27 R4（`hoverFolderTargetApp`・`createFolderAndAnimateDrop`・`applyFolderHoverScale`・`onDrop` フォルダ分岐）を、**最初から §10.30/§10.31 の修正1〜4 を織り込んだ形**で実装する（スタブ経由の巻き戻しを再現しない）。`onDragOver` は §10.30.3 の enter/exit 状態機械、半径は §10.30.5 の式。→ commit → 実機確認（報告1・要望3）。
4. **タスクD（修正5）**: §10.30.6 の drag 中 animator + WYSIWYG 確定（`commitPendingOrder`）。→ commit → 実機確認（要望2）。

各タスクの受け入れ条件は §10.30.7 + §10.32.3 を参照。タスクB の reload 修正が入るまでは、報告1の「その場で作られない」は解消しない点に注意（タスクC の修正1〜4 だけでは判定は直っても表示が追いつかない）。

### 10.33 再構築の実装記録（2026-07-08）

§10.32.4 のタスク分解に沿って、失われたフォルダ機能一式を origin/16-dev @ 297de14 の上に再実装した。

**タスクA は不要だった**: 復元後に `SearchContainerView` を確認したところ、§10.24（`reorderRadius = min(cellW/2 + borderSpace.x/2, cellH/2 + borderSpace.y/2)`）と §10.25（`iconVisualCenter()`）は **297de14 に既にコミット済み**で失われていなかった。感度修正は再実装不要。

変更ファイル:

| ファイル | 内容（タスク） |
|---|---|
| `data/folder/service/FolderDao.kt` | （B）`insertFolderReturningId(folder): Long` と `createFolderWithItems(folder, items: (Int) -> List<FolderItemEntity>): Int`（`@Transaction`。`folder.copy(id = 0)` で新規 insert → 生成 id で items 生成 → insert → id を返す）を追加 |
| `data/folder/service/FolderService.kt` | （B）`createFolderWithItems(title, appInfos): Int`（`FolderInfoEntity(title=title)` + `appInfos` を rank=index で item 化して DAO 呼び出し） |
| `data/folder/model/FolderViewModel.kt` | （B）`createFolderWithApps(title, appInfos)`（`createFolder`/`updateFolderItems` と同じ「repository 書き込みを await → `reloadHelper.reloadGrid()`」パターン。§10.32.3 の reload 修正の核心） |
| `allapps/LawnchairAlphabeticalAppsList.kt` | （C）`createFolder(title, apps)` を `viewModel.createFolderWithApps` へ委譲。（D）`hasPendingReorder(): Boolean` と `commitPendingOrder()`（pendingOrder を再計算せずそのまま永続化＝WYSIWYG）を追加 |
| `allapps/views/SearchContainerView.kt` | （C+D）大幅書き換え。下記 |

`SearchContainerView` の再構成（§10.30 の設計を最初から織り込み、スタブ経由の巻き戻しを再現しない形で実装）:

- **状態**: `resolvedCreateFolder`（旧）を廃し `hoverFolderTargetApp: AppInfo?`（フォルダ確定状態、`acceptDrop`/`onDrop` が読む）+ `scaledFolderTargetView: View?` を導入。`lastTargetSlotKey`（スロットのみ）→ `lastSlotSignature`（`"$slotKey:$zone"`、reorder alarm 再アーム専用）へ。
- **onDragOver（修正1+2+3）**: ①`beginPendingReorder` → ②slot 解決 → ③**FOLDER 保持中なら signature ゲートの外で毎フレーム評価**（同一アプリ かつ `isWithinFolderExitRadius` 内なら即 return で維持、`cancelPendingReorder` は呼ばない＝修正1）→ ④exit 時は `clearFolderHover()` + `lastSlotSignature=null` で reorder 側へ落とす → ⑤self スロットは何もしない → ⑥zone 別: FOLDER は `isSlotAnimating(slot)`（per-child のみ、修正3）でガードしてから scale 適用 + `hoverFolderTargetApp` セット、REORDER は signature 変化時のみ alarm 再アーム、NONE は signature 更新のみ。
- **半径（修正4）**: `computeZoneRadii()` を新設し `classifyZone`（enter）と `isWithinFolderExitRadius`（exit）が共有。`folderEnterRadius = min(iconVisibleRadius * 1.0, reorderRadius * 0.8)`、`folderExitRadius = folderEnterRadius * 1.3`。
- **isSlotAnimating（修正3）**: whole-RV の `itemAnimator?.isRunning` を削除、per-child `translationX/Y` のみ。FOLDER enter 判定だけに適用。
- **drag 中 animator（修正5）**: `onDragStart` で対象 RecyclerView に `DefaultItemAnimator(moveDuration=150, supportsChangeAnimations=false)` を設定、`onDragEnd` で `itemAnimator=null` に戻す（通常運用の無アニメを維持）。
- **WYSIWYG 確定（修正5）**: `onDrop` の reorder 分岐で、`hasPendingReorder()` が true なら `commitPendingOrder()`（プレビューをそのまま確定）、false（プレビュー未発火の素早い drop）なら従来の `reorderApp(insertIndex)`。
- **フォルダ作成 drop（R4）**: `hoverFolderTargetApp` が非 null なら `createFolderAndAnimateDrop()` → `cancelPendingReorder()`（修正1、reloadGrid で再構築されるため巻き戻しは不可視）→ `clearFolderHover()` → `mainList.createFolder(my_folder_label, [targetApp, movedApp])` → `animateDragViewOnto()`。着地アニメは `animateDropLanding`/フォルダ作成で `animateDragViewOnto(dragView, targetView)` を共有（§10.27）。target 解決は index ではなく**ライブ children 走査 `findChildViewForApp()`**（フォルダ行が adapter position をずらしても正しい）。
- **reload 修正（§10.32.3）**: フォルダ作成は R3 の atomic write + `reloadGrid` 経路を通るため、`getFoldersFlow` が contents 2 揃いで emit → `observeFolders → updateAdapterItems → addAppsWithSections`（`:279` の `size > 1` ガード通過）で**その場で表示**される。

検証: `compileLawnWithQuickstepGithubDebugJavaWithJavac` → `BUILD SUCCESSFUL in 4m 19s`（Room の DAO 生成含め、新規/変更起因のエラーなし。警告は無関係な quickstep の deprecation のみ）。`installLawnWithQuickstepGithubDebug` で実機 `SC-52C - 16` へインストール。

未実施: 実機確認（§10.30.7 + §10.32.3 の受け入れ条件）。特に **報告1（フォルダが release 直後に作られる・再起動不要）**、要望2（プレビューが滑る・WYSIWYG）、要望3（深い重なりのみフォルダ）。都度コミットは未実施（実機確認 OK 後にまとめて行う想定）。

### 10.34 §10.33 ビルドの実機確認結果と残 2 件の詳細調査（2026-07-08・別エージェント引き継ぎ用）

#### 10.34.1 実機確認結果

| 項目 | 結果 |
|---|---|
| 要望3（深い重なりのみフォルダ判定） | **OK** |
| 回帰（フォルダ境界での明滅） | **OK**（明滅なし） |
| 報告1（フォルダが release 直後に作られる） | **未解決**。まだその場で作られない |
| 要望2（並び替えアニメ） | 左右で挙動が違う。**右→左の動きが正常値**、左→右が異なる（おかしい） |

以下、残 2 件を別エージェントが調査・修正できるよう、コードを読んで特定した内容を残す。**要望2は根本原因を確定、報告1は計測が要るため仮説と計測ポイントを提示する。**

#### 10.34.2 要望2（並び替えアニメの左右非対称）: 根本原因【確定】

**原因**: `AlphabeticalAppsList.updateAdapterItems()` の差分適用が `DiffUtil.calculateDiff(new MyDiffCallback(oldItems, mAdapterItems), false)`（`src/com/android/launcher3/allapps/AlphabeticalAppsList.java:376`）で、**第2引数 `detectMoves = false`**。

- `detectMoves = false` だと DiffUtil は移動（move）を検出せず、位置が変わったアイテムを **remove（旧位置）＋ insert（新位置）** として報告する。`DefaultItemAnimator` は move を slide（並進）で、remove/insert を fade（＋わずかな並進）でアニメーションするため、並び替えが「滑る」ようには見えない。
- さらに remove/insert 対象は DiffUtil の LCS（最長共通部分列）で決まり、**LCS は移動方向で非対称**。moved app を前方（左→右）へ動かす場合と後方（右→左）へ動かす場合で「keep されるアイテム」と「remove+insert されるアイテム」の集合が変わるため、**方向によって見た目のアニメーションが変わる**。ユーザーの「右→左は正常、左→右は違う」はこの非対称性で説明できる。
- **重要な訂正**: `BaseAllAppsAdapter.java:158-159`（§10.22 で追記した `isSameAs` のコメント）の「detectMoves=false でも DiffUtil が reordering を real move として報告できる」という記述は**前提が誤り**。identity ベースの `isSameAs`（`BaseAllAppsAdapter.java:162-175`）は「無関係な位置が別アプリへ rebind されるのを防ぐ」効果はあるが、moved item 自体は detectMoves=false である限り remove+insert のままで、move にはならない。§10.22 は二重描画（rebind flicker）は解消したが、アニメーションが slide にならない/非対称である問題は残っていた（今回顕在化）。

**修正の方向（別エージェント向け）**:

- 第一候補: `calculateDiff(cb, true)` に変更して move を検出させる。`DefaultItemAnimator` が move を対称な slide でアニメーションするようになり、左右差も解消する。
- ただし `updateAdapterItems()` は launcher3 共有コードで、**検索結果更新・アプリ更新・Work タブ等すべての All Apps 更新**に効く。detectMoves=true は追加コスト（おおむね O(N²) の move 検出パス）があるが、drawer のアイテム数程度なら実用上問題は小さい見込み。影響を最小化するなら「**ドラッグ中だけ detectMoves=true**」にする（例: `LawnchairAlphabeticalAppsList` に drag 中フラグを持たせ、`updateAdapterItems` をオーバーライドするか、`calculateDiff` の第2引数をフラグで切り替える薄いフック）。§10.30.6 で drag 中だけ `DefaultItemAnimator` を有効化しているのと同じスコープに揃えるのが自然。
- 併せて、§10.30.6 の staggered 演出（第2弾）はこの detectMoves 修正で slide が対称に出るのを確認してから判断する。

#### 10.34.3 報告1（フォルダが release 直後に表示されない）: 調査と仮説

**まず切り分け（別エージェントが最初に確定すべきこと）**: 今回のビルドで「フォルダは**アプリ再起動すると**現れるのか、それとも**一切現れない**のか」。
- 再起動で現れる → DB 書き込みは成功。壊れているのは**その場の表示（再描画）経路**。→ 仮説 D1〜D3。
- 一切現れない → `createFolderWithApps` まで到達していない、または DB 書き込み自体が失敗。→ 仮説 D4。

**§10.33 の私の reload 修正（atomic write＋reloadGrid）が効かなかった意味**: 「書き込み順序／atomicity」が真因ではなかった。表示経路側に別の原因がある。以下、優先度順。

**仮説 D1（最有力）: `foldersLiveData` observer は発火しているが、`addAppsWithSections` で新フォルダの `folderApps.size > 1` が成立していない**

- 再描画は `LawnchairAlphabeticalAppsList.observeFolders()`（`:224-229`）→ `updateAdapterItems()` → `addAppsWithSections()`（`:274-292`）の経路で起きる。手動フォルダモードでは各フォルダの中身を `appsStore.getApp(app.componentKey)`（`:277`）で解決し、`folderApps.size > 1`（`:279`）**のときだけ**フォルダ行を追加する。
- 新規作成直後、`folder.getContents()` が返す 2 アプリのうち片方でも `appsStore.getApp(componentKey)` が null を返すと size < 2 となり**フォルダが描画されない**。再起動すると（別経路で）解決でき表示される、という筋。
- 計測: `addAppsWithSections` のフォルダ分岐に、各 folder について `folder.id` / `folder.getContents().size` / `appsStore.getApp` の解決成否（componentKey 文字列付き）をログ出力。ドラッグでフォルダ作成 → logcat 確認。
- 疑うべき詳細: `FolderService.toItemInfo(componentKey)`（`service/FolderService.kt:94-104`）は `launcherApps.getActivityList` を毎回スキャンして `converters.fromComponentKey(it.componentKey) == componentKey` で突き合わせる。ここで `FolderItemEntity.componentKey`（`AppInfo.toEntity` が保存した文字列, `data/Converters.kt`）と、`appsStore.getApp` が期待する `ComponentKey` の**文字列表現の不一致**（user シリアライズ形式など）があると解決に失敗し得る。作成直後と再起動後で差が出るかも要確認。

**仮説 D2: observer が発火していない（Room flow が in-place で再 emit しない、または LiveData 購読が非アクティブ）**

- `FolderService.getFoldersFlow()`（`:37-43`）は `folderDao.getAllFolders()`（Room Flow）を `.map { getFolderInfo(id, true) }` したもの。`FolderViewModel.folders`（`:27-37`）は `.distinctUntilChanged().stateIn(WhileSubscribed(5000))`、`foldersLiveData = folders.asLiveData(viewModelScope)`。
- `createFolderWithItems` は Folders テーブルに insert するので Room の InvalidationTracker が `getAllFolders()` を再 emit するはずだが、**別コネクション/WAL チェックポイント**（DAO に `checkpoint` RawQuery がある = `service/FolderDao.kt:61`）が絡むと in-place で観測されない可能性がある。
- 計測: `observeFolders` のラムダ（`:225`）冒頭に「発火した」「folders.size」ログを入れる。作成直後に発火するか、発火時の size は増えているかを確認。発火しないなら Room flow/購読側、発火するが表示されないなら D1。
- 補足: `FolderViewModel` は `ViewModelProvider` 経由でなく手動生成（`LawnchairAlphabeticalAppsList.kt:50`）なので `onCleared()` が呼ばれず `viewModelScope` は生存し続ける。購読自体は活きているはず。ただし drag を処理する `mainList` インスタンスの `viewModel` と、実際に画面に出ているリストの `viewModel` が**同一インスタンスか**は要確認（別インスタンスなら別 observer で、作成した側の再描画が画面へ反映されない）。

**仮説 D3: `reloadGrid()`（`idp.onPreferencesChanged`）が All Apps を再構築せず、observer 経路だけが頼りだが、その observer 経路が drop 直後のタイミングで抑制される**

- 既存フォルダ**編集**（`updateFolderItems`）は「既に size>1 で描画済みのフォルダ」の中身更新なので D1 の罠にかからず、in-place 更新が効いている（＝ユーザー確認済み）。一方**新規作成**は「今まで単独だった 2 アプリを初めてフォルダ化」するため D1/D2 の初回描画に固有の問題が出やすい。この非対称が「編集は反映されるが作成は反映されない」症状と整合。
- 計測: 作成直後に手動で `updateAdapterItems()` 相当を強制（例: タブ切替やスクロール）して即表示されるか。されるなら「observer 発火のトリガー欠落」、されないなら D1（データ解決）寄り。

**仮説 D4（「一切現れない」場合）: `onDrop` のフォルダ分岐に入っていない or `createFolder` 未到達**

- `SearchContainerView.onDrop`（今回実装）はフォルダ分岐条件が `hoverFolderTargetApp != null && … && folderApp.key != movedApp.key`。要望3で「深い重なりでフォルダ判定に入る」ことは実機 OK なので `hoverFolderTargetApp` は drop 前に立っているはずだが、`onDragExit(dragComplete=true)` → `acceptDrop` → `onDrop` の間で消えていないか要確認（今回 `onDragExit` は dragComplete 時にクリアしない実装）。
- 計測: `onDrop` 冒頭で `hoverFolderTargetApp` の有無、`createFolderAndAnimateDrop` 到達、`FolderViewModel.createFolderWithApps` 到達、`FolderService.createFolderWithItems` の戻り id、をログ。

**別エージェントへの推奨手順**:
1. まず「再起動で出るか／一切出ないか」を確定（D1〜D3 か D4 か）。
2. `createFolderWithApps` 到達と戻り id、`observeFolders` 発火と size、`addAppsWithSections` の folder ごとの `getContents().size` と `appsStore.getApp` 解決成否をログで一気に可視化。
3. 切れているリンクを特定してから修正。有力は D1（componentKey 解決 or size>1 ガード）。
4. 併せて要望2（§10.34.2）の detectMoves 修正（ドラッグ中スコープ推奨）も入れる。

**現状のコード位置（参照用）**: `data/folder/service/FolderDao.kt`（`insertFolderReturningId`/`createFolderWithItems`）、`data/folder/service/FolderService.kt`（`createFolderWithItems`/`toItemInfo`/`getFoldersFlow`）、`data/folder/model/FolderViewModel.kt`（`createFolderWithApps`）、`allapps/LawnchairAlphabeticalAppsList.kt`（`createFolder`/`observeFolders`/`addAppsWithSections`）、`allapps/views/SearchContainerView.kt`（`onDrop`/`createFolderAndAnimateDrop`）、`src/com/android/launcher3/allapps/AlphabeticalAppsList.java:376`（detectMoves）。いずれも §10.33 実装後の状態（未コミット・実機 `SC-52C - 16` にインストール済み）。

### 10.35 原因究明（実機 DB・logcat による確定）と修正実装計画（2026-07-08・コード未変更）

§10.34 の引き継ぎに基づき、**接続中の実機からコード変更なしで証拠を取得**し、仮説を大幅に絞り込んだ。

#### 10.35.1 取得した確定事実

計測手法: `adb exec-out run-as app.lawnchair.debug cat databases/preferences`（+ `-wal`/`-shm`）で Room DB を取得し、platform-tools 同梱の `sqlite3` で照会。あわせて `adb logcat -d` の履歴と `ps` のプロセス起動時刻を解析。

**事実1: DB 書き込み経路は完全に動作している → 仮説D4 は棄却**

`Folders` テーブルに id=5（2026-07-08 11:49:52）と id=6（11:49:54）が存在し、それぞれ `FolderItems` に 2 行（rank 0/1、Samsung カレンダー + Jumptoon、`pkg/cls#0` 形式）が紐付いている。**同一内容のフォルダが2秒差で2つ** = 「1回目の drop で表示されず、もう一度 drop した」というユーザー操作の痕跡とも整合する。`onDrop` → `createFolderWithApps` → atomic write は end-to-end で成功している。

**事実2: クラッシュは起きていない。再起動はユーザーの手動操作**

logcat 履歴より: 旧プロセス pid 32426 は 11:49:53 に大量 read（reloadGrid の model reload とみられる）→ 11:49:58 ユーザーが Recents へ遷移 → 11:49:59 `ActivityManager: Killing 32426:app.lawnchair.debug (adj 905): remove task`（**Recents からのタスク削除 = 手動再起動**）→ 11:50:04 新プロセス pid 716 起動。`FATAL`/`AndroidRuntime` なし。

**事実3: 初回ロード経路（購読開始時の初回 emission → 表示）は正常**

現プロセス（716）はフォルダ 3〜6 がすべて DB に存在する状態で起動しており、ユーザー報告（再起動後は表示される）と合わせると、`observeFolders → updateAdapterItems → addAppsWithSections` の**表示経路そのものは初回 emission に対しては機能する**。

**事実4: componentKey の文字列形式不一致（D1 の一変種）は棄却**

書き込み側 `AppInfo.toEntity`（`Converters.fromComponentKey` = `ComponentKey.toString()`、`data/Converters.kt:17-22`）と照合側 `toItemInfo` の比較（`FolderService.kt:116`）は**同一関数・同一形式**で、DB 実物も `pkg/cls#0` 形式だった。形式起因の恒常的な解決失敗はない（実際、再起動後は同じデータで表示できている）。

**事実5: `reloadGrid()` はプロセスも activity も殺さず、全再構築を1回は走らせている**

`reloadGrid()` = `idp.onPreferencesChanged()` → `MAIN_EXECUTOR` 上で `onConfigChanged()`（`InvariantDeviceProfile.java:593-596`）→ IDP 再init + listener 通知（`LawnchairAlphabeticalAppsList.onIdpChanged` → `onAppsUpdated()`、`.kt:336-338`）+ model reload。事実2のタイムラインとも整合。**作成後に少なくとも1回は全再構築が走っているのに表示されない**ということは、**そのセッション中、`folderList` に「描画可能な（contents≥2 の）新フォルダ」が一度も入っていない**ことを意味する。

**結論**: 残る候補は2つだけ。
- **D2（本命寄り）**: ライブ emission（`getAllFolders()` flow → `foldersLiveData` → `observeFolders`）が作成直後に届いていない。
- **D1'**: ライブ emission は届いているが、emit 時の `toItemInfo` 解決がそのタイミングに限って失敗し contents < 2 で `:318` ガードに弾かれている。

どちらが真因かは実機のランタイムログでしか確定できない（今回は端末が Doze/ロック中のため再現操作は次回）。ただし**以下の修正F-Aはどちらが真因でも症状を解消する**。

#### 10.35.2 修正実装計画

**修正F-A（即効・真因非依存）: フォルダ作成時の楽観的ローカル反映**

- `LawnchairAlphabeticalAppsList.createFolder(title, apps)` を次のように変更: `viewModel.createFolderWithApps(...)`（既存、裏で DB 書き込み + reloadGrid）に加えて、**手元にある 2 つの `AppInfo` から `FolderInfo` を直接合成して `folderList` に add し、その場で `updateAdapterItems()` を呼ぶ**。
- drop した瞬間に drawer にフォルダが出る。Room flow の emission・`toItemInfo` の解決・reloadGrid のどれにも依存しない。Home の `createUserFolderIfNecessary()` が drop と同時に `FolderIcon` を即時生成し、永続化を裏で行うのと同じ「UI 先行」パターン。
- 後続の canonical emission（`observeFolders`）が `folderList` を丸ごと置き換えるため、楽観エントリは自然に canonical 版へ差し替わる（`AdapterItem.isSameAs` はフォルダをタイトルで識別するため DiffUtil 上も安定）。emission が来ない/解決に失敗する（真因未修正）場合でも、少なくとも当該セッション中は楽観エントリが表示され続ける。
- 楽観 `FolderInfo` の `id` は未確定（0）でよい: `getSortedFolders()` は `drawerListOrder` に無い id を `Int.MAX_VALUE`（フォルダ節の末尾）に置くだけで安全（`.kt:270-277`）。
- 受け入れ条件: drop 直後（再起動なし・数百 ms 以内）に 2 アプリ入りフォルダがフォルダ節末尾に表示され、メイン一覧から 2 アプリが消える（`pref_hideFolderApps` 既定 true）。

**修正F-B（真因特定・恒久デバッグログ）**: タグ `MorrowaFolder` で3点を `Log.d`/`Log.w` に。

1. `observeFolders` 冒頭: 発火の有無、`folders.size`、各 folder の `(id, title, contents.size)`。
2. `FolderService.mapToFolderInfo`: `toItemInfo` が null を返した `componentKey` を `Log.w`（D1' の直接検出）。
3. `addAppsWithSections` のフォルダ分岐: folder ごとの `appsStore.getApp` 解決数と表示可否。

次回実機セッションで「ドラッグ作成 → `adb logcat -s MorrowaFolder`」により D2/D1' を1回で確定し、真因側（D2 なら flow/購読、D1' なら解決経路）を追修正する。ログは軽量なので恒久に残す（削除しない）。

**修正F-C（要望2: detectMoves、§10.34.2 の確定原因への実装）**

- `AlphabeticalAppsList` に `protected boolean shouldDetectMoves() { return false; }` を追加し、`updateAdapterItems()` の `DiffUtil.calculateDiff(cb, false)`（`:376`）を `calculateDiff(cb, shouldDetectMoves())` に変更（AOSP 差分は実質2行）。
- `LawnchairAlphabeticalAppsList` で override し、**drag 中（`draggedComponentKey != null || pendingOrder != null`）のみ true** を返す。§10.30.6 の「drag 中だけ ItemAnimator」と同じスコープに揃い、検索・アプリ更新など通常経路のコストは従来どおりゼロ。
- move 検出により `DefaultItemAnimator` が対称な slide を配信し、**左→右/右→左の非対称が解消**する。O(N²) の move 検出は drag 中の数百 item に限られ許容範囲。
- `BaseAllAppsAdapter.java:156-160` の誤ったコメント（「detectMoves=false でも real move として報告できる」）を訂正する。
- 受け入れ条件: 並び替えプレビューが左右どちら向きでも同じ slide アニメーションになる。drag 終了後の通常更新の挙動が従来どおり。

**実装・検証順**

1. F-A + F-B（フォルダ表示、1タスク）→ ビルド → コミット → 実機: drop 直後の表示を確認、`MorrowaFolder` ログで D2/D1' を確定。
2. F-C（アニメ対称化、独立小タスク）→ ビルド → コミット → 実機: 左右対称を確認。
3. ログで確定した真因（D2/D1'）の本修正を別タスクで設計・実施（F-A により UX は既に直っているため、優先度は下げてよい）。
4. §10.32.2 の再発防止どおり、各タスク完了ごとにコミット + push。

**備考（今回の調査で見つけた将来の改善候補、非緊急）**: 既定タイトルのまま作成すると同名フォルダが複数できる（現に「自分のフォルダー」が4つ）。`AdapterItem.isSameAs` はフォルダをタイトルで識別するため、同名フォルダは DiffUtil 上同一 identity になり、アニメーションが不正確になり得る（表示自体は正しい）。canonical データには `folder.id` があるので、将来「タイトル + id」識別へ強化する余地がある。また、テスト過程で作られた重複フォルダ（id=5/6 等）は設定画面から削除して構わない。

### 10.36 F-A/F-B/F-C 実装記録（2026-07-08）

§10.35.2 の計画どおり実装した。変更ファイルと内容:

| 修正 | ファイル | 内容 |
|---|---|---|
| F-A | `allapps/LawnchairAlphabeticalAppsList.kt` | `createFolder(title, apps)` に楽観的ローカル反映を追加。`viewModel.createFolderWithApps(...)`（既存・裏で DB+reloadGrid）に加え、手元の 2 `AppInfo` から `FolderInfo` を合成（`id=0`）して `folderList.add(...)` → `updateAdapterItems()`。canonical emission が来れば `folderList = folders.toMutableList()` で置き換わる |
| F-B.1 | `allapps/LawnchairAlphabeticalAppsList.kt` | `observeFolders` ラムダ冒頭で `Log.d("MorrowaFolder", ...)`（発火・folders.size・各 `(id, title, contents.size)`） |
| F-B.3 | `allapps/LawnchairAlphabeticalAppsList.kt` | `addAppsWithSections` フォルダ分岐で `Log.d`（folder ごとの `contents / resolved(appsStore.getApp) / shown(size>1)`） |
| F-B.2 | `data/folder/service/FolderService.kt` | `mapToFolderInfo` で `toItemInfo` が null の componentKey を `Log.w("MorrowaFolder", ...)`（D1' 直接検出） |
| F-C | `src/com/android/launcher3/allapps/AlphabeticalAppsList.java` | `protected boolean shouldDetectMoves()` を追加（既定 false）、`updateAdapterItems` の `calculateDiff(cb, false)` → `calculateDiff(cb, shouldDetectMoves())` |
| F-C | `allapps/LawnchairAlphabeticalAppsList.kt` | `shouldDetectMoves()` を override、drag 中（`draggedComponentKey != null || pendingOrder != null`）のみ true |
| F-C | `src/com/android/launcher3/allapps/BaseAllAppsAdapter.java` | §10.22 の誤ったコメント（「detectMoves=false でも real move として報告される」）を §10.34.2 の正しい説明へ訂正 |

`FOLDER_TAG = "MorrowaFolder"` を `LawnchairAlphabeticalAppsList` の private companion に定義。

検証: `compileLawnWithQuickstepGithubDebugJavaWithJavac` → `BUILD SUCCESSFUL in 5m 40s`（新規/変更起因のエラーなし。警告は無関係な既存 `SearchAdapterItem.kt` の hides-Java-field のみ）。**インストールは未完了**: `installLawnWithQuickstepGithubDebug` はコンパイル成功・APK パッケージ済みだが、デバイス転送段階で端末が未接続（`adb devices` が空・Doze/USB 切断）のためハングして失敗（BUILD FAILED、コード起因ではない）。端末再接続後に `installLawnWithQuickstepGithubDebug` を再実行するだけでよい。

未実施（次回実機セッション）:
- 報告1: drop 直後に再起動なしでフォルダが表示されるか（F-A の受け入れ条件）。
- 要望2: 並び替えプレビューが左右対称に slide するか（F-C）。
- 真因確定: 「ドラッグ作成 → `adb logcat -s MorrowaFolder`」で D2（observer 未発火）か D1'（contents 解決失敗）を判定。判定後、F-A で UX は直っている前提で真因の本修正を別タスクで実施。
- コミット + push は実機確認 OK 後（§10.32.2 の再発防止方針）。

### 10.37 F-A/F-C 実機確認と新要望（フォルダの D&D 操作一式）の整理（2026-07-09）

#### 10.37.1 §10.36 ビルドの実機確認結果

| 項目 | 結果 |
|---|---|
| 報告1（フォルダがその場で作られる・F-A） | **解決**。drop 直後・再起動なしでフォルダが表示されるようになった |
| フォルダの**生成位置** | **不正**。重ねた場所ではなく一覧の**上位**にフォルダが作られる（→ 新要望A） |
| 要望2（アニメ左右対称・F-C） | **未報告**（ユーザー未言及。次回確認） |
| 真因ログ D2/D1'（F-B） | **未取得**（logcat 未採取。F-A で症状は消えたため優先度低。ログは恒久設置済みなので次回いつでも採取可） |

F-A が効いた＝楽観エントリ（`folderList` 末尾に追加）が表示されている。生成位置が「上位」なのは、**現状 drawer ではフォルダが一覧の上部ブロックに固めて描画される**ため（§10.37.3）。ここから新要望群に入る。

#### 10.37.2 新要望の整理（A〜D）

ユーザー要望（2026-07-09）を4つに分解:

- **要望A（生成位置）**: フォルダを、重ねた**その場所**に生成する（上部固定ではなく、drop したアプリの位置に）。
- **要望B（フォルダのドラッグ並び替え）**: フォルダ自体を、他アプリと同じようにドラッグして並び替えられるようにする。ただし**フォルダの階層化は禁止**＝フォルダをフォルダ（やアプリ）に重ねてもフォルダの中にフォルダを作らない（重ねは並び替えのみ）。
- **要望C（フォルダのドラッグ→その場で離す→解除メニュー）**: フォルダをドラッグしてその場で離した場合、他アプリ同様にメニュー（ポップアップ）を出し、その中に「**フォルダ解除**」の項目**だけ**を出す。解除したら、**解除した場所に**中のアプリが展開されて出る。
- **要望D（フォルダ内の操作）**: 開いたフォルダの中でも、アプリの並び替えと長押しメニューができるようにする。

#### 10.37.3 現状の drawer フォルダの仕組み（調査結果）

- **管理は設定画面主体**: フォルダの作成/リネーム/削除/メンバー選択/並び順は `ui/preferences/destinations/AppDrawerFoldersPreference.kt`（`drawerListOrder` を並べ替え、`FolderViewModel.deleteFolder` 等）で行う設計。ドロワー内にはフォルダの D&D・解除・フォルダ内並び替えの導線が**無い**。
- **描画は上部ブロック固定**: `LawnchairAlphabeticalAppsList.addAppsWithSections()`（手動フォルダモード `drawerList=true` の else 分岐）は、`getSortedFolders()` を**先に全部**描画（`AdapterItem.asFolder`）→ その後 `remainingApps` を描画する。つまり**フォルダ群は常に一覧の先頭に集まり、アプリはその下**。フォルダの順序は `drawerListOrder`（フォルダ id の順序文字列）、アプリの順序は `drawerAppOrder`（`DrawerAppOrderEntity` の componentKey→rank、**アプリのみ**）で、**両者は別管理**。
- **フォルダアイコンは標準 `FolderIcon`**: `all_apps_folder_icon.xml` は `com.android.launcher3.folder.FolderIcon` を膨らませ、`BaseAllAppsAdapter`（`VIEW_TYPE_FOLDER`, `:294-300` / `:385-393`）が `FolderIcon.inflateFolderAndIcon` で生成。クリックで標準 Folder が開く。長押しは現状ドロワー用の特別処理なし。
- **ドラッグは AppInfo 限定**: `SearchContainerView` の drag 経路（`onDragStart`/`eligibleMainList`/`resolveTargetSlot`）は `dragInfo is AppInfo` を前提。フォルダ（`FolderInfo`）の drag は非対応。フォルダアイコンを長押ししても drawer 内 drag は始まらない。

#### 10.37.4 要望A: フォルダ生成位置 = 統合順序モデルへの変更【本要望群の構造的な核】

- **問題の本質**: フォルダが「上部ブロック固定」なのは §10.37.3 のとおり `addAppsWithSections` がフォルダを先に一括描画するため。生成位置を drop 地点にするには、**フォルダとアプリを1本の順序列に統合**する必要がある（フォルダ用 `drawerListOrder` とアプリ用 `drawerAppOrder` の分離を解消）。
- **設計方針（推奨）**: 手動順序を「**エントリ列**（各エントリ = アプリの componentKey か、フォルダ id のどちらか）」に一般化する。
  - 永続化: `DrawerAppOrderEntity`（componentKey, rank）を、種別を持つ統合エントリ（例: `type ∈ {app, folder}`, `key`(componentKey or folderId), `rank`）へ拡張、または新テーブル追加。DB マイグレーション要（現行 v4 → v5）。
  - 構築: `addAppsWithSections` を「エントリ列を rank 順に走査し、app なら `AdapterItem.asApp`、folder なら `AdapterItem.asFolder` を積む」単一ループへ再構成。フォルダに属するアプリはメイン列から除外（現行 `filteredList` + `pref_hideFolderApps` 準拠）。
  - 生成: フォルダ作成時、drop 先アプリ（`targetApp`）の rank 位置に新フォルダのエントリを挿入し、`targetApp`/`movedApp` はメイン列から除去（フォルダへ移動）。F-A の楽観反映もこの位置へ挿入するよう変更。
- **決定事項（ユーザー確認したい）**: フォルダを「アプリ列の中に混在」させてよいか（＝上部ブロックを廃止）。要望Aの素直な解釈はこれ。ただし現行の「フォルダは上、アプリは下」を好むユーザーもいるため、既定挙動として混在にするか要確認。→ §10.37.8 で確認。
- **影響範囲**: `addAppsWithSections`、`getAppSortComparator`/`drawerAppOrder`、`reorderApp`/`commitPendingOrder`、`getSortedFolders`、DB スキーマ + マイグレーション、設定画面の並び順 UI（`drawerListOrder`）との整合。**最も差分が大きい**。B/C はこの統合順序モデルの上に乗る。

#### 10.37.5 要望B: フォルダのドラッグ並び替え（ネスト禁止）

- **必要な変更**:
  1. **フォルダアイコンを drag 可能に**: ドロワーのフォルダ（`FolderIcon`）長押しで drawer 内 drag を開始し、`dragInfo` に `FolderInfo`（または folder id を運ぶ ItemInfo）を載せる。Home のアイコン長押し→`beginDragShared` に相当する導線を drawer 用に用意（現状アプリ側がどう drag 開始しているか＝`ActivityAllAppsContainerView` の long-click→drag 経路を踏襲）。
  2. **`SearchContainerView` をフォルダ drag 対応に**: `onDragStart`/`eligibleMainList`/`resolveTargetSlot`/`acceptDrop`/`onDrop` の `dragInfo is AppInfo` 前提を、`AppInfo` または `FolderInfo`（統合エントリ）を扱えるよう一般化。並び替えは統合順序列（§10.37.4）の rank 移動として実装。
  3. **ネスト禁止**: ドラッグ中が**フォルダ**のとき、`classifyZone` が FOLDER を返しても**フォルダ作成/投入をしない**（＝常に REORDER 扱い）。アプリをフォルダに重ねた場合の「既存フォルダへ追加（Phase F）」は本要望では扱わない（別途）。少なくとも「フォルダ in フォルダ」は生成経路自体を塞ぐ。
- **決定事項**: アプリを**既存フォルダに重ねた**ときの挙動（追加 or 並び替えのみ）。要望Bは「フォルダをドラッグして並び替え」なので、まずは**フォルダ drag = 並び替えのみ**に限定し、アプリ→既存フォルダ追加は後続（Phase F）とするのが安全。→ §10.37.8。

#### 10.37.6 要望C: フォルダのドラッグ→その場で離す→「解除」メニュー

- **UX 対応付け**: Launcher3 では長押し→`PopupContainerWithArrow`（ショートカット/アプリ情報のポップアップ）が出て、そのままドラッグすれば drag、動かさず離せばポップアップが残る。要望Cはフォルダ版で「ポップアップに**解除のみ**」。
- **必要な変更**:
  1. **フォルダ用ポップアップ**: ドロワーのフォルダ長押し（またはドラッグして未移動で release）で、`SystemShortcut` 相当の「フォルダ解除」1項目だけのポップアップを表示。アプリ側のドロワー長押しポップアップ（`PopupContainerWithArrow` / `LauncherPopupLiveUpdateHandler` 周辺）に、フォルダ ItemInfo のときは解除項目だけを供給する分岐を追加。
  2. **解除処理**: 「解除」で、そのフォルダの中身アプリを**フォルダの位置**に展開する。統合順序列（§10.37.4）で、フォルダエントリを除去し、その rank 位置に中身アプリの componentKey を rank 順に挿入 → `FolderViewModel.deleteFolder(id)`（既存、folder + items を CASCADE 削除）。`pref_hideFolderApps` で隠れていたアプリがメイン列のその位置に戻る。
- **決定事項**: 「ドラッグしてその場で離す」と「単なる長押し」を同一の解除ポップアップにまとめてよいか（実装が単純）。要望文は「ドラッグしてその場で離した場合」だが、アプリと同じ操作感なら長押しでも同じポップアップが自然。→ §10.37.8。

#### 10.37.7 要望D: フォルダ内のアプリ並び替え・長押しメニュー

- **現状**: ドロワーのフォルダは標準 `FolderIcon`→クリックで標準 `Folder` が開く。Home のフォルダは開いた状態で並び替え（`FolderPagedView.realTimeReorder`）と各アイコン長押しに対応済み。ドロワー由来フォルダの開いた `Folder` が、その並び替え結果を **Room（`FolderItemEntity.rank`）へ永続化**するか、長押しメニューが drawer 文脈で機能するかは**要調査**。
- **必要な変更（見込み）**:
  1. 開いたフォルダ内の並び替えを許可し、確定時に `FolderViewModel.updateFolderItems(id, title, apps)`（既存・並び順を rank で保存）へ反映。
  2. フォルダ内アイコンの長押しメニュー（アプリ情報/アンインストール/「フォルダから出す」等）を drawer フォルダでも出す。「フォルダから出す」を入れるなら統合順序列への戻し処理が要る（要望Cの解除と共通ロジック）。
- **決定事項**: フォルダ内アイコンの長押しに何を出すか（最小: アプリ情報のみ／推奨: アプリ情報＋フォルダから出す）。→ §10.37.8。

#### 10.37.8 実装順・要決定事項

**依存関係**: 要望A の統合順序モデルが B/C の土台。D は比較的独立（標準 Folder の drawer 永続化）。

**推奨実装順（各タスクでビルド＋実機＋コミット/push、§10.32.2）**:

1. **タスクG（統合順序モデル・要望A）**: フォルダとアプリを1本の順序列に統合。DB v5 マイグレーション、`addAppsWithSections` 単一ループ化、`reorderApp`/`commitPendingOrder`/F-A 楽観反映を統合列へ。→ フォルダが drop 位置に出る。
2. **タスクH（フォルダ drag 並び替え・要望B）**: フォルダアイコン長押し→drag、`SearchContainerView` の FolderInfo 対応、ネスト禁止。
3. **タスクI（解除ポップアップ・要望C）**: フォルダ用ポップアップ（解除のみ）＋解除で中身をその位置へ展開。
4. **タスクJ（フォルダ内操作・要望D）**: 開いたフォルダの並び替え永続化＋長押しメニュー。

**決定事項（ユーザー確定・2026-07-09）**:

- (A) **アプリ列に混在（上部ブロック廃止）**。フォルダとアプリを単一順序列に統合し、重ねた場所にフォルダを生成する（タスクG）。
- (B) **既存フォルダに追加する**。アプリを既存フォルダへ深く重ねたらそのフォルダに追加する（＝Phase F を後続ではなく今回スコープに含める）。フォルダ同士のネストは引き続き禁止（フォルダ drag は並び替えのみ）。
- (C) **長押しでも解除ポップアップを表示**（アプリの長押しと同操作感）。ドラッグ未移動 release と長押しの両方で同じ解除ポップアップ。
- (D) **標準ホームフォルダと同じ長押しメニュー**（アプリ情報・アンインストール等を含む）。フォルダ内アイコンは Home のフォルダ同様のロングクリックメニューを出す。

この決定に伴うスコープ調整:

- タスクH（要望B）に「**アプリ→既存フォルダへの追加（Phase F）**」を含める。フォルダ drag 時のネスト禁止は維持。`SearchContainerView` の drop 分岐は「ドラッグが App かつ深い重なりの相手が**アプリ**→新規フォルダ、相手が**フォルダ**→そのフォルダに追加、ドラッグが Folder→常に並び替え」に整理。
- タスクI（要望C）は長押し起点のポップアップを主導線にする（drag 未移動 release も同じポップアップに合流）。
- タスクJ（要望D）は Home のフォルダ長押しメニュー（`PopupContainerWithArrow` + `SystemShortcut` 群）を drawer フォルダの開いた `Folder` でも有効化し、並び替え結果と「フォルダから出す/アンインストール」を Room（`FolderItemEntity`）へ反映する方針。

**未確定の技術リスク（実装中に詰める）**: (1) 統合順序と設定画面 `drawerListOrder` の二重管理の整理（統合列を正とし、設定画面はそれを編集する形へ寄せるか）。(2) ドロワー由来フォルダの開いた `Folder` が reorder/標準長押しメニューを drawer 文脈で正しく扱えるか（要コード調査、タスクJ 着手時）。(3) DB マイグレーション v4→v5 の後方互換（既存 `drawerAppOrder`/`drawerListOrder` から統合列への移行）。(4) 既存フォルダ追加（B）と解除（C）で、メイン統合列とフォルダ `FolderItemEntity` の整合を両方向で保つこと。

→ タスクG〜J の詳細設計を §10.38 で確定した（リスク (1)〜(4) への対応も織り込み済み）。

### 10.38 タスクG〜J 詳細設計（2026-07-09・コード未変更）

#### 10.38.0 追加調査で確定した事実（設計の前提）

| # | 事実 | 位置 |
|---|---|---|
| a | `OptionsPopupView.show(launcher, RectF, List<OptionItem>, ...)` が存在し、任意矩形にアンカーした任意項目のポップアップを出せる（Home の壁紙/ウィジェットメニューで使用実績）。`PopupContainerWithArrow` と違い `BubbleTextView` 前提でない → **解除ポップアップに流用可能** | `views/OptionsPopupView.java:163`（`OptionItem` は `:315,324`） |
| b | drawer フォルダの ViewHolder は**毎バインドで `FolderIcon` を作り直す**（`removeAllViews()` → `inflateFolderAndIcon`）。**long-click は未配線**。`onBindViewHolder` 冒頭で `itemView.setVisibility(VISIBLE)` に**リセット**される | `BaseAllAppsAdapter.java:296-302`, `:312-313`, `:387-395` |
| c | `FolderInfo.add(ItemInfo)` は LC 改修済みで `AppInfo` を受け付ける | `model/data/FolderInfo.java:99` |
| d | `Folder.isInAppDrawer()` = `mInfo.container == NO_ID` が既にあり、drawer フォルダから中身をドラッグすると「フォルダを閉じるだけで item は削除しない」安全な dead-end になっている | `folder/Folder.java:529`, `:507-517` |
| e | `FolderIcon.isInAppDrawer()` も存在し、複数箇所で drawer 特例が既にある | `folder/FolderIcon.java:430,741,748` |
| f | 現行の手動順序テーブルは `DrawerAppOrderEntity(componentKey PK, rank)`（アプリ専用） | `data/appdrawer/DrawerAppOrderEntity.kt` |
| g | `addAppsWithSections` のフォルダ描画は `FolderInfo()` を新規生成して title だけコピーし、**canonical id を引き継いでいない**（`AdapterItem.asFolder` の identity が title 頼みになっている一因） | `LawnchairAlphabeticalAppsList.kt:319-321` |

#### 10.38.1 タスクG: 統合順序モデル（要望A）

**G-1. データモデルとマイグレーション**

- 新テーブル `DrawerOrder`: `DrawerOrderEntity(key: String @PrimaryKey, rank: Int)`。`key` は名前空間付き文字列 **`"app:<componentKey>"` / `"folder:<folderId>"`**（componentKey と folder id の衝突を構造的に排除）。DAO は `getAll(): Flow<List<DrawerOrderEntity>>` + `replaceAll(entities)`（`@Transaction` で DELETE→INSERT、既存 `DrawerAppOrderDao` と同型）。
- Room は **v4→v5 で CREATE TABLE のみ**（`Migration` は `Context` を持てず `drawerListOrder` pref を読めないため、SQL だけでは現表示順を再現できない）。
- **データ移行はランタイムシード**: `LawnchairAlphabeticalAppsList`（または repository）が「`DrawerOrder` が空 かつ（旧 `DrawerAppOrder` に行がある or フォルダが存在する）」を検出したら、**現行の表示順**（`getSortedFolders()` 順のフォルダ → `drawerAppOrder`/アルファベット順のアプリ）をそのまま rank 0..N で `DrawerOrder` に書き込む。**アップグレード直後の見た目は不変**（フォルダ上部ブロックのまま）で、以後の並び替え/作成で初めて混在していく。旧 `DrawerAppOrder` テーブルと `drawerListOrder` pref は**残置・参照停止**（書き込みも停止）。
- メモリ表現: `sealed class DrawerEntry { class App(val info: AppInfo); class Folder(val info: FolderInfo) }` + `fun key(): String`。`SearchContainerView` との受け渡しもこの型に統一する。

**G-2. 読み経路（表示構築）**

- `LawnchairAlphabeticalAppsList` は `DrawerOrder` の Flow を observe して `drawerOrder: Map<String, Int>` を保持（既存 `drawerAppOrder` 観測の置き換え）。
- `getAppSortComparator()` は継続（アプリの rank は `"app:<key>"` で引く。未登録アプリは末尾アルファベット順）。**mApps の相対順 = 統合列のアプリ相対順**を保ち、DiffUtil と fast scroll の前提を崩さない。
- `addAppsWithSections()` の手動モード分岐（`drawerList=true` の else）を**単一 walk** に再構成:
  1. 表示エントリ列を構築: フォルダ（resolve 済み・`size>1` ガード通過のもの）を rank で、アプリ（comparator ソート済み `appList`、`filteredList` 除外後）を rank で、**マージ走査**（rank 同値は folder 優先、rank 無しアプリは末尾）。
  2. walk 中、folder エントリは `AdapterItem.asFolder`、app エントリは `AdapterItem.asApp` を積む。fast scroll のセクション生成は base の `addAppsWithSections()`（`AlphabeticalAppsList.java:483-518`）と同じ「`sectionName` 変化で `FastScrollSectionInfo` 追加」ロジックを walk 内の app item に対して再現する（現行の手動順時の挙動と同等）。
  3. **事実g の修正**: `AdapterItem.asFolder` へ渡す `FolderInfo` に canonical `id` を引き継ぐ。あわせて `AdapterItem.isSameAs` のフォルダ識別を「**両方 id≠0 なら id 比較、それ以外は title 比較**」へ強化（同名フォルダ4つ問題 §10.35 備考の解消。楽観エントリ(id=0)は title でマッチし、canonical 化で id マッチへ自然移行）。
- `getSortedFolders()` は廃止し、フォルダ順も統合列から取る。

**G-3. 書き込み経路（並び替え・作成・楽観反映）**

- `pendingOrder: MutableList<AppInfo>` を **`MutableList<DrawerEntry>` へ一般化**（タスクHの土台。G の時点では App エントリしか動かないが型は entry）。`movedTo`/`previewReorder`/`beginPendingReorder`/`commitPendingOrder`/`reorderApp` を entry ベースへ書き換え、確定時は `DrawerOrder.replaceAll`（表示エントリ列全体を rank=index で書く。フォルダも毎回書かれるので folder rank の別管理が消える）。
- フォルダ作成（`createFolder`）: 統合列で **targetApp のエントリ位置に folder エントリを挿入**し、member 2アプリの app エントリを**除去**して `replaceAll`。F-A の楽観反映（`folderList.add`）はそのまま、位置は上記 rank が決める。→ **要望A成立**（drop したその場所にフォルダが出る）。
- **設定画面との整合（リスク(1)の解消）**: 統合列を唯一の正とする。`AppDrawerFoldersPreference` のフォルダ並び替え UI は「folder エントリ同士の相対順を統合列内で入れ替える」操作として `DrawerOrder` へ書く（アプリを跨いだ絶対位置は既存の各 folder エントリ位置を維持したまま、folder エントリの並びだけ順序交換）。`drawerListOrder` は読み書きとも停止。

**G-4. フェーズ分割と受け入れ条件**

| フェーズ | 内容 | 受け入れ条件 |
|---|---|---|
| G1 | スキーマ v5 + DAO + ランタイムシード + 読み経路（walk 化） | アップグレード後、見た目・並び順が完全に従来どおり（フォルダ上部・アプリ下部・手動順維持）。再起動後も同じ |
| G2 | pendingOrder の entry 化 + 書き込みの `DrawerOrder` 化 | アプリ並び替え（drop/preview/WYSIWYG）が従来どおり動き、再起動後も保持。設定画面のフォルダ並び替えが引き続き機能 |
| G3 | フォルダ作成位置 + 楽観反映の位置対応 | **重ねた場所に**フォルダが生成され（再起動なしで表示、F-A 維持）、再起動後も同位置 |

#### 10.38.2 タスクH: フォルダ drag 並び替え（ネスト禁止）+ アプリ→既存フォルダ追加（要望B + Phase F）

**H-1. フォルダアイコンの長押し→drag 開始**

- 配線場所: `BaseAllAppsAdapter.onBindViewHolder` の `VIEW_TYPE_FOLDER` 分岐（`:387-395`、既に LC-Feature 領域）で、生成した `FolderIcon` に `OnLongClickListener` を設定する。リスナ実体は Lawnchair 側に新設（`DrawerFolderLongClick` 相当）:
  1. `ItemLongClickListener.onAllAppsItemLongClick`（`touch/ItemLongClickListener.java:150`）と同じゲート（`isInState(ALL_APPS)`、drag 未進行等）。
  2. 解除ポップアップを表示（タスクI、`OptionsPopupView`）。
  3. `launcher.getWorkspace().beginDragShared(folderIcon, launcher.getAppsView(), DragOptions(...))` を呼ぶ。`FolderIcon` の tag は `FolderInfo`（`ItemInfo` サブクラス）なので `beginDragShared` の tag チェック（`Workspace.java:1957-1959`）を通り、DragPreviewProvider も View ベースで動く（Home のフォルダ drag 実績）。`DragOptions.preDragCondition` に「タッチスロップ超えで解除ポップアップを close して本 drag へ」を実装（アプリの `PopupContainerWithArrow` と同じ操作感。§9.3 の教訓どおり **state は一切触らない**）。
- `Workspace.onDragStart` の既存分岐（dragSource が appsView → state 遷移なし）は `FolderInfo` でもそのまま成立。
- **上部バー**: `AddToHomescreenDropTarget`（AppInfo 前提）・`SecondaryDropTarget`（Uninstall）・`DeleteDropTarget` が `FolderInfo` drag で `supportsDrop=false` になることを確認し（ならない場合は明示ガード追加）、フォルダ drag 中はバーが出ない状態を仕様とする。

**H-2. `SearchContainerView` の entry 一般化**

- `draggedComponentKey` → `draggedEntryKey`（`"app:"`/`"folder:"`）。R1 の attach listener・初回走査も entryKey 照合へ（folder VH は毎バインド reinflate（事実b）だが、drag 中は `isContentSame`（folder は `itemInfo==null` 同士 → true）で rebind が抑制されるため INVISIBLE は維持される。`:313` の bind 時 VISIBLE リセットが効くのは rebind 時のみ＝drag 中は発生しない。実機確認項目に含める）。
- `eligibleMainList` のゲートを「`dragInfo is AppInfo` **または** `dragInfo is FolderInfo`（drawer フォルダ = `container == NO_ID`）」へ。
- `resolveTargetSlot`: `VIEW_TYPE_FOLDER` の child もスロットに含める（`TargetSlot` に entryKey を持たせる）。
- **ゾーン分岐（決定Bを織り込んだ最終形）**:

```text
drag = App:
  target = App,    dist ≤ folderEnter → 新規フォルダ作成（既存の R4 経路）
  target = Folder, dist ≤ folderEnter → 既存フォルダへ追加（H-3）
  それ以外の REORDER 圏             → 統合列で並び替え
drag = Folder:
  classifyZone の結果に関わらず常に REORDER（FOLDER 圏は REORDER に降格）
  → フォルダ in フォルダ / フォルダ投入は経路ごと存在しない（ネスト禁止）
```

- ヒステリシス・enter/exit 状態機械（§10.30.3）は entryKey ベースでそのまま流用。

**H-3. アプリ→既存フォルダ追加**

- drop 確定: `FolderViewModel.updateFolderItems(folder.id, folder.title, contents + movedApp)`（既存 API、rank=index で全書き換え）。
- 楽観反映: `folderList` 内の該当 `FolderInfo.add(movedApp)`（事実c）+ 統合列から moved の app エントリを除去して `replaceAll` + `updateAdapterItems()`。
- ホバー演出: target が folder のときも同じ scale アップ（`FOLDER_HOVER_SCALE`）。
- ガード: 楽観フォルダ（id=0、canonical 未着）への追加は**不可**（id=0 なら追加せず並び替えに降格 + `MorrowaFolder` ログ。canonical 置き換えは通常サブ秒で完了するため実用上問題なし）。

**H-4. フェーズと受け入れ条件**

| フェーズ | 内容 | 受け入れ条件 |
|---|---|---|
| H1 | フォルダ長押し→drag→統合列並び替え（ネスト禁止） | フォルダをドラッグして任意位置（アプリ間含む）へ並び替えでき、再起動後も保持。フォルダに深く重ねても何も作られない。ドラッグ中バーが出ない。R1 不可視・着地アニメがフォルダでも機能 |
| H2 | アプリ→既存フォルダ追加 | アプリをフォルダに深く重ねると scale 演出→drop で中身に追加され（drawer 即時反映+Room 永続化）、メイン一覧から消える。浅い重なりは並び替え |

#### 10.38.3 タスクI: フォルダ解除ポップアップ（要望C）

- **文言/リソース**: 新規 string `drawer_folder_disband` = 「フォルダを解除」（en: "Disband folder"）。アイコンは既存 `ic_remove_no_shadow`。
- **表示**: H-1 の長押しハンドラから `OptionsPopupView.show(launcher, RectF(FolderIcon の DragLayer 相対矩形), listOf(OptionItem(drawer_folder_disband, ...)), /* shouldAddArrow */ false)`（事実a）。ドラッグ未移動で release → ポップアップが残る（`OptionsPopupView` の既定動作）。移動 → `preDragCondition.shouldStartDrag` で `AbstractFloatingView.closeOpenViews` により閉じて drag へ（決定C: 長押し・その場 release どちらも同じポップアップ）。
- **解除処理 `disbandFolder(folder)`**（リスク(4) の片方向）:
  1. 統合列: folder エントリ（rank r）を除去し、`folder.getContents()` の**メンバーを rank 順に r から連続挿入**（`"app:<key>"` エントリ復活。`pref_hideFolderApps=false` 等で既にメイン列にエントリが存在するアプリはスキップ）→ `replaceAll`。
  2. `FolderViewModel.deleteFolder(folder.id)`（既存。`FolderItems` は FK CASCADE で消える）。
  3. 楽観反映: `folderList` から除去 + `updateAdapterItems()` → **解除した位置に中身が展開されて出る**。
- 受け入れ条件: フォルダ長押しで「フォルダを解除」だけのポップアップが出る。解除するとその位置に中のアプリが順序どおり展開され、再起動後も保持。設定画面のフォルダ一覧からも消える。ポップアップを無視してドラッグすれば H の並び替えができる。

#### 10.38.4 タスクJ: フォルダ内の並び替え・長押しメニュー（要望D）

- **前提（事実d/e）**: drawer フォルダを開いた標準 `Folder` は `isInAppDrawer()` で識別でき、開いた中での並び替え自体（`FolderPagedView.realTimeReorder`）は Home 実装がメモリ上（`mInfo` の並び）では既に動く見込み。**欠けているのは Room への永続化と、長押しメニューの動作確認**。
- **J1（並び替えの永続化）**: `Folder` の並び替え確定点（`onDrop` の `rearrangeChildren()` 後、または `close`）に `isInAppDrawer()` ガード付きの LC フック（1〜2行）を追加し、Lawnchair 側で `mInfo` の現在順序を `FolderViewModel.updateFolderItems(mInfo.id, mInfo.title, apps)` へ書く。**`mInfo.id == 0`（楽観フォルダのまま開いた）場合は書かずに `MorrowaFolder` ログ**（canonical 置き換え後は id 付きで開かれる）。
- **J2（長押しメニュー）**: フォルダ内アイコン（`BubbleTextView`）の長押しに標準ポップアップ（アプリ情報・Uninstall・deep shortcuts = Home のフォルダと同一、決定D）を出す。**要調査ポイント（着手時最初に確認）**: Home のフォルダ内長押しは `ItemLongClickListener.onWorkspaceItemLongClick` 経路で、`NORMAL` 系 state ゲートを持つ可能性が高い。drawer フォルダは `ALL_APPS` state で開くため、`Folder` のアイコン bind 時に `isInAppDrawer()` なら ALL_APPS ゲートのリスナ（`onAllAppsItemLongClick` 相当）へ差し替える分岐が必要になる見込み。Uninstall 実行後の整合は、次回 emit 時に `toItemInfo` が null → contents から自然消滅（`FolderItems` の残骸行は表示に影響しないため掃除は将来課題）。
- **J3（スコープ外の明記）**: フォルダから外への**ドラッグ排出**は今回やらない（現状は事実d のとおり「閉じるだけ」の安全な dead-end。中身を出す手段は解除（I）と設定画面で提供済み。将来 Phase F' として統合列への drop 対応を検討）。
- 受け入れ条件: drawer フォルダ内でアイコンをドラッグ並び替え→閉じて開き直しても順序保持（再起動でも）。フォルダ内アイコン長押しで Home フォルダと同じメニューが出て、アプリ情報/Uninstall が機能する。

#### 10.38.5 リスク対応の対応表と実装順

| §10.37.8 リスク | 対応 |
|---|---|
| (1) drawerListOrder との二重管理 | G-3: 統合列を唯一の正に。設定画面は統合列を編集、pref は読み書き停止・残置 |
| (2) 開いた Folder の drawer 文脈 | J1/J2: `isInAppDrawer()`（既存）ガードのフックで永続化、長押しゲートは着手時に要調査と明記 |
| (3) v4→v5 移行 | G-1: SQL は CREATE のみ + ランタイムシードで現表示順を保存（アップグレード直後の見た目不変） |
| (4) 統合列と FolderItems の双方向整合 | H-3（追加=列から除去+items 追記）/ I（解除=items 削除+列へ展開）を各1関数に集約し、楽観反映とセットで実装 |

**実装順（各タスクでビルド→実機確認→コミット+push、§10.32.2 遵守）**: G1 → G2 → G3 → H1 → H2 → I → J1 → J2。G1/G2 は「挙動不変」の受け入れ条件を持つ純リファクタなので、ここで回帰（並び替え・fast scroll・検索・Work タブ・フォルダ表示・設定画面）を厚めに確認してから G3 以降の挙動変更に進む。§10.36 の残項目（F-C の左右対称の実機確認、`MorrowaFolder` ログによる D2/D1' 確定）は G1 の実機確認と同じセッションで一緒に消化する。
### 10.39 タスクG・J の並行実装・レビュー・統合（2026-07-10）

§10.38 の依存関係から並行可能な **G連鎖（要望A の土台）と J連鎖（要望D・G非依存）** を、worktree 分離の2エージェントでオーケストレーションして実装。各エージェントに詳細レポートを義務付け、統括（Claude）がレポートと実コードを突き合わせてレビューした。

**前提整備**: F-A/F-B/F-C（＋フォルダ機能 rebuild 一式、§10.24-10.36）を `0622086f20` としてコミット+push（git identity 未設定を `namamiso <hanpenneko@gmail.com>` で解決）。worktree はコミットから分岐するため先行コミットが必須だった。`local.properties`(sdk.dir) は gitignore で worktree に無いため各エージェントにコピー手順を付与。

**運用上の知見（重要）**: エージェントが foreground の gradle ビルド（数分・無出力）を実行すると 600s のストリーム・ウォッチドッグでストールする。→ 「**エージェントはビルドしない。実装+コミット+レポートまで。コンパイルは統括が回して差し戻す**」方針に切替え。両者ともこの方針で完走。

**タスクG（`worktree-agent-ab2a5f5…`, 4コミット: G1 `2455d89` / G2 `8170615` / G3 `6c01525` / schema `d3e4883`）**:
- 実装: 新テーブル `DrawerOrder(key,rank)`（namespaced `app:` / `folder:`）、v4→v5 マイグレーション（CREATE のみ）＋ランタイムシード（`maybeSeedDrawerOrder`：drawerOrder 空かつ旧データ有りで現表示順を rank 0..N 保存、新規ユーザーは非シード）、読み walk（`buildOrderedEntries`：フォールバック＝従来上部ブロック／rank-merge、fast-scroll セクション再現）、`pendingOrder` の `DrawerEntry` 化、フォルダ生成を drop 位置に、fact g 修正（canonical id 引き継ぎ＋`isSameAs` を `id>0` 同士は id 比較）、設定画面の並び替えを `DrawerOrder` へ（`reorderDrawerFolders`）。
- レビュー確定の逸脱3点（妥当）: ①app 書き込みを G2→G1 前倒し（read/write 整合上必然）②`isSameAs` は `id != 0` でなく **`id > 0`**（`NO_ID=-1` を正しく反映）③`drawerListOrder` は設定画面のフォールバック順として書込維持。
- 要デバイス確認（レポート申告）: フォルダがアプリ間に入った時の fast-scroll、シードのタイミング（フォルダ遅延ロード時）、`hideFolderApps=false` 時のメンバー位置、G3 の一瞬のちらつき。

**タスクJ（`worktree-agent-a8190b…`, 2コミット: J1 `cc910c6` / J2 `b1dd9d6`）**:
- 設計前提2つが誤りと判明（レポートで訂正）: (i) フォルダ内長押しは `Folder.onLongClick → beginDragShared → PopupContainerWithArrow` を通り **state ゲート無し**で既に Home と同じメニューが出る → **J2 はコメントのみ**。(ii) `Folder.onDragStart` が drawer フォルダを**あらゆる drag で閉じていた**ため内部並び替えも不可能だった → 並び替えの**有効化**が必要。
- 実装（全て `isInAppDrawer()` ガード）: `onDragStart` の即閉じ削除、`onDropCompleted` に `effectiveSuccess = success && !(isInAppDrawer() && target != this)`（drag-out は失敗扱いで item を戻す＝J3 dead-end 維持、内部並び替え target==this は不変）、`DrawerFolderReorder.persistOrder`（`@JvmStatic`・id==0 スキップ・`filterIsInstance<AppInfo>`）で Room 永続化。
- 要デバイス確認: drag-out で item が確実に戻るか、内部 drag 中に上部バー（add-to-home/uninstall）が出ないか。

**検証・統合**:
- 単体コンパイル: J `BUILD SUCCESSFUL 2m57s` / G `5m49s`（KSP が schema v5 JSON 生成→G ブランチにコミット）。
- 16-dev へ G→J の順で `--no-ff` マージ（`f69fdba` / `57f0f7a`）。**ファイル競合ゼロ**（G=主リスト系、J=Folder 系、`FolderViewModel` は G のみ変更）。
- 統合コンパイル（マージ済み）: `BUILD SUCCESSFUL 4m12s`。実機 `R5CT3378XTJ` へインストール。

**未実施**: 実機確認（上記 G/J の受け入れ条件＋要確認項目、§10.36 の F-C 左右対称・`MorrowaFolder` の D2/D1' 確定を同セッションで消化）。push は実機 OK 後。次は第2バッチ H（フォルダ drag＋既存フォルダ追加）→ I（解除ポップアップ）。

### 10.40 実機確認結果（2026-07-10）: G/J 統合ビルドは「フォルダ内長押しメニュー以外は全滅」

ユーザー実機確認（`57f0f7a`）の結果、**フォルダ内アイコンの長押しメニュー（J2）以外はまったく機能しなかった。**

| 項目 | 実機結果 |
|---|---|
| J2: フォルダ内アイコンの長押しメニュー | **動作**（唯一機能した） |
| G（要望A）: 統合順序・フォルダの drop 位置生成・フォルダ/アプリ並び替え | **全く動かず** |
| J1: フォルダ内並び替えの Room 永続化 | **全く動かず** |
| F-C: 並び替えプレビューの左右対称 slide | 未達／未確認 |
| F-B: `MorrowaFolder` ログでの D2/D1' 確定 | 未確認 |

**重大**: J2 は元々 AOSP 経路で動いていたもの（§10.39 の調査どおり実質コード追加なし）で、Morrowa 独自実装で新たに機能した項目は無い。§10.36 の F-A 単体ビルドでは「フォルダがその場に作られる」ところまで動いていたが、**G の統合（`addAppsWithSections`／順序モデルの全面書き換え）で、それも含めてドロワーの編集系が全面退行した**とみられる。

**反省点**: G/J はコンパイル成功・実機インストール成功までは確認したが、**実機での動作確認をしていなかった**（コンパイル成功≠動作）。エージェント成果を静的レビュー＋コンパイルのみで 16-dev へ統合したのは早計だった。

**次アクション（真因究明）**: コード未変更で、実機ログ・挙動から G のどこで壊れたかを切り分ける。優先調査点:
- ドロワーがそもそも正しく描画されているか（`addAppsWithSections` の walk が空/例外/全消えになっていないか）。
- `observeDrawerOrder`／`maybeSeedDrawerOrder` の初期化順・シード条件で表示が崩れていないか。
- `SearchContainerView` と `pendingOrder`（`DrawerEntry` 化）の受け渡しが壊れ、ドラッグ並び替え・フォルダ生成が発火しなくなっていないか（H 未実装のため drag 経路が entry 型と不整合の可能性）。
- `getAppSortComparator` の preview 分岐が `DrawerEntry.App` 前提になり、既存 drag 経路（AppInfo index）と噛み合っているか。

worktree（G/J エージェント）は差し戻し・追加調査のため保持。**この巻き戻し/退行は 16-dev に push 済みのため、次は真因究明→修正を最優先とする。**
