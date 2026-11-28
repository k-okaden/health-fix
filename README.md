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
- 指定範囲からの歩数生成、時間帯ごとの分配、手動書き込み
- 履歴とアプリ内設定の保存
- 任意の過去日への作成、日付重複のスキップ

自動実行、曜日選択、補完、記録の編集・削除、Health Connect 上の履歴照合は未実装です。
