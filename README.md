# health-fix

Android アプリ。設定した歩数を Health Connect に記録し、端末内で履歴を管理します。

## APK を入手する

GitHub Actions が APK をビルドします。GitHub リポジトリの **Actions → Android APK** から最新実行を開き、`health-fix-...` artifact をダウンロードしてください。`v*` タグを作成すると GitHub Releases に APK を公開します。

## ビルド

```sh
gradle assembleDebug
```

仕様書: [SPEC.md](SPEC.md)

## 現在の実装範囲

- Health Connect の歩数読み書き権限
- 初期設定: 毎日、自動実行ON、0:00〜8:00、48,000〜52,000歩
- 歩数生成、15/30/60分区間へのランダム分散、Health Connect への手動入力
- WorkManager による終了時刻直後の自動実行（OSの都合で遅れる場合があります）
- 履歴表示と、指定した開始日〜終了日の未記録日をまとめて作成
- 既にアプリ内履歴にある日は重複を避けてスキップ

曜日選択、未実行日の自動補完、記録の編集・削除、Health Connect 上の履歴照合は未実装です。アプリ内履歴は現状端末内の設定ストレージに保存します。
