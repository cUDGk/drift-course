# DriftCourse — 設計

## きっかけ

[DRIFT](https://github.com/cUDGk/drift) はスマホ単独で完結させる設計だったが、
[llm-android](https://github.com/cUDGk/llm-android) を 13T (8GB LPDDR5X) で実測してみると、

- 1.5B Q4 が 16–20 tok/s、1.5B + 0.5B speculative でようやく 25–30 tok/s 射程
- 4B 以上は帯域的に絶対に 30 tok/s に届かない (LPDDR5X-6400 の実効帯域が物理限界)
- 7B / 30B MoE は RAM 的に起動不可 (Low Memory Killer に殺される)
- 常用すると熱でスロットリング、バッテリ消費も厳しい

一方、同じ家の中に **Ryzen 9 7940HS / Radeon 780M / 64GB** のミニ PC が常時電源で立っている。
推論だけミニ PC でやって、スマホは UI だけにすれば、

- 30B クラスの MoE を 24 tok/s で回せる ([実測](https://github.com/cUDGk/llm-exp-runbook))
- cache_prompt + spec decode で多ターン会話は **3–4.4×** 高速 ([実測](https://github.com/cUDGk/llm-exp-chatcache))
- スマホは冷たいまま、バッテリもほぼ減らない
- DRIFT の「クラウドに出さない」原則は LAN 内完結なら維持できる

DriftCourse はこの構成を具体化する。

## スコープ

### やること

- ミニ PC 上で llama-server + FastAPI のラッパサーバを常駐
- Android 薄クライアントから HTTP + SSE で叩く
- DRIFT のキャラカード / 階層化メモリ / 会話履歴をサーバ側に集約
- LAN 内 (+ Tailscale) で動く

### やらないこと (当面)

- クラウド LLM へのフォールバック (方針として禁止)
- マルチユーザ (家族で共有する設計にしない)
- マルチ端末同期 (1 スマホ = 1 サーバ)
- 音声・画像生成連携
- アカウント / ログイン

## 物理構成

```
家の WiFi / LAN
  ├── スマホ (Android 13T)  ── UI, 一時キャッシュ
  └── ミニ PC (192.168.1.7) ── 推論 + 永続化
```

外出時に使いたければ Tailscale に両方入れる。VPN 扱いで暗号化も解決。

## ソフト構成

### サーバ (ミニ PC 側)

- **FastAPI** (Python 3.12) でラッパ API を露出
- 中で **llama-server** を subprocess として起動・監視
- 永続化は **SQLite** 単一ファイル (WAL モード)

なぜ llama-cpp-python を直接使わず llama-server を挟むか:
- llm-exp-chatcache で検証済みの `cache_prompt` / speculative decoding がそのまま使える
- モデル切替時は subprocess を再起動すれば済む
- クラッシュ隔離 (Python プロセスは生きる)

### クライアント (Android 側)

- **Kotlin + Jetpack Compose** (llm-android の UI 層を流用)
- JNI / llama.cpp は**落とす**
- **OkHttp + SSE** で `/v1/chat` を受信
- **DataStore** にサーバ URL / Bearer トークン
- **Room** に会話キャッシュ (オフライン閲覧用)

## API (初期案)

OAI 互換ではなく DriftCourse ネイティブ API。OAI 互換が必要な外部クライアントはサーバが追加で `/v1/*` を exposed しても良いが、優先度は低い。

```
GET  /health                              — ヘルスチェック
GET  /models                              — 利用可能な GGUF 一覧 + 現在ロード中
POST /models/load                         — モデル切替 (subprocess 再起動)

GET  /characters                          — キャラカード一覧
POST /characters                          — SillyTavern 形式カードを upload
GET  /characters/:id
PATCH /characters/:id
DELETE /characters/:id

GET  /conversations?character_id=X        — 会話一覧
POST /conversations                       — 新規
GET  /conversations/:id/messages          — 全メッセージ
POST /conversations/:id/messages          — 送信 → SSE でトークンが降ってくる
DELETE /conversations/:id

GET  /conversations/:id/memory            — 階層化メモリ閲覧
PATCH /conversations/:id/memory/:layer    — 特定層を手動編集
```

認証は **Bearer トークン** (サーバ初回起動時に生成、ユーザが QR でスマホに読ませる想定)。

## 階層化メモリ

DRIFT CONCEPT.md と同じ 4 層構成を踏襲する:

| 層 | 実装 |
|---|---|
| キャラ記憶 | JSON カードに構造化して保持、プロンプトの system 部に混ぜる |
| 直近ターン | 生ログをそのまま、n トークンまで |
| 中期要約 | バックグラウンドジョブで古いターンを要約 (別モデル or 同モデル) |
| 長期アーカイブ | sqlite-vec で embedding 検索 |

各層のトークン予算は `characters.settings` に持たせる。計算はサーバ側で完結。

**要約の実行戦略**:
- 会話ストリーミングとは別スレッド
- `priority = low` で裏で回す (ユーザ体験を阻害しない)
- 失敗したら次の idle タイミングで再試行

## 推論設定のデフォルト

[llm-exp-runbook](https://github.com/cUDGk/llm-exp-runbook) / [llm-exp-chatcache](https://github.com/cUDGk/llm-exp-chatcache) の実測結果から:

| 項目 | 値 | 根拠 |
|---|---|---|
| backend | Vulkan + Flash Attention | CPU 比 +23%、FA は微増 (E1) |
| 量子化 | Q4_K_M | Q5_K_M より速く精度差小 (E2) |
| KV cache | q8_0 | f16 と同速、VRAM 半減 (E3) |
| メインモデル | Qwen3-30B-A3B MoE | 7B Dense の 2× (E4) |
| draft モデル | Qwen2.5-0.5B | チャット文脈で実績あり |
| cache_prompt | 有効 | prefill が 10–60× 速い |
| n_draft | 8 | 標準 |

## セキュリティ

- サーバは **LAN インタフェース以外で listen しない** (`0.0.0.0` ではなく LAN IP bind)
- Bearer トークンは初回生成後 `server/.drift-token` に保存、gitignore
- TLS は Tailscale に任せる (素の LAN では平文 HTTP、ただし家庭内前提)
- llama-server のポートは外に出さない (DriftCourse server 経由のみ)

## 将来の分岐

| 起きたら | 変えること |
|---|---|
| 端末で 1B が 30B-A3B 相当に追いつく | オンデバイス復活、llm-android に回帰 |
| 家に Mac Studio 等が来る | 同 API でバックエンド差替 (MLX 版サーバ) |
| Tailscale の代わりに自前 Zerotier を使いたい | 何も変えなくて良い、API は HTTP のみ |
| 別プラットフォーム (iOS / Desktop) | クライアントを増やすだけ、サーバは共通 |

## ライセンス

MIT
