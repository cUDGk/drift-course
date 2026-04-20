<div align="center">

# DriftCourse

### ミニ PC を推論サーバに、スマホを薄いクライアントにした完全ローカル型 AI チャット

[![Server: FastAPI](https://img.shields.io/badge/Server-FastAPI-009688?style=flat&logo=fastapi&logoColor=white)](server/)
[![Client: Android](https://img.shields.io/badge/Client-Android-3DDC84?style=flat&logo=android&logoColor=white)](android/)
[![Inference: llama.cpp](https://img.shields.io/badge/Inference-llama.cpp%20%28Vulkan%29-000000?style=flat)](https://github.com/ggerganov/llama.cpp)
[![Status: WIP](https://img.shields.io/badge/Status-WIP-orange?style=flat)](./CONCEPT.md)

**家の LAN を出ない。クラウドを使わない。スマホのバッテリーと熱の制約も受けない。**

</div>

## 要するに

[DRIFT](https://github.com/cUDGk/drift) の「完全ローカル / キャラ一貫性 / 長期記憶」の方針を保ったまま、推論だけミニ PC に逃がす構成。

- **スマホ (Android)** は UI とチャット履歴のキャッシュだけ。llama.cpp は載せない。
- **ミニ PC** で llama.cpp (Vulkan + Flash Attention) を常駐させ、スマホから HTTP + SSE で叩く。
- 通信は LAN 内、または Tailscale 経由。クラウドは使わない。

## なぜスマホでやらないか

- 8GB LPDDR5X の帯域 (~25 GB/s) では **1.5B Q4 でも 20 tok/s が限界**、7B 以上は実用速度に届かない
- 熱でサーマルスロットリングしてバッテリもゴリゴリ減る
- 大きいモデル (30B MoE / 32B dense) は RAM 的に起動すら不可

ミニ PC (Ryzen 9 7940HS / Radeon 780M / 64GB) なら:
- **Qwen3-30B-A3B MoE Q4_K_M で 24 tok/s** ([実測](https://github.com/cUDGk/llm-exp-runbook))
- `cache_prompt` + speculative decoding で多ターン会話は **3–4.4×** 更に速い ([実測](https://github.com/cUDGk/llm-exp-chatcache))
- 32B / 72B も RAM に乗る (72B は遅いが動く)

## アーキテクチャ

```
┌─────────────────────┐          LAN / Tailscale           ┌──────────────────────────┐
│  Android (13T 等)   │ ◄──── HTTP + SSE ────────────────► │  Mini PC (192.168.1.7)   │
│                     │                                    │                          │
│  • Compose UI       │                                    │  ┌────────────────────┐  │
│  • OkHttp / SSE     │                                    │  │ DriftCourse server │  │
│  • Room キャッシュ  │                                    │  │ (FastAPI)          │  │
│  • DataStore 設定   │                                    │  │  ├─ /v1/chat (SSE) │  │
│                     │                                    │  │  ├─ /characters    │  │
│                     │                                    │  │  ├─ /conversations │  │
│                     │                                    │  │  └─ /models        │  │
│                     │                                    │  └─────────┬──────────┘  │
│                     │                                    │            │             │
│                     │                                    │  ┌─────────▼──────────┐  │
│                     │                                    │  │ llama-server       │  │
│                     │                                    │  │ (Vulkan + FA)      │  │
│                     │                                    │  │  + cache_prompt    │  │
│                     │                                    │  │  + 0.5B draft spec │  │
│                     │                                    │  └────────────────────┘  │
└─────────────────────┘                                    └──────────────────────────┘
```

## 何がどこにある

- [CONCEPT.md](CONCEPT.md) — 設計方針・将来分岐
- [server/](server/) — Python / FastAPI のミニ PC 側
- [android/](android/) — Kotlin / Compose のスマホ側 (着手は次フェーズ)
- [docs/](docs/) — API 仕様 / セットアップ

## 状態

WIP。今ここ:

- [x] プロジェクト骨格
- [ ] サーバ: llama-server プロセス管理 + SSE 素通し
- [ ] サーバ: キャラカード / 会話 DB
- [ ] クライアント: DataStore 設定 + SSE 受信
- [ ] LAN 越しに 13T から実チャット

## 関連リポジトリ

- [cUDGk/drift](https://github.com/cUDGk/drift) — 元の概念
- [cUDGk/llm-android](https://github.com/cUDGk/llm-android) — オンデバイス推論実装 (クライアント UI の流用元 / オフラインフォールバックの素材)
- [cUDGk/llm-exp-runbook](https://github.com/cUDGk/llm-exp-runbook) — ミニ PC 上の Backend / 量子化 / MoE 実測
- [cUDGk/llm-exp-chatcache](https://github.com/cUDGk/llm-exp-chatcache) — cache_prompt + spec decode の多ターン実測

## ライセンス

MIT © 2026 cUDGk
