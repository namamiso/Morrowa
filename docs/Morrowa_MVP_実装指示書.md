# Morrowa MVP 実装指示書

作成日: 2026-06-12

## 1. 目的

この文書は、Morrowa MVP を Codex に実装させるための作業指示書である。
プロダクト仕様の正本は `docs/Morrowa_仕様書_v0.1.md` とし、この文書では実装順、作業単位、受け入れ条件、検証方法を定義する。

Claude はこの文書をもとに Codex へ小さなタスクを渡す。
Codex はこの文書と `AGENTS.md` に従い、Lawnchair fork の基本動作を壊さないように実装する。

## 2. MVP の最終ゴール

MVP では、Morrowa を Android ホームランチャーとして起動でき、以下の機能が成立している状態を目指す。

- Lawnchair 由来の基本ホーム機能が使える
- Home / Habit / ToDo / Widget Blank の 4 論理ページを持つ
- Home と Widget Blank は Lawnchair の通常ホームページとして使える
- Habit と ToDo は Morrowa 専用固定画面として表示できる
- 横スワイプで 4 論理ページを循環移動できる
- 初回起動は Home を表示する
- 以後は最後にいたページへ戻る
- Habit をローカル保存できる
- Habit の達成履歴を保存し、草カレンダーで確認できる
- ToDo をローカル保存できる
- ToDo / Habit にアラームを紐づけられる
- 未達習慣通知を出せる
- ToDo / Habit をゴミ箱へ移動し、30日後に完全削除できる
- JSON エクスポート / インポートで手動バックアップできる

## 3. 絶対に守る制約

- 完全自作ランチャーにしない
- Lawnchair の基本ホーム、アプリ一覧、フォルダ、ウィジェット配置を壊さない
- Morrowa は Dock と QSB を持たない
- Home 画面は検索バーと Dock を持たない
- 旧 Dock / QSB 領域は通常 Workspace グリッドに統合し、下部 2 行もアプリ・フォルダ・ウィジェット配置可能にする
- Hotseat オブジェクトは内部互換用に残すが、表示・操作対象としては使わない
- 既存 Dock アイテムは MVP では DB に残したまま非表示にし、Dock -> Workspace 下部行 migration は将来タスクにする
- Home と Widget Blank は Lawnchair の通常ホームページとして扱う
- Habit と ToDo は Lawnchair 通常ページに混ぜない
- MVP ではクラウド同期を入れない
- MVP では共有、チーム利用、複数ユーザーを入れない
- MVP では Google カレンダー同期を入れない
- MVP では内蔵カレンダーを作らない
- MVP ではページインジケータ、タブ、ページラベルを出さない
- 先に大規模なテーマ変更や見た目変更をしない
- upstream 由来コードを広範囲に書き換えない

## 4. 実装対象の分類

### 4.1 OSS 由来で残すもの

- Android 既定ホームとしての動作
- アプリ一覧
- アプリ起動
- ホーム上のアプリ配置
- フォルダ
- 外部ウィジェット配置
- 基本設定導線
- ホームからの配置削除
- Hotseat オブジェクトは内部互換用に残す

### 4.2 OSS 由来から変更するもの

- ホームページ構成を Morrowa の 4 論理ページで扱う
- 横スワイプを Home / Habit / ToDo / Widget Blank の循環移動として扱う
- 最後にいたページへ復帰する
- Home に透明アイコン機能を追加する
- 長押しメニューに透明化 / 透明解除を追加する
- 透明化済みアイコンは通常時に完全非表示、編集モード中だけ薄く表示する
- 透明化してもタップ起動、長押し、アクセシビリティ名は維持する
- ホームから削除時は Undo を出す
- Widget Blank を Home と同じ通常ページで、初期状態が空のページとして扱う
- Home は検索バーと Dock を持たない
- Dock / QSB は表示しない
- Hotseat オブジェクトは高さ 0 とし、表示・操作対象としては使わない
- 旧 Dock / QSB 領域は通常 Workspace グリッドに統合する
- 下部 2 行もアプリ・フォルダ・ウィジェット配置可能にする
- MVP では Hotseat カスタマイズ設定を表示しない
- 既存 Dock アイテムは DB に残したまま非表示にする
- Dock -> Workspace 下部行 migration は将来タスクにする
- Widget Blank ではショートカット配置も許可する

### 4.3 Morrowa で追加するもの

- Morrowa 論理ページ状態
- Habit 専用画面
- ToDo 専用画面
- Habit local storage
- Habit rule history
- Habit completion history
- Habit grass calendar
- ToDo local storage
- ToDo / Habit alarm linkage
- Incomplete habit notification
- Trash
- Manual JSON export / import

## 5. ページ基盤仕様

### 5.1 論理ページ

```text
Home -> Habit -> ToDo -> Widget Blank -> Home
```

逆方向:

```text
Home -> Widget Blank -> ToDo -> Habit -> Home
```

### 5.2 ページ実体

| 論理ページ | 実体 | 配置可否 |
|---|---|---|
| Home | Lawnchair 通常ページ | アプリ、フォルダ、ウィジェットを置ける |
| Habit | Morrowa 専用固定画面 | アプリ、フォルダ、ウィジェットは置けない |
| ToDo | Morrowa 専用固定画面 | アプリ、フォルダ、ウィジェットは置けない |
| Widget Blank | Lawnchair 通常ページ | アプリ、フォルダ、ウィジェットを置ける |

### 5.3 ページ移動

- 横スワイプで移動する
- 左右どちらも循環する
- 左右端は存在しない
- ページインジケータは表示しない
- タブは表示しない
- 画面端タップ移動は採用しない

### 5.4 復帰

- 初回起動は Home
- `last_page_type` がない場合は Home
- ホーム復帰時は最後にいたページへ戻る
- ロック解除後は最後にいたページへ戻る
- アプリから戻ると最後にいたページへ戻る

保存候補:

```text
launcher_state
- last_page_type
- updated_at
```

`last_page_type`:

```text
HOME
HABIT
TODO
WIDGET_BLANK
```

## 6. 実装フェーズ

### Phase 0: 調査

目的:
Morrowa のページ基盤をどこへ差し込むか決める。

調査対象:

- Launcher 起動フロー
- Workspace
- ページ移動
- ホームページ保存
- Launcher DB
- Widget 配置
- 設定保存
- Compose / View の使い分け

成果物:

- 調査メモ
- 差し込み候補
- 推奨実装方針
- 触るべきファイル一覧
- 触らない方がよいファイル一覧

受け入れ条件:

- Home / Widget Blank を Lawnchair 通常ページとして扱う方法が説明されている
- Habit / ToDo を Morrowa 専用固定画面として表示する候補が説明されている
- Launcher DB を拡張する必要があるかどうかが説明されている
- 既存ホーム機能へのリスクが書かれている

### Phase 1: Morrowa 論理ページ基盤

目的:
4 論理ページの状態と循環移動を成立させる。

実装内容:

- Morrowa 論理ページ enum を作る
- 現在の論理ページ状態を持つ
- 横スワイプで次ページ / 前ページへ移動する
- Home / Habit / ToDo / Widget Blank を循環させる
- Habit / ToDo は仮の空画面でよい
- ページインジケータとタブは出さない

受け入れ条件:

- Home -> Habit -> ToDo -> Widget Blank -> Home と移動できる
- Home -> Widget Blank -> ToDo -> Habit -> Home と移動できる
- Habit / ToDo ではアプリ、フォルダ、ウィジェットを配置できない
- Home / Widget Blank では通常ページとして配置できる
- 既存のアプリ起動、アプリ一覧が壊れていない
- Home / Widget Blank に Dock と QSB が表示されない
- Home / Widget Blank の下部 2 行にアプリ、フォルダ、ウィジェットを配置できる

### Phase 2: 最後にいたページの保存

目的:
最後にいた Morrowa 論理ページへ戻れるようにする。

実装内容:

- `last_page_type` を保存する
- 初回起動時は Home を使う
- 保存済み値が不正な場合は Home へフォールバックする
- ホーム復帰、ロック解除、アプリ復帰で最後のページへ戻る

受け入れ条件:

- 初回起動は Home
- Habit でアプリへ移動して戻ると Habit に戻る
- ToDo で画面オフ、ロック解除後に ToDo に戻る
- 保存値が壊れてもクラッシュせず Home に戻る

### Phase 3: Habit 最小実装

目的:
Habit をローカル保存し、専用画面で表示、追加、編集、チェックできるようにする。

実装内容:

- Habit データモデル
- Habit local storage
- Habit 追加
- Habit 編集
- Habit 入力バリデーション
- Habit 手動並び
- Habit タップで選択日のチェック ON / OFF
- Habit 長押しメニュー
- Habit アーカイブ確認
- Habit 削除確認
- Habit アーカイブ
- Habit ゴミ箱移動
- 1日1回チェック
- 4:00 JST habit day 判定
- habit_rule_history
- habit_completions
- 草カレンダーの最小表示
- Habit frequency rules

受け入れ条件:

- Habit を追加できる
- Habit を編集できる
- Habit 作成時の必須項目は名前と頻度ルール
- Habit の名前とメモはどちらも100文字まで
- Habit のメモは Markdown を許可する
- Habit のアラーム設定は任意で、作成後に設定してもよい
- Habit の変更は保存ボタンを押すまで確定しない
- Habit をチェックできる
- Habit をタップすると選択日のチェック ON / OFF が切り替わる
- Habit 編集は長押しメニューから開く
- Habit の長押しメニューは `編集` / `アラーム設定` / `アーカイブ` / `削除`
- Habit アーカイブは長押しメニューから `アーカイブ` を選び、確認メニューで確定した場合のみ実行する
- Habit 削除は長押しメニューから `削除` を選び、確認メニューで確定した場合のみゴミ箱へ移動する
- Habit のスワイプ操作は実装されていない
- Habit 削除 / アーカイブ時の Undo は表示されない
- アーカイブした Habit は通常一覧には出ず、履歴と過去日の草カレンダーには残る
- Habit の並び順は完全手動である
- 同一 habit day で二重チェックにならない
- 4:00 境界で habit day が切り替わる
- Habit の頻度ルールは `毎日` / `毎週の曜日指定` / `毎月の日付指定`
- 毎週ルールでは複数曜日を選択できる
- 毎月ルールでは複数日を選択できる
- 31日指定は、31日がない月ではその月だけ対象外になる
- 草カレンダーは年単位表示で、週始まりは日曜日
- 未来日は選択不可、チェック不可
- 過去日はチェック ON / OFF 編集可能
- 過去日の達成状態を草カレンダーに反映できる

### Phase 4: ToDo 最小実装

目的:
ToDo をローカル保存し、専用画面で表示、追加、編集、削除できるようにする。

実装内容:

- ToDo データモデル
- ToDo local storage
- ToDo 追加
- ToDo 編集
- ToDo 入力バリデーション
- ToDo 手動並び
- 任意の予定日
- タップで詳細編集
- 長押しメニュー
- 削除確認
- ゴミ箱移動
- 完了状態は持たない

受け入れ条件:

- ToDo を追加できる
- ToDo を編集できる
- ToDo 作成時の必須項目はタイトルのみ
- ToDo のタイトルとメモはどちらも100文字まで
- ToDo のメモは Markdown を許可する
- ToDo のアラーム設定は任意で、作成後に設定してもよい
- ToDo の変更は保存ボタンを押すまで確定しない
- ToDo を手動並び替えできる
- ToDo の予定日は任意で、未設定でも作成できる
- 予定日による自動並び替えは行われない
- 予定日を過ぎた ToDo は削除されず、表示上だけ少し強調される
- ToDo をタップすると詳細編集を開く
- ToDo の長押しメニューは `編集` / `アラーム設定` / `削除`
- ToDo 削除は長押しメニューから `削除` を選び、確認メニューで確定した場合のみゴミ箱へ移動する
- ToDo のスワイプ削除は実装されていない
- ToDo 削除時の Undo は表示されない
- 完了済み一覧や完了履歴は作られていない

### Phase 5: アラーム / 通知

目的:
ToDo / Habit にアラームを紐づけ、未達習慣通知を出せるようにする。

実装内容:

- AlarmRule
- AlarmSchedule
- ToDo alarm linkage
- Habit alarm linkage
- 未達習慣通知
- 通常通知
- 通知タップ時の対象画面遷移
- 端末再起動後の再設定
- Android 権限確認

受け入れ条件:

- ToDo に任意時刻のアラームを設定できる
- Habit に任意時刻のアラームを設定できる
- ToDo / Habit のアラームは MVP では通常通知として表示される
- ToDo アラーム通知をタップすると ToDo 画面へ直接遷移する
- Habit アラーム通知をタップすると Habit 画面へ直接遷移する
- 未達習慣がある場合に通知できる
- 未達習慣通知のデフォルト時刻は 20:00
- 未達習慣通知は 1日1回
- 未達習慣通知をタップすると Habit 画面へ直接遷移する
- MVP では独自アラーム音を持たず、Android 標準の通知音に任せる
- MVP ではスヌーズは実装されていない
- 端末再起動後に保存済みアラームを再登録できる
- exact alarm 権限が必要な場合に破綻しない

### Phase 6: Trash

目的:
削除した ToDo / Habit をゴミ箱で管理する。

実装内容:

- ゴミ箱一覧
- 復元
- 完全削除確認
- 完全削除
- 30日後自動削除

受け入れ条件:

- 削除した ToDo / Habit がゴミ箱に入る
- ゴミ箱から復元できる
- ToDo は復元すると ToDo 一覧の末尾に戻る
- Habit は復元すると Habit 一覧の末尾に戻る
- 完全削除は確認メニューで確定した場合のみ実行される
- 完全削除後の Undo は表示されない
- 30日経過した項目を削除対象にできる
- ゴミ箱の複数選択削除は実装されていない

### Phase 7: JSON Export / Import

目的:
ローカルデータを手動でバックアップ、復元できるようにする。

実装内容:

- JSON export
- JSON import
- schema_version
- exported_at
- 全置換 import
- import 前確認
- ToDo / Habit 関連データのみを対象にする
- Home / Widget Blank 配置情報を対象外にする
- 透明アイコン状態を対象外にする

受け入れ条件:

- ToDo / Habit / habit rule history / habit completions / alarm / trash を export できる
- export した JSON を import できる
- import は全置換である
- import 前に「現在のデータは置き換わります」の確認が出る
- merge import は実装されていない
- Home / Widget Blank のアプリ、フォルダ、ウィジェット配置は export / import されない
- 透明アイコン状態は export / import されない

## 7. MVP で実装しないこと

- Cloud sync
- Google Calendar sync
- 内蔵カレンダー
- 共有機能
- 複数ユーザー
- タグ
- サブタスク
- ToDo 完了履歴
- 高度な統計画面
- ページインジケータ
- タブ UI
- JSON merge import
- Dynamic color 対応
- 大規模テーマ変更
- 細かいカスタマイズ設定の追加

## 8. Codex タスクの標準フォーマット

Claude は Codex に作業を渡すとき、以下の形式を使う。

```text
目的:

対象:

読むべきファイル:

実装内容:

やらないこと:

受け入れ条件:

検証方法:
```

## 9. 最初に Codex へ渡すべきタスク

```text
目的:
Morrowa 論理ページ基盤を入れる前に、Lawnchair のページ管理、Workspace、Launcher DB、設定保存の構造を調査する。

対象:
Launcher 起動フロー、Workspace、ページ移動、ホームページ保存、Widget 配置、設定保存。

読むべきファイル:
まず rg で Workspace、Launcher、LauncherProvider、Favorites、screenId、page、widget、DataStore、SharedPreferences 相当を探す。

実装内容:
このタスクではコード変更しない。調査結果を `docs/Morrowa_MVP_実装メモ.md` にまとめる。

やらないこと:
Habit / ToDo の UI 実装、DB 実装、ページ基盤の実装はまだ行わない。

受け入れ条件:
Home / Widget Blank を Lawnchair 通常ページとして扱う方法の候補がある。
Habit / ToDo を専用固定画面として差し込む候補がある。
Launcher DB を変更する必要性が判断されている。
リスクと推奨方針が書かれている。

検証方法:
コード検索結果、該当ファイル、推奨差し込み点を文書に記録する。
```

## 10. 実装完了時の共通検証

可能な範囲で以下を確認する。

- Gradle build が通る
- Android に install できる
- 既定ホームとして起動できる
- アプリ一覧を開ける
- アプリを起動できる
- ホームへ戻れる
- Dock と QSB が表示されない
- 旧 Dock / QSB 領域の下部 2 行にアプリ、フォルダ、ウィジェットを配置できる
- 外部ウィジェット配置が壊れていない
- Home / Widget Blank の通常ページ動作が維持されている
- Habit / ToDo で配置操作が混入していない
- クラッシュログがない

## 11. 仕様判断が必要な未確定事項

以下は実装中に調査または判断が必要な項目である。

- Habit / ToDo 専用画面をどのレイヤーに差し込むか
- 透明アイコン状態を Lawnchair DB に持たせる具体的方法
- Lawnchair DB 拡張が危険な場合の Morrowa 側 fallback 保存方式
- exact alarm 権限方針
- exact alarm が使えない場合の代替実装

## 12. 決定済み事項

以下は決定済みであり、実装時に再判断しない。

- Home / Widget Blank の配置 DB は Lawnchair 標準 DB を使う
- Morrowa 独自 DB には Home / Widget Blank のアプリ、フォルダ、ウィジェット配置を持たない
- Widget Blank は Lawnchair 通常ホームページの2枚目として初期生成する
- Morrowa の `last_page_type` は Launcher DB ではなく DataStore / SharedPreferences 相当の軽量設定に保存する
- 透明アイコン状態は原則 Lawnchair DB 側に持たせる。ただし調査で危険なら Morrowa 側に退避する
- 透明化対象は Workspace 上のアプリアイコン、フォルダ
- ウィジェットと Habit / ToDo 項目は透明化対象外
- Morrowa は Dock と QSB を持たない
- Home 画面は検索バーと Dock を持たない
- Hotseat オブジェクトは内部互換用に残すが、高さ 0 とし、表示・操作対象としては使わない
- 旧 Dock / QSB 領域は通常 Workspace グリッドに統合し、下部 2 行もアプリ・フォルダ・ウィジェット配置可能にする
- MVP では Hotseat カスタマイズ設定を表示しない
- 既存 Dock アイテムは MVP では DB に残したまま非表示。Dock -> Workspace 下部行 migration は将来タスク
- 透明化済みアイコンは通常時に完全非表示、編集モード中だけ薄く表示する
- 透明化してもタップ起動は有効
- 透明化済みアイコンの長押しメニューは `透明解除` と `ホームから削除`
- ホームから削除時は Undo を出す
- ToDo はタップで詳細編集を開く
- ToDo の予定日は任意
- ToDo の並び順は完全手動で、予定日による自動並び替えはしない
- 予定日を過ぎた ToDo は削除せず、表示上だけ少し強調する
- ToDo は完了状態を持たず、終わったら削除してゴミ箱へ移動する
- ToDo 削除は長押しメニューから `削除` を選び、確認メニューで確定した場合のみ実行する
- ToDo の長押しメニューは `編集` / `アラーム設定` / `削除`
- ToDo のスワイプ削除と削除時 Undo は採用しない
- Habit はタップで選択日のチェック ON / OFF を切り替える
- Habit 編集は長押しメニューから開く
- Habit の長押しメニューは `編集` / `アラーム設定` / `アーカイブ` / `削除`
- Habit アーカイブは確認メニューで確定した場合のみ実行する
- Habit 削除は確認メニューで確定した場合のみゴミ箱へ移動する
- Habit のスワイプ操作と削除 / アーカイブ時 Undo は採用しない
- アーカイブした Habit は通常一覧には出さず、履歴と過去日の草カレンダーには残す
- Habit の並び順は完全手動
- Habit の頻度ルールは `毎日` / `毎週の曜日指定` / `毎月の日付指定`
- 毎週ルールでは複数曜日を選択できる
- 毎月ルールでは複数日を選択できる
- 31日指定は、31日がない月ではその月だけ対象外
- 草カレンダーは年単位表示で、週始まりは日曜日
- 未来日は選択不可、チェック不可
- 過去日はチェック ON / OFF 編集可能
- ToDo のタイトル・メモはどちらも100文字まで
- Habit の名前・メモはどちらも100文字まで
- ToDo / Habit のメモは Markdown を許可する
- ToDo 作成時の必須項目はタイトルのみ
- Habit 作成時の必須項目は名前と頻度ルール
- ToDo / Habit のアラーム設定は任意で、作成後に設定してもよい
- ToDo / Habit の変更は保存ボタンを押すまで確定しない
- Home は検索バーと Dock を持たない
- Dock / QSB は表示しない
- Widget Blank ではショートカット配置も許可する
- 設定画面は MVP では最小限にし、細かいカスタマイズ項目を増やさない
- Dynamic Color やテーマ変更は MVP では採用しない
- 高度な統計画面は作らず、Habit の草カレンダーだけにする
- ToDo / Habit のアラームは MVP では通常通知から始める
- ToDo アラーム通知は ToDo 画面へ、Habit アラーム通知は Habit 画面へ直接遷移する
- アラーム音は独自音を持たず、Android 標準の通知音に任せる
- スヌーズは MVP では採用しない
- 端末再起動後は保存済みアラームを再登録する
- 未達習慣通知は 20:00 の 1日1回
- 未達習慣通知タップ時は Habit 画面へ直接遷移する
- ToDo / Habit をゴミ箱から復元した場合は各一覧の末尾に戻す
- ゴミ箱内の完全削除は確認メニューを挟む
- 完全削除後の Undo は表示しない
- 30日経過したゴミ箱項目は自動削除対象にする
- ゴミ箱の複数選択削除は MVP では採用しない
- JSON export / import は ToDo / Habit 関連データのみを対象にする
- Home / Widget Blank の配置情報と透明アイコン状態は JSON export / import 対象外
- JSON import は MVP では全置換のみ
- JSON import 前には現在データが置き換わる確認を必ず出す
- JSON merge import は MVP では採用しない
