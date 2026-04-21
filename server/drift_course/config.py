from __future__ import annotations

import secrets
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    .env から読む。サーバ IP / トークン / llama-server バイナリ位置など。
    実測で検証済みのデフォルトは llm-exp-runbook / llm-exp-chatcache の結果を反映する。
    """

    model_config = SettingsConfigDict(env_file=".env", env_prefix="DRIFT_", extra="ignore")

    # バインド先。LAN 内で使う想定なので家の LAN IP を明示。0.0.0.0 は意図的に避ける。
    host: str = "0.0.0.0"
    port: int = 8787

    # llama-server バイナリへのパス (Windows なら .exe)
    llama_server_bin: Path = Field(
        default=Path("C:/tools/llama.cpp/llama-server.exe"),
        description="Path to llama-server executable",
    )
    # モデル格納ディレクトリ (GGUF)
    models_dir: Path = Field(default=Path("./models"))
    # 現在ロード中のモデルファイル名 (models_dir 相対)
    default_model: str = "Qwen3-30B-A3B-Q4_K_M.gguf"
    # speculative decoding 用 draft モデル (無効なら空文字)
    default_draft_model: str = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"

    # llama-server に渡す標準パラメータ (全て Vulkan + FA + cache_prompt 前提)
    ctx_size: int = 8192
    n_gpu_layers: int = 999  # iGPU 全オフロード
    flash_attn: bool = True
    cache_type_k: str = "q8_0"
    cache_type_v: str = "q8_0"
    draft_max: int = 8

    # llama-server の内部ポート (DriftCourse server からしか叩かない)
    llama_port: int = 18080

    # 認証トークン (.drift-token が無ければ自動生成して書き出す)
    token_file: Path = Field(default=Path(".drift-token"))

    # SQLite DB
    db_path: Path = Field(default=Path("./drift.db"))

    # 自動要約 (mid レイヤ更新) の動作パラメータ。
    # 閾値は文字数/4 の荒いトークン見積もり。tokenizer を呼ばない。
    summarize_interval_s: float = 30.0
    # 閾値を緩めに。要約が早すぎると直近の文脈を失って柔軟性が下がるため、
    # ctx 8192 の余裕を見て 6000 (~24000 文字相当) で初めて発火させる。
    summarize_trigger_tokens: int = 6000
    summarize_batch_tokens: int = 1500
    summarize_keep_tokens: int = 3500
    summarize_enabled: bool = True


def ensure_token(settings: Settings) -> str:
    """
    Bearer トークンを取得。無ければ生成して書き出す。
    ユーザは初回起動時に出力されたトークンを QR 等でスマホに渡す想定。
    """
    p = settings.token_file
    if p.exists():
        tok = p.read_text(encoding="utf-8").strip()
        if tok:
            return tok
    tok = secrets.token_urlsafe(32)
    p.write_text(tok, encoding="utf-8")
    return tok
