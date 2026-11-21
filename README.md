# MySQL Index Lab

Kotlin + Spring Boot + GraphQL + MySQLで、**同じデータ・同じ検索条件について、emailのインデックス有無だけを変えて比較する**実験です。ビルドはGradle Kotlin DSL、Java 21を使います。

| テーブル | データ | 主キー | emailのインデックス |
| --- | --- | --- | --- |
| `Usertable1` | 両方に同一データ | `id` | なし |
| `Usertable2` | 両方に同一データ | `id` | `idx_usertable2_email` |

MySQLのInnoDBでは主キーにもインデックスがあります。「なし」は**emailに対する追加のインデックスがない**という意味です。`id`検索では両テーブルとも主キーを使えるため、今回の比較には`email`検索を使います。

## 起動

必要なもの：JDK 21、Docker Desktop（起動済み）。Gradleは同梱のWrapperが取得するため、別途インストール不要です。

```bash
docker compose up -d --wait
./gradlew bootRun
```

ブラウザで [GraphiQL](http://localhost:8080/graphiql) を開きます。APIは `POST http://localhost:8080/graphql` です。初回起動ではテーブルだけ作成され、データ投入は次の操作で行います。

MySQLはホストの`3307`、APIは`8080`です。既存のMySQLの標準ポート`3306`と分けています。Composeに書いたパスワードは、このローカル実験専用の固定値です。

## まず試す3つの操作

GraphiQLに1つずつ貼り付けて実行してください。サンプルは `requests.http` にもあります。

### 1. 両テーブルに10万件ずつ投入

```graphql
mutation {
  seedUsers(rowCount: 100000) {
    rowsPerTable
  }
}
```

`id=1..100000`、`name="User 1"`、`email="user1@example.com"`のようなデータを生成します。年齢は18〜80歳です。**再実行すると、実験用の両テーブルの既存データを置き換えます。** 1〜100万件まで指定できます。データ生成とコピーは同じトランザクション内で行い、失敗時はロールバックします。

### 2. 同じユーザーをそれぞれ検索

```graphql
query {
  withoutIndex: search(table: TABLE1, email: "user50000@example.com") {
    elapsedMs
    users { id name email age }
  }
  withIndex: search(table: TABLE2, email: "user50000@example.com") {
    elapsedMs
    users { id name email age }
  }
}
```

### 3. 繰り返して測定し、実行計画を見る

```graphql
query {
  benchmark(email: "user50000@example.com", repetitions: 20, warmup: 3) {
    repetitions
    warmup
    speedup
    table1 {
      matchedRows
      medianMs
      averageMs
      minMs
      maxMs
      samplesMs
      explainAnalyze
    }
    table2 {
      matchedRows
      medianMs
      averageMs
      minMs
      maxMs
      samplesMs
      explainAnalyze
    }
  }
}
```

見る場所：

- `matchedRows`：同じ検索結果になっているか（この例は両方1件）。
- `medianMs`：繰り返し計測した検索時間の中央値。
- `speedup`：`table1.medianMs / table2.medianMs`。10なら、この測定ではインデックスありの中央値が10分の1。
- `explainAnalyze`：DBが実際にクエリを実行した結果の計画。TABLE1では`Table scan`、TABLE2では`Index lookup ... idx_usertable2_email`が想定されます。文字列中の`\n`は改行です。

## 何を測っているか

アプリで`System.nanoTime()`を使い、JDBC呼び出しの直前から、結果をすべて取得してKotlinの`User`に変換するまでを測っています。**接続プールからの接続取得・アプリとDB間の通信・結果変換を含みます。HTTP通信やGraphQL全体の時間、データ投入時間は含みません。** DB内部の実行時間は`EXPLAIN ANALYZE`の`actual time`と併せて確認します。`EXPLAIN ANALYZE`自身にも計測コストがあります。

3回ずつウォームアップした後、両テーブルを交互に測り、各回で先に測るテーブルも交代します。MySQLのバッファプールやOS/JVMが温まった状態の比較です。毎回ディスクから読む実験ではなく、ディスクI/O削減量を直接測定するものでもありません。MySQL 8.4に旧式のクエリ結果キャッシュはありませんが、データや索引のページはメモリにキャッシュされます。

時間はPCの負荷やキャッシュ状態で変わるので、1回の数値だけで結論を出さず、実行計画も比較します。小さいデータでは通信などの固定コストが目立ち、差が小さい場合があります。パフォーマンステストでは速度比を合否の条件にしていません。

## IntelliJ IDEAで開く

1. **Open**から、この`mysql-index-lab`フォルダを開く。
2. Gradleプロジェクトとして読み込み、Gradleの同期が完了するまで待つ。
3. **Settings → Build, Execution, Deployment → Build Tools → Gradle**で、Gradleの配布元を**Wrapper**、Gradle JVMを**JDK 21**にする。JDKがなければIDEのDownload JDKから21を取得する。
4. Project SDKも21にする。
5. ターミナルで`docker compose up -d --wait`を実行する。
6. Gradleツールウィンドウの **Tasks → application → bootRun** を実行する。Kotlin/Springの補助機能の有無に関係なく、Gradleタスクで起動できる。
7. ブラウザで`http://localhost:8080/graphiql`を開く。

IntelliJのHTTP Clientが使える場合は`requests.http`の各リクエストも実行できます。使えない場合はGraphiQLだけで全操作ができます。

## SQLを直接見る

```bash
docker compose exec mysql mysql -u lab -plab_local_password index_lab
```

```sql
SHOW INDEX FROM Usertable1;
SHOW INDEX FROM Usertable2;

SELECT COUNT(*) FROM Usertable1;
SELECT COUNT(*) FROM Usertable2;

EXPLAIN ANALYZE
SELECT id, name, email, age FROM Usertable1
WHERE email = 'user50000@example.com';

EXPLAIN ANALYZE
SELECT id, name, email, age FROM Usertable2
WHERE email = 'user50000@example.com';
```

`schema.sql`のこの1行が「emailにインデックスを貼る」に相当します。

```sql
INDEX idx_usertable2_email (email)
```

すでにあるテーブルに後から貼るなら、次のSQLです（TABLE2には作成済みなので重ねて実行しません）。

```sql
CREATE INDEX idx_usertable2_email ON Usertable2(email);
```

InnoDBのセカンダリインデックスのリーフにはemailと主キー値が格納されます。今回はemailのB-tree系索引で候補を絞り、主キーを使ってデータ本体からnameやageも取得できます。TABLE1ではemailの索引がないため、通常は行を全体走査して条件を調べます。

## 次の実験

1. `rowCount`を1,000 → 10,000 → 100,000 → 1,000,000に変え、同じメールで中央値と計画を記録する。投入件数以下のIDを選ぶ。
2. `missing@example.com`で存在しないユーザーを探す。どちらも0件なのに探し方がどう違うかを見る。
3. SQLで`id`を条件にして比較する。両方に主キーインデックスがあるとどうなるか。
4. SQLで`email LIKE '%50000%'`を試す。先頭が分からない条件で索引をどう使うか、実行計画を見る。

## 動作確認時の測定例

2026-11-22、Apple Silicon Mac / Java 21 / Docker上のMySQL 8.4で、各10万件から`user50000@example.com`を検索しました。ウォームアップ3回、測定20回の例です。

| 比較 | 中央値 | 実行計画 |
| --- | --- | --- |
| TABLE1（email索引なし） | 13.647 ms | Table scan、実際に100,000行を走査 |
| TABLE2（email索引あり） | 0.615 ms | idx_usertable2_emailによるIndex lookup、1件取得 |

この1回の測定では中央値で約22.18倍の差でした。再現時の速度比を保証する値ではありません。全サンプルと実行計画は[benchmark-example.json](docs/benchmark-example.json)に保存しています。

## テストと停止

```bash
./gradlew test bootJar
docker compose stop
```

テストではTestcontainersが**独立したMySQLコンテナ**を起動します。実験用DBを変更せず、GraphQL経由のデータ投入・結果一致・索引構成・異なる実行計画・再投入・入力チェックを検証します。Dockerの起動が必要です。GitHub Actionsでも同じ検証を実行します。

実験データをすべて消して初期化する場合だけ、`docker compose down -v`を実行します。通常の`stop`や`down`では名前付きボリュームのデータは残ります。

接続先を変える場合は`DB_URL`、`DB_USER`、`DB_PASSWORD`、APIポートを変える場合は`PORT`を環境変数に指定できます。`.env`はCompose用であり、GradleやIntelliJには自動で読み込まれません。API側の上書きは実行構成の環境変数に設定してください。

## 構成

```text
src/main/kotlin/dev/sakamoti55/indexlab/
  IndexLabApplication.kt  起動
  LabController.kt        GraphQLの入口
  LabService.kt           データ投入・SQL・計測
src/main/resources/
  schema.sql              テーブルと索引
  graphql/schema.graphqls APIの型定義
  application.yaml        接続設定
requests.http             APIの実行例
compose.yaml              ローカルMySQL
```

参考：[Spring BootのGraphQL対応](https://docs.spring.io/spring-boot/3.5/reference/web/spring-graphql.html)、[MySQL EXPLAIN ANALYZE](https://dev.mysql.com/doc/refman/8.4/en/explain.html)、[InnoDBの主キー・セカンダリインデックス](https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html)。
