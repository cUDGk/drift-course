from __future__ import annotations

import json
import secrets
import sqlite3
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

# SQLite スキーマ。DRIFT CONCEPT の 4 層メモリ (char / recent / mid / long) を
# そのまま列挙値として持つ。vec テーブルは sqlite-vec を後で足すときに追加予定。
SCHEMA = """
CREATE TABLE IF NOT EXISTS characters (
  id             TEXT PRIMARY KEY,
  name           TEXT NOT NULL,
  system_prompt  TEXT NOT NULL DEFAULT '',
  card_json      TEXT NOT NULL DEFAULT '{}',
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
  id           TEXT PRIMARY KEY,
  character_id TEXT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
  title        TEXT NOT NULL DEFAULT '',
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conv_char ON conversations(character_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS messages (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  role            TEXT NOT NULL CHECK (role IN ('system','user','assistant')),
  content         TEXT NOT NULL,
  created_at      INTEGER NOT NULL,
  summarized_at   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_msg_unsummarized ON messages(conversation_id, summarized_at);

CREATE TABLE IF NOT EXISTS memory_layers (
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  layer           TEXT NOT NULL CHECK (layer IN ('char','recent','mid','long')),
  content         TEXT NOT NULL DEFAULT '',
  updated_at      INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, layer)
);
"""


def _now() -> int:
    return int(time.time())


def new_id() -> str:
    return secrets.token_urlsafe(9)


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        # check_same_thread=False + 1 connection per request で運用。
        # WAL は同時 reader と 1 writer を許す。
        self.conn = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA foreign_keys=ON")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        # 旧スキーマ (summarized_at 無し) のデプロイ向けの防御的 ALTER。
        # idx_msg_unsummarized が summarized_at を参照するため executescript より前に入れる。
        try:
            self.conn.execute("ALTER TABLE messages ADD COLUMN summarized_at INTEGER")
        except sqlite3.OperationalError:
            pass
        self.conn.executescript(SCHEMA)

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Connection]:
        self.conn.execute("BEGIN")
        try:
            yield self.conn
        except Exception:
            self.conn.execute("ROLLBACK")
            raise
        else:
            self.conn.execute("COMMIT")

    def close(self) -> None:
        self.conn.close()

    # --- characters ---
    def create_character(self, name: str, system_prompt: str, card: dict[str, Any]) -> dict[str, Any]:
        cid = new_id()
        now = _now()
        with self.tx() as c:
            c.execute(
                "INSERT INTO characters(id,name,system_prompt,card_json,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                (cid, name, system_prompt, json.dumps(card, ensure_ascii=False), now, now),
            )
        return self.get_character(cid)  # type: ignore[return-value]

    def list_characters(self) -> list[dict[str, Any]]:
        rows = self.conn.execute("SELECT * FROM characters ORDER BY updated_at DESC").fetchall()
        return [_row_character(r) for r in rows]

    def get_character(self, cid: str) -> dict[str, Any] | None:
        r = self.conn.execute("SELECT * FROM characters WHERE id=?", (cid,)).fetchone()
        return _row_character(r) if r else None

    def update_character(
        self,
        cid: str,
        name: str | None,
        system_prompt: str | None,
        card: dict[str, Any] | None,
    ) -> dict[str, Any] | None:
        cur = self.get_character(cid)
        if cur is None:
            return None
        with self.tx() as c:
            c.execute(
                "UPDATE characters SET name=?, system_prompt=?, card_json=?, updated_at=? WHERE id=?",
                (
                    name if name is not None else cur["name"],
                    system_prompt if system_prompt is not None else cur["system_prompt"],
                    json.dumps(card if card is not None else cur["card"], ensure_ascii=False),
                    _now(),
                    cid,
                ),
            )
        return self.get_character(cid)

    def delete_character(self, cid: str) -> bool:
        with self.tx() as c:
            cur = c.execute("DELETE FROM characters WHERE id=?", (cid,))
            return cur.rowcount > 0

    # --- conversations ---
    def create_conversation(self, character_id: str, title: str = "") -> dict[str, Any]:
        convid = new_id()
        now = _now()
        with self.tx() as c:
            c.execute(
                "INSERT INTO conversations(id,character_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
                (convid, character_id, title, now, now),
            )
        return self.get_conversation(convid)  # type: ignore[return-value]

    def list_conversations(self, character_id: str | None) -> list[dict[str, Any]]:
        if character_id:
            rows = self.conn.execute(
                "SELECT * FROM conversations WHERE character_id=? ORDER BY updated_at DESC",
                (character_id,),
            ).fetchall()
        else:
            rows = self.conn.execute(
                "SELECT * FROM conversations ORDER BY updated_at DESC"
            ).fetchall()
        return [dict(r) for r in rows]

    def get_conversation(self, convid: str) -> dict[str, Any] | None:
        r = self.conn.execute("SELECT * FROM conversations WHERE id=?", (convid,)).fetchone()
        return dict(r) if r else None

    def delete_conversation(self, convid: str) -> bool:
        with self.tx() as c:
            cur = c.execute("DELETE FROM conversations WHERE id=?", (convid,))
            return cur.rowcount > 0

    def touch_conversation(self, convid: str) -> None:
        self.conn.execute("UPDATE conversations SET updated_at=? WHERE id=?", (_now(), convid))

    # --- messages ---
    def append_message(self, convid: str, role: str, content: str) -> dict[str, Any]:
        mid = new_id()
        now = _now()
        with self.tx() as c:
            c.execute(
                "INSERT INTO messages(id,conversation_id,role,content,created_at) VALUES(?,?,?,?,?)",
                (mid, convid, role, content, now),
            )
            c.execute("UPDATE conversations SET updated_at=? WHERE id=?", (now, convid))
        return {"id": mid, "conversation_id": convid, "role": role, "content": content, "created_at": now}

    def list_messages(self, convid: str) -> list[dict[str, Any]]:
        rows = self.conn.execute(
            "SELECT * FROM messages WHERE conversation_id=? ORDER BY created_at", (convid,)
        ).fetchall()
        return [dict(r) for r in rows]

    def list_unsummarized_messages(self, convid: str) -> list[dict[str, Any]]:
        # system は system プロンプト再合成側で扱うので除外。
        rows = self.conn.execute(
            "SELECT * FROM messages WHERE conversation_id=? AND role != 'system' "
            "AND summarized_at IS NULL ORDER BY created_at",
            (convid,),
        ).fetchall()
        return [dict(r) for r in rows]

    def mark_summarized(self, message_ids: list[str], ts: int) -> None:
        if not message_ids:
            return
        placeholders = ",".join("?" * len(message_ids))
        with self.tx() as c:
            c.execute(
                f"UPDATE messages SET summarized_at=? WHERE id IN ({placeholders})",
                (ts, *message_ids),
            )

    def count_unsummarized_tokens(self, convid: str) -> int:
        # LENGTH(content) は SQLite では UTF-16 コードユニット数 (sqlite3 driver 経由)。
        # 日本語想定なので cp / 4 は粗い下限見積もりで十分。実トークナイザは呼ばない。
        row = self.conn.execute(
            "SELECT COALESCE(SUM(LENGTH(content)), 0) AS s FROM messages "
            "WHERE conversation_id=? AND role != 'system' AND summarized_at IS NULL",
            (convid,),
        ).fetchone()
        return int(row["s"]) // 4

    # --- memory ---
    def get_memory(self, convid: str) -> dict[str, str]:
        rows = self.conn.execute(
            "SELECT layer, content FROM memory_layers WHERE conversation_id=?", (convid,)
        ).fetchall()
        out = {"char": "", "recent": "", "mid": "", "long": ""}
        for r in rows:
            out[r["layer"]] = r["content"]
        return out

    def set_memory(self, convid: str, layer: str, content: str) -> None:
        if layer not in ("char", "recent", "mid", "long"):
            raise ValueError(f"invalid layer: {layer}")
        with self.tx() as c:
            c.execute(
                "INSERT INTO memory_layers(conversation_id,layer,content,updated_at) VALUES(?,?,?,?) "
                "ON CONFLICT(conversation_id,layer) DO UPDATE SET content=excluded.content, updated_at=excluded.updated_at",
                (convid, layer, content, _now()),
            )


def _row_character(r: sqlite3.Row) -> dict[str, Any]:
    d = dict(r)
    try:
        d["card"] = json.loads(d.pop("card_json") or "{}")
    except json.JSONDecodeError:
        d["card"] = {}
    return d
