# Morrowa App Drawer 編集 / drag 実装計画

作成日: 2026-07-02
位置付け: `docs/Morrowa_MVP_実装メモ.md` の今日の作業履歴末尾へ追記するための実装計画。App Drawer / All Apps 内のアプリアイコン長押しから、既存ホーム画面 drag/drop に近い編集体験を追加する。

---

## 12. 2026-07-02 実装計画: App Drawer 編集 / drag 体験

### 12.1 目的

App Drawer / All Apps 内のアプリアイコンを長押ししたとき、ホーム画面上のアイコン drag に近い編集 / drag 状態へ入り、画面上部に drop target を表示する。

App Drawer からの drag 中に表示する上部 drop target は、MVP では次の2つだけに限定する。

```text
Uninstall
Add to Home screen
```

この実装では、Launcher3 / Lawnchair 既存の drag-and-drop 基盤をできるだけ再利用し、App Drawer 専用の独自 drag system は作らない。

### 12.2 目標 UX

1. ユーザーが App Drawer / All Apps を開く。
2. App Drawer 内のアプリアイコンを長押しする。
3. Lawnchair が、ホーム画面アイコン drag に近い drag / edit 状態へ入る。
4. 画面上部に drop target area が表示される。
5. drop target area には `Uninstall` と `Add to Home screen` だけを表示する。
6. `Uninstall` へ drop すると、既存の uninstall flow を起動する。
7. `Add to Home screen` へ drop すると、対象アプリの shortcut を現在の Workspace / Home 画面へ追加する。
8. 対象アプリが uninstall 不可の場合は、既存の Launcher3 / Lawnchair 挙動に従い、`Uninstall` を非表示または disabled にする。
9. 何らかの理由で Home へ追加できない場合は、クラッシュせず安全に失敗させる。

### 12.3 実装制約

- 既存の `DragController`、`DropTargetBar`、`ButtonDropTarget`、`Workspace`、All Apps drag logic を優先して再利用する。
- 既存基盤で実現できる場合、App Drawer 専用の独自 drag system は作らない。
- ホーム画面アイコン drag 時の見た目、アニメーション、drop target 表示に寄せる。
- 変更は小さく、Lawnchair / Launcher3 の既存設計に沿わせる。
- 必要のない大規模リファクタは行わない。
- 既存のホーム画面 drag/drop を壊さない。
- App Drawer のスクロール、検索、フォルダ、prediction を壊さない。
- work profile、private profile、cloned profile、multi-user app の扱いを壊さない。
- `AppInfo` / `ItemInfo` / `WorkspaceItemInfo` 変換は、既存 utility を優先して使う。

### 12.4 事前調査フェーズ

目的:
既存のホーム画面 drag/drop と App Drawer long press の入口を把握し、最小差分で入れる差し込み点を決める。

調査対象:

| 領域 | 確認すること |
|---|---|
| ホーム画面 drag | アイコン長押しから drag 開始、top drop target 表示までの流れ |
| DropTargetBar | drop target の表示 / 非表示、drag source ごとの target 出し分け可否 |
| ButtonDropTarget | `Uninstall` / remove / info など既存 target の受け入れ条件 |
| App Drawer long press | App Drawer 内アイコン長押しが現在どう処理されているか |
| All Apps -> Workspace | 既存の All Apps から Workspace へアプリを置く処理があるか |
| Profile / UserHandle | work profile、private profile、cloned profile、multi-user app の item 情報保持方法 |

優先して読む候補:

```text
DragController
DropTargetBar
ButtonDropTarget
DeleteDropTarget / UninstallDropTarget / uninstall target equivalents
Workspace
PagedView
ActivityAllAppsContainerView
BaseAllAppsAdapter
AllAppsRecyclerView
App Drawer item view classes
Launcher / LawnchairLauncher
ItemInfo / AppInfo / WorkspaceItemInfo
```

事前調査の出力:

- ホーム画面 drag で top drop targets を表示している class / method
- App Drawer item long press の入口 class / method
- App Drawer から Workspace へ app shortcut を追加する既存経路の有無
- `Add to Home screen` が既存 Workspace drop logic を再利用できるか、小さな新規 `DropTarget` が必要か
- 触るべきファイル / 触らない方がよいファイル

### 12.4a 事前調査結果 (2026-07-04)

Explore による調査の結果、前提が変わった。**App Drawer 長押しは、すでに Workspace と同じ `DragController` / `DropTargetBar` 基盤で drag を開始している。** Phase 1 相当は実質実装済みであり、新規に必要な実装はかなり小さい。

主な事実:

| 項目 | 結果 |
|---|---|
| App Drawer 長押し → drag 開始 | 既に実装済み。`ItemLongClickListener.onAllAppsItemLongClick()`（`touch/ItemLongClickListener.java:138-179`）が `launcher.getWorkspace().beginDragShared(v, launcher.getAppsView(), new DragOptions())`（同 L177）を呼び、Workspace 長押しと同じ `Workspace.beginDragShared` → `LauncherDragController.startDrag` 経路に入る |
| drag source の記録 | `LauncherDragController.startDrag()` が `mDragObject.dragSource = source;`（`dragndrop/LauncherDragController.java:169`）を設定済み。App Drawer 由来なら `source` は `Launcher.getAppsView()`（`ActivityAllAppsContainerView`） |
| All Apps -> Workspace 配置 | 既に実装済み。`Workspace.onDropExternal()`（`Workspace.java:3082-`、コメント L3182 "This is for other drag/drop cases, like dragging from All Apps"）が `ItemInflater.inflateItem()` で `AppInfo` を `WorkspaceItemInfo` に変換し配置する |
| Uninstall target | `SecondaryDropTarget`（`SecondaryDropTarget.java`）が担当。`getButtonType()`（L151-193）は `ItemInfo` のみで判定しており、`AppInfo`（App Drawer 由来）でもそのまま動く。multi-profile 制約（work / private / cloned）も `item.user` ベースで既に対応済み |
| `Add to Home screen` に相当するボタン | **存在しない**。`res/layout/drop_target_bar.xml` には `DeleteDropTarget`（Remove/Cancel）と `SecondaryDropTarget`（Uninstall）の2つしかない |
| drag source によるボタン出し分け | **存在しない**。`ButtonDropTarget.onDragStart()`（`ButtonDropTarget.java:217-229`）は `dragObject.dragInfo`（`ItemInfo`）だけを見ており、`dragObject.dragSource` は見ていない。フィルタリングを入れるならここが差し込み点 |
| App Drawer 長押し時の副作用 | `BubbleTextView.startLongPressAction()`（`BubbleTextView.java:1545-1547`）が `PopupContainerWithArrow.showForIcon()` を呼ぶため、**現状は App Drawer 長押しでも通常のショートカット popup メニューが同時に出る**。これは「長押しで即 drag/edit 状態に入る」という目標 UX と衝突する可能性がある。抑制するか共存させるかは仕様判断が必要 |

再整理した実装スコープ:

- 新規に必要なのは実質2点のみ。
  1. `Add to Home screen` 用の小さな `ButtonDropTarget` サブクラスを新設し、`drop_target_bar.xml` に3つ目の子として追加する（通常 `GONE`）。中身は `Workspace.onDropExternal` / `ItemInflater.inflateItem` の配置ロジックを呼び出すだけでよい。
  2. `dragObject.dragSource` が App Drawer（`ActivityAllAppsContainerView` / `launcher.getAppsView()`）由来かどうかで、表示するボタンを `Uninstall` + `Add to Home screen` に絞るフィルタリングを `ButtonDropTarget` 系に追加する。Workspace 由来の drag の既存表示（Remove/Cancel + Uninstall）は変更しない。
- `DragController` / `Workspace.beginDragShared` / `DropTargetBar` / `SecondaryDropTarget` はすべて無改造で再利用できる。

### 12.4b 仕様判断: ショートカット popup との共存 (決定済み, 2026-07-04)

App Drawer 長押し時に出る既存のショートカット popup（`PopupContainerWithArrow.showForIcon()`）は、この機能のために抑制せず**共存させる**。

- 長押し → 既存の popup（アプリ情報などの shortcut 一覧）が表示される。
- 同時に drag も開始され、上部 drop target bar に `Uninstall` / `Add to Home screen` が表示される。
- popup を閉じてそのまま drag を続ける、または popup 経由の操作を選ぶ、のどちらも既存動作のまま許容する。
- 今回の実装では `BubbleTextView.startLongPressAction()` / `PopupContainerWithArrow` 周りは変更しない。

### 12.5 Phase 1: App Drawer long press から既存 drag を開始する（調査の結果: 実質実装済み、要実機確認のみ）

目的:
App Drawer 内のアプリアイコン長押しで、既存 `DragController` ベースの drag を開始できるようにする。

実装方針:

- App Drawer item の long press handler を特定する。
- 対象 item の `AppInfo` / `ItemInfo` を取り出す。
- 既存の All Apps drag 開始処理がある場合はそれを使う。
- 既存処理が途中までしか使えない場合でも、`DragController` に渡す drag object / drag source / drag options は既存構造に合わせる。
- 独自の touch tracking や drag shadow 実装は作らない。

やらないこと:

- App Drawer の adapter / recycler 全体の大規模置換。
- 独自 drag system の新設。
- App Drawer item model の新設。

受け入れ条件:

- App Drawer 内のアプリアイコン長押しで drag が始まる。
- drag 開始後も App Drawer がクラッシュしない。
- App Drawer の通常タップ起動は壊れていない。
- App Drawer のスクロールと検索が壊れていない。

### 12.6 Phase 2: App Drawer drag 中だけ top drop target を2つに制限する

目的:
App Drawer 由来の drag では、上部 drop target area に `Uninstall` と `Add to Home screen` だけを表示する。

実装方針:

- `DropTargetBar` / `ButtonDropTarget` の既存表示制御を調査する。
- drag source または drag object から、App Drawer 由来 drag か判定する。
- App Drawer 由来 drag の場合だけ、表示 target を次の2つに限定する。

```text
Uninstall
Add to Home screen
```

- `Uninstall` は既存 target を再利用する。
- `Uninstall` 不可アプリでは、既存挙動に従い hidden / disabled にする。
- ホーム画面由来 drag の既存 target 表示は変えない。

やらないこと:

- ホーム画面 drag の drop target 構成変更。
- App info / Remove / 透明化など、今回の UX に含まれない target の追加。

受け入れ条件:

- App Drawer 由来 drag では上部 target が `Uninstall` と `Add to Home screen` のみになる。
- ホーム画面 drag では既存 target 表示が維持される。
- uninstall 不可アプリの `Uninstall` target は既存条件通りに非表示または disabled になる。

### 12.7 Phase 3: `Add to Home screen` DropTarget を実装または再利用する

目的:
App Drawer から drag した app を `Add to Home screen` へ drop すると、現在の Workspace / Home 画面に shortcut を追加する。

実装方針:

- 既存の All Apps -> Workspace 追加処理を最優先で探す。
- 既存の Workspace drop logic を呼び出せる場合は、それを再利用する。
- 新規 `DropTarget` が必要な場合でも、`ButtonDropTarget` 派生または既存 target と同じ構造に寄せる。
- drop された `AppInfo` から `WorkspaceItemInfo` または同等の shortcut item を作る。
- 配置先は、既存 Workspace placement logic に任せる。
- 追加先候補は「現在の Home / Workspace screen」を優先する。
- 空きセルがない、Workspace が受け入れ不可、profile 制約などの場合はクラッシュせず graceful に失敗する。

検討事項:

- App Drawer 表示中に `current home screen` をどう解釈するか。
- Widget Blank / Habit / ToDo 表示状態と Workspace の current page が食い違う場合の扱い。
- Home 以外に追加される可能性がある場合、仕様として Home に戻してから追加するか、現在の Workspace page に追加するか。

暫定判断:

- 初期実装では、既存 Workspace の current page / default placement logic を優先して使う。
- Morrowa 論理ページ側の `Habit` / `ToDo` 固定画面には app shortcut を追加しない。
- 追加に失敗した場合は toast / log など既存の失敗表現に寄せ、クラッシュさせない。

受け入れ条件:

- App Drawer から `Add to Home screen` へ drop すると Home / Workspace に shortcut が追加される。
- 追加した shortcut からアプリを起動できる。
- 空きセルがない場合でもクラッシュしない。
- work profile / private profile / cloned profile / multi-user app の `UserHandle` が失われない。

### 12.8 Phase 4: `Uninstall` target を既存挙動に接続する

目的:
App Drawer から drag した app を `Uninstall` へ drop したとき、既存の uninstall flow を使う。

実装方針:

- 既存の `UninstallDropTarget` または同等 class を探す。
- `supportsDrop()` / `acceptDrop()` / `onDrop()` 相当の条件判定をそのまま使う。
- system app、policy 制限、profile 制限などで uninstall 不可の場合は既存判定に従う。
- App Drawer 由来 drag でも `AppInfo` / `ItemInfo` が既存 uninstall target の期待型に合うようにする。

やらないこと:

- uninstall 判定の独自実装。
- profile / device policy 周辺の条件を独自に分岐すること。

受け入れ条件:

- uninstall 可能アプリを drop すると既存 uninstall flow が開く。
- uninstall 不可アプリでは target が出ない、または disabled になる。
- work profile / private profile で既存制約を壊さない。

### 12.9 Phase 5: drag cancel / end の状態復元

目的:
drag のキャンセル、drop 成功、drop 失敗後に App Drawer / Launcher UI が通常状態へ戻るようにする。

確認するケース:

- target 以外へ drag して指を離す。
- Back / Home / gesture で drag が中断される。
- App Drawer 検索中に long press -> drag -> cancel する。
- スクロール中または prediction 表示中に drag する。
- drop target 表示中に Launcher state が変わる。

実装方針:

- `DragController` の drag end / cancel callback を既存通り使う。
- App Drawer 側に一時状態を追加する場合は、drag end で必ず clear する。
- DropTargetBar の表示状態は既存 lifecycle に合わせる。
- App Drawer の scroll position / search query / predictions は保持する。

受け入れ条件:

- drag cancel 後に App Drawer が通常操作へ戻る。
- 検索状態やスクロール状態が不必要に壊れない。
- target 表示が残留しない。
- 連続して long press / cancel してもクラッシュしない。

### 12.10 Phase 6: 回帰確認とビルド検証

目的:
App Drawer drag 追加によって既存の Launcher 機能を壊していないことを確認する。

最低限の検証:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugKotlin compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

可能なら追加で確認:

```powershell
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat installLawnWithQuickstepGithubDebug --console=plain
```

実機確認項目:

- App Drawer のアプリアイコン長押しで drag / edit 状態に入る。
- 上部に `Uninstall` と `Add to Home screen` だけが出る。
- `Uninstall` drop で既存 uninstall flow が起動する。
- uninstall 不可アプリで `Uninstall` が非表示または disabled になる。
- `Add to Home screen` drop で Home / Workspace に shortcut が追加される。
- 追加された shortcut からアプリを起動できる。
- drag cancel で App Drawer に戻る。
- App Drawer の検索が壊れていない。
- App Drawer のスクロールが壊れていない。
- prediction / folder / profile 表示が壊れていない。
- ホーム画面アイコン drag/drop の既存挙動が壊れていない。

### 12.11 Codex に渡す最初のタスク

```text
目的:
App Drawer / All Apps 内のアプリアイコン長押しから、既存 DragController ベースの drag/edit 状態に入るための差し込み点を調査する。

対象:
ホーム画面アイコン drag flow、DropTargetBar / ButtonDropTarget、Uninstall target、App Drawer item long press、All Apps から Workspace へ app shortcut を追加する既存経路。

読むべきファイル:
DragController、DropTargetBar、ButtonDropTarget、UninstallDropTarget または同等 class、Workspace、PagedView、ActivityAllAppsContainerView、BaseAllAppsAdapter、AllAppsRecyclerView、App Drawer item view classes、Launcher / LawnchairLauncher、ItemInfo / AppInfo / WorkspaceItemInfo 周辺を rg で調査する。

実装内容:
このタスクでは大きなコード変更をしない。まず、以下を短くまとめる。
- ホーム画面 drag で top drop targets を表示する class / method
- App Drawer item long press の入口 class / method
- Add to Home screen target が既存 Workspace drop logic を再利用できるか
- 小さな新規 DropTarget が必要か
- 触るべきファイルと触らない方がよいファイル

やらないこと:
独自 drag system の新設、All Apps adapter の大規模置換、ホーム画面 drag behavior の変更、profile / multi-user 判定の独自実装。

受け入れ条件:
実装前調査として、差し込み方針、対象 class / method、主なリスク、最小実装案が docs に追記されている。
```

### 12.12 実装時の注意点

- App Drawer 由来 drag とホーム画面由来 drag を明確に区別する。
- ただし区別のために新しい item model を作らず、既存 `AppInfo` / `ItemInfo` / `WorkspaceItemInfo` の流れへ寄せる。
- `Add to Home screen` は便利機能だが、Workspace 配置は複雑なので、既存 placement logic を必ず優先する。
- `Uninstall` は security / policy / profile の条件を含むため、既存 target 判定を再利用する。
- 既存 App Drawer の search / prediction / scroll / profile handling は MVP の生命線ではないが、ランチャー基本機能なので壊さない。
- 変更が `DropTargetBar` や `DragController` に及ぶ場合は、ホーム画面 drag の回帰確認を必ず行う。

### 12.13 完了条件

- App Drawer 内のアプリアイコン長押しで drag/edit 状態に入る。
- 上部 drop target area に `Uninstall` と `Add to Home screen` の2つだけが出る。
- `Uninstall` drop が既存 uninstall flow に接続されている。
- `Add to Home screen` drop で Workspace に app shortcut が追加される。
- drag cancel / drop 失敗時にクラッシュせず通常状態へ戻る。
- 既存ホーム画面 drag/drop が維持されている。
- App Drawer の検索、スクロール、prediction、profile handling が壊れていない。
- Kotlin / Java compile が通る。
- 無関係な整形、大規模 refactor、仕様外 target の追加がない。

---

## 13. 2026-07-04 実装内容: `Add to Home screen` drop target 追加

### 13.1 背景

§12.4a の事前調査の結果、App Drawer 長押し → drag 開始と、All Apps -> Workspace 配置は既に実装済みであることが判明した。新規実装が必要なのは次の2点のみに絞られた。

1. `Add to Home screen` 用の小さな `ButtonDropTarget` サブクラス。
2. drag の出所（Workspace か App Drawer か）で表示ボタンを絞るフィルタリング。

ショートカット popup との共存については §12.4b の通り「共存させる」で決定済みのため、`BubbleTextView` / `PopupContainerWithArrow` 側は変更していない。

### 13.2 実装内容

変更 / 追加したファイル:

| ファイル | 内容 |
|---|---|
| `src/com/android/launcher3/AddToHomescreenDropTarget.java`（新規） | App Drawer 由来 drag のときだけ表示される `ButtonDropTarget`。`LauncherAccessibilityDelegate#addToWorkspace()` を呼び出して配置する |
| `src/com/android/launcher3/DeleteDropTarget.java` | `dragObject.dragSource` が App Drawer（`ActivityAllAppsContainerView`）由来なら `supportsDrop()` が `false` を返し、Remove / Cancel を非表示にする |
| `res/layout/drop_target_bar.xml` | `AddToHomescreenDropTarget` を3つ目の子として追加（初期 `visibility="gone"`） |
| `res/drawable/ic_add_no_shadow.xml`（新規） | 既存の `ic_remove_no_shadow.xml` / `ic_uninstall_no_shadow.xml` と同じスタイル（20dp、`tint` 属性、白 fill）の "+" アイコン |

文字列は新規追加していない。既存の `R.string.action_add_to_workspace`（"Add to home screen" / 日本語 "ホーム画面に追加"、全ロケール翻訳済み）をそのまま再利用した。

### 13.3 実装方針の詳細

- **配置ロジックの再利用**: `Workspace.onDropExternal()` ではなく、`LauncherAccessibilityDelegate.addToWorkspace(ItemInfo, boolean, Consumer<Boolean>)` を再利用した。この method は次の既存箇所で全く同じ用途に使われている。
  - `LauncherAccessibilityDelegate` 自身の `ADD_TO_WORKSPACE` accessibility action（All Apps item のアクセシビリティ「ホーム画面に追加」）
  - `BaseWidgetSheet.addWidget()` の「タップして追加」ボタン（`Launcher.getLauncher(context).getAccessibilityDelegate().addToWorkspace(...)`）
  - `onDropExternal` は touch 座標ベースの配置が前提のため、座標を持たないボタンドロップには `addToWorkspace` の方が適合していた。
- **drag source の判定**: `DropTarget.DragObject.dragSource instanceof ActivityAllAppsContainerView` で判定する。`LauncherDragController.startDrag()` が `dragSource` に `Launcher.getAppsView()` を設定済みのため、追加のフィールドや item model は不要だった。
- **フィルタリングの実装場所**: `ButtonDropTarget`（抽象基底クラス）は変更せず、`AddToHomescreenDropTarget.supportsDrop()` と `DeleteDropTarget.supportsDrop()` それぞれに `mIsAppDrawerDrag` フィールドを持たせた。`onDragStart()` で `dragObject.dragSource` から判定して保持し、`supportsDrop(ItemInfo)` から参照する。`ButtonDropTarget` / `DropTargetBar` / `SecondaryDropTarget`（Uninstall）は無改造。
- **`DropTargetBar` の可視ボタン数**: `onMeasure` / `onLayout` は「同時に見えるボタンは1個または2個」を前提にしている。`DeleteDropTarget` と `AddToHomescreenDropTarget` の表示条件（`mIsAppDrawerDrag` の真偽）は互いに排他的なため、Workspace 由来 drag では従来通り Remove/Cancel + Uninstall の最大2つ、App Drawer 由来 drag では Uninstall + Add to Home screen の最大2つに収まり、3つ同時表示にはならない。
- **アクセシビリティ**: `AddToHomescreenDropTarget` はタッチ drag 専用とし、`getSupportedAccessibilityAction()` は常に `INVALID` を返す。All Apps item は既存の `ADD_TO_WORKSPACE` accessibility action で同等の機能を別経路から提供済みのため、二重実装していない。

### 13.4 やらないことの確認

- 独自 drag system は新設していない（既存 `DragController` / `Workspace.beginDragShared` / `LauncherDragController` をそのまま利用）。
- `ButtonDropTarget` 基底クラス、`DropTargetBar`、`SecondaryDropTarget` は無改造。
- `BubbleTextView.startLongPressAction()` / `PopupContainerWithArrow` は変更していない（ポップアップ共存の決定通り）。
- Home 画面由来 drag の既存 target 表示・挙動は変更していない。

### 13.5 検証状況

実行:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat compileLawnWithQuickstepGithubDebugJavaWithJavac --console=plain
```

結果:

- `BUILD SUCCESSFUL in 2m 19s`（`compileLawnWithQuickstepGithubDebugKotlin` も依存タスクとして実行され、両方成功）
- 新規 / 変更ファイルに起因するコンパイルエラーなし
- 出た warning はすべて本変更と無関係な既存コード由来（`DesktopModeStatus` deprecated 使用など）

未実施（次回以降）:

- 実機 install（`installLawnWithQuickstepGithubDebug`）。
- 実機での動作確認（App Drawer 長押し → drag → 上部に Uninstall / Add to Home screen が2つだけ出る、Add to Home screen で Workspace に配置される、Uninstall が既存 flow を開く、drag cancel で通常状態に戻る、ホーム画面アイコン drag の既存表示が変わっていない、検索 / スクロール / フォルダ / prediction / profile 表示が壊れていない）。
