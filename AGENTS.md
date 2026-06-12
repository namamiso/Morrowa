# AGENTS.md

## Purpose

このリポジトリは、Lawnchair fork をベースにした Android パーソナルランチャー `Morrowa` を開発するための作業場所である。
この文書は、Codex などの実装エージェントが守る共通前提を定義する。

## Project Summary

- プロダクトは Android ホームランチャー
- ベースは Lawnchair fork
- 中核価値は ToDo、Habit、Alarm をホーム画面へ統合すること
- クラウド同期は行わず、完全ローカルで完結させる
- 目的は「アプリを開く場所」ではなく「今日を動かす場所」を作ること
- 実装の主戦場はランチャー基盤の再発明ではなく、Morrowa 独自の生活導線である

## Current Phase

現在は MVP 実装準備から初期実装へ移る段階である。
Lawnchair fork のコードベースは配置済みであり、まず既存構造を理解してから小さく実装する。

## Source Of Truth

- 正本仕様: `docs/Morrowa_仕様書_v0.1.md`
- MVP 実装指示: `docs/Morrowa_MVP_実装指示書.md`
- Claude 統括方針: `CLAUDE.md`
- この `AGENTS.md` は実装エージェント向けの共通ルールであり、詳細仕様の完全な代替ではない。

仕様変更が必要な場合は、コードだけでなく該当ドキュメントも更新する。

## Orchestration Model

- Claude が全体方針、タスク分解、レビュー観点を管理する。
- Codex は Claude から渡された具体タスクを実装、調査、検証する実行エージェントとして動く。
- Codex は実装中に仕様の穴、危険な前提、Lawnchair 由来コードへの過剰改変を見つけたら、勝手に大きく進めず明示する。
- 1タスクは小さく保つ。複数領域を同時に大きく変更しない。

## Non-Negotiable Decisions

- 完全自作ランチャー路線には戻さない
- MVP ではクラウド、共有、複数ユーザー対応を入れない
- MVP では外部ウィジェットよりランチャー内部パネル UI を優先する
- Home と Widget Blank は Lawnchair の通常ホームページとして扱う
- Habit と ToDo は Morrowa 専用固定画面として扱う
- ベースランチャーの基本動作を壊す変更は慎重に扱う
- upstream 由来コードへの広範囲な直接改変は避ける

## MVP Page Decisions

Morrowa は 4 つの論理ページを持つ。

```text
Home -> Habit -> ToDo -> Widget Blank -> Home
```

- 初回起動は `Home`
- 横スワイプは循環する
- 逆方向にも循環する
- ページインジケータは出さない
- タブやページラベルは出さない
- アプリから戻る、ロック解除後、ホーム復帰時は最後にいたページへ戻る
- Home は Lawnchair 通常ページ
- Widget Blank は Lawnchair 通常ページで、初期状態は空白
- Habit と ToDo はアプリ、フォルダ、ウィジェットを置けない専用画面
- Home の検索バーは MVP ではコード上から完全削除する
- Widget Blank ではショートカット配置も許可する

## Engineering Principles

- まず既存の Lawnchair 構造を理解してから変更する
- Morrowa 独自機能は、可能な限り独立したパッケージや層にまとめる
- 変更時は、何が Lawnchair 由来で何が Morrowa 独自かが追跡しやすい状態を保つ
- ローカルファーストの制約を前提にデータ設計する
- 過剰な抽象化や早すぎる汎用化を避ける
- 既存のビルド、起動、ホーム動作を壊さない
- 変更は小さな差分に分け、各差分で検証可能にする

## Expected MVP Components

- Morrowa 論理ページ基盤
- Home / Widget Blank の通常 Lawnchair ページ運用
- Habit 専用画面
- ToDo 専用画面
- Habit local storage
- ToDo local storage
- Habit completion history
- Habit grass calendar
- ToDo / Habit alarm linkage
- Incomplete habit notification
- Trash
- Manual JSON export / import

## Suggested Domain Model

初期設計では、以下のエンティティを前提に検討する。

- Task または Todo
- Habit
- HabitRuleHistory
- HabitCompletion
- AlarmRule
- AlarmSchedule
- HabitReminderSetting

Task と Habit は UI では別でも、将来的な統合を見据えて近い構造を維持する。

## How To Work In This Repo

- 変更前に対象コードを読む
- `rg` で既存の責務分割、類似実装、設定保存、DB、Workspace 周辺を調べる
- 既存の Lawnchair パターンに合わせる
- Morrowa 独自コードの置き場所を明確にする
- 大きな実装前には、調査結果と差し込み方針を短くまとめる
- 実装後は最低限ビルドまたは対象テストを走らせる
- 実機インストールが必要な場合は Android CLI / Gradle / adb の既存手順に従う

## Change Priority

1. ホームランチャーとして成立すること
2. Lawnchair 由来の基本動作を壊さないこと
3. Morrowa 論理ページ基盤が成立すること
4. Task / Habit / Alarm のローカルデータが成立すること
5. ホーム上で即時操作できること
6. 操作結果がホームへ即時反映されること
7. デザインを磨くこと

## Avoid

- クラウド前提の提案
- マルチユーザー前提の提案
- MVP で不要な設定項目の追加
- ベースランチャー全体を書き換える雑なリファクタ
- Lawnchair の通常ページ機構へ Habit / ToDo を無理に混ぜる実装
- 先に Habit / ToDo の完成 UI へ飛びつくこと
- 「まず全部作り直す」系の判断

## Definition Of Done

変更は、以下を満たして初めて完了とみなす。

- このプロジェクトの要件と矛盾しない
- ランチャーとしての基本動作を不用意に壊していない
- Morrowa 独自価値に直接つながる
- ローカル完結方針を守っている
- 次の担当者が意図を追える程度に文書またはコード構造が整理されている
- 実装変更の場合、可能な範囲でビルドまたは動作確認を実施している
