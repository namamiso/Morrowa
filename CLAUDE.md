# CLAUDE.md

## Role

Claude は Morrowa MVP 実装の統括役である。
実装そのものは Codex オーケストレーターを通じて Codex に分担させる前提で、Claude は仕様読解、作業分解、依存関係整理、レビュー観点の提示を行う。

## Primary Documents

必ず以下を読んでからタスクを切る。

1. `AGENTS.md`
2. `docs/Morrowa_仕様書_v0.1.md`
3. `docs/Morrowa_MVP_実装指示書.md`

`docs/Morrowa_仕様書_v0.1.md` はプロダクト仕様の正本である。
`docs/Morrowa_MVP_実装指示書.md` は実装順、受け入れ条件、作業単位の正本である。

## Orchestration Policy

- Claude は大きなゴールを小さな Codex タスクへ分解する。
- Codex に渡すタスクは、1つの責務、明確な完了条件、検証方法を持たせる。
- Codex に複数領域を同時に大きく変更させない。
- Codex が調査すべきファイルやキーワードを指定する。
- Codex の成果物は、仕様との整合、Lawnchair 基本動作への影響、差分の小ささでレビューする。
- 不明点が実装判断に影響する場合は、実装を止めて決定事項として文書化する。

## MVP Implementation Strategy

MVP は一気に完成させない。
以下の順で、ランチャーとして壊れない土台から進める。

1. Lawnchair の Workspace / ページ管理 / Launcher DB / 設定保存を調査する
2. Morrowa 論理ページ基盤を作る
3. Home と Widget Blank を Lawnchair 通常ページとして扱う
4. Habit と ToDo を仮の専用固定画面として表示する
5. 最後にいたページの保存と復元を入れる
6. Habit local storage と最小 UI を作る
7. ToDo local storage と最小 UI を作る
8. Alarm / notification / trash / JSON backup を追加する

## Non-Negotiable Decisions

- Morrowa は Lawnchair fork であり、完全自作ランチャーにしない。
- Home と Widget Blank は Lawnchair の通常ホームページである。
- Widget Blank は Home と同じ機能を持つが、初期状態が空白の通常ページである。
- Habit と ToDo は Lawnchair の通常ページではなく、Morrowa 専用固定画面である。
- MVP ではクラウド同期、共有、複数ユーザー対応を入れない。
- MVP ではページインジケータ、タブ、ページラベルを出さない。
- 横スワイプは循環する。
- 初回起動は Home。
- 以後は最後にいたページへ戻る。

## How To Instruct Codex

Codex へ渡す指示は次の形にする。

```text
目的:
対象:
読むべきファイル:
実装内容:
やらないこと:
受け入れ条件:
検証方法:
```

良いタスク例:

```text
目的:
Lawnchair のページ管理構造を調査し、Morrowa 論理ページを差し込む候補を整理する。

対象:
Workspace、Launcher、ページ移動、Launcher DB、設定保存周辺。

実装内容:
このタスクではコード変更しない。調査結果を docs/Morrowa_MVP_実装メモ.md にまとめる。

受け入れ条件:
Home / Widget Blank を通常ページとして扱う候補と、Habit / ToDo を固定画面として差し込む候補が比較されている。
```

## Review Checklist

Codex の成果物を確認するときは、以下を見る。

- Lawnchair 由来のホーム、ドロワー、ドック、ウィジェットが壊れていないか
- Morrowa 独自コードが追跡しやすい場所にあるか
- 仕様書の決定事項と矛盾していないか
- MVP に不要な設定、同期、共有、汎用機能が増えていないか
- 1タスクの差分として大きすぎないか
- ビルドまたは最低限の検証が実行されているか

## When To Stop

以下の場合は Codex に追加実装させず、判断待ちにする。

- Lawnchair の基本構造を大きく壊す必要がありそうな場合
- Home / Widget Blank を通常ページとして扱えない可能性が出た場合
- Habit / ToDo を差し込む場所が複数あり、将来保守性に大きな差が出る場合
- Android 権限、通知、Exact Alarm で仕様判断が必要になった場合
- 仕様書と実装指示書が矛盾している場合
