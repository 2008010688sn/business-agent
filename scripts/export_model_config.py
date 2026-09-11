#!/usr/bin/env python3
"""Copy model_config + agent bindings from source AI DB into local business_agent.

Requires SRC_PG_* and DATA_AGENT_CRYPTO_KEY. Does not print secrets.
Does not write API keys into git SQL files.
"""
from __future__ import annotations

import base64
import os
import sys

import psycopg2
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from psycopg2 import sql
from psycopg2.extras import Json, RealDictCursor

PREFIX = "enc:gcm:"
TENANT = "default"
AGENT_IDS = (4, 5)


def src_conn():
    return psycopg2.connect(
        host=os.environ["SRC_PG_HOST"],
        port=int(os.environ.get("SRC_PG_PORT", "5432")),
        dbname=os.environ["SRC_PG_DB"],
        user=os.environ["SRC_PG_USER"],
        password=os.environ["SRC_PG_PASSWORD"],
    )


def dst_conn():
    return psycopg2.connect(
        host=os.environ.get("POSTGRES_HOST", "127.0.0.1"),
        port=int(os.environ.get("POSTGRES_PORT", "5433")),
        dbname=os.environ.get("POSTGRES_DB", "business_agent"),
        user=os.environ.get("POSTGRES_USER", "agent"),
        password=os.environ.get("POSTGRES_PASSWORD", "agent"),
    )


def crypto_key_bytes():
    key = os.environ.get("DATA_AGENT_CRYPTO_KEY", "").strip()
    if not key:
        raise SystemExit("DATA_AGENT_CRYPTO_KEY is required to decrypt model api keys")
    try:
        decoded = base64.b64decode(key)
        if len(decoded) in (16, 24, 32):
            return decoded
    except Exception:
        pass
    raw = key.encode("utf-8")
    if len(raw) not in (16, 24, 32):
        raise SystemExit("DATA_AGENT_CRYPTO_KEY length must be 16/24/32 bytes")
    return raw


def decrypt_secret(value, key_bytes):
    if not value:
        return value
    if not value.startswith(PREFIX):
        return value
    payload = base64.b64decode(value[len(PREFIX) :])
    iv, cipher = payload[:12], payload[12:]
    return AESGCM(key_bytes).decrypt(iv, cipher, None).decode("utf-8")


def local_cols(cur, table):
    cur.execute(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_schema='agent' AND table_name=%s
        ORDER BY ordinal_position
        """,
        (table,),
    )
    return [r[0] for r in cur.fetchall()]


def insert_rows(dst, table, rows, cols):
    if not rows:
        return 0
    use = [c for c in cols if c in rows[0]]
    stmt = sql.SQL("INSERT INTO agent.{} ({}) VALUES ({})").format(
        sql.Identifier(table),
        sql.SQL(", ").join(map(sql.Identifier, use)),
        sql.SQL(", ").join(sql.Placeholder() for _ in use),
    )
    payload = []
    for row in rows:
        values = []
        for col in use:
            value = row.get(col)
            if isinstance(value, (dict, list)):
                value = Json(value)
            values.append(value)
        payload.append(tuple(values))
    with dst.cursor() as cur:
        cur.executemany(stmt, payload)
    return len(payload)


def remap(row):
    out = dict(row)
    if "tenant_id" in out:
        out["tenant_id"] = TENANT
    for key in ("create_by", "last_modify_by"):
        if out.get(key) in (None, "", "1"):
            out[key] = "sn68"
    for key in ("create_name", "last_modify_name"):
        if out.get(key):
            out[key] = "sn68"
    return out


def main():
    key_bytes = crypto_key_bytes()
    src = src_conn()
    dst = dst_conn()
    decrypted = 0
    failed = []
    try:
        with src.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(
                """
                SELECT * FROM v4_ai.model_config
                WHERE deleted = false AND (tenant_id = '1' OR tenant_id IS NULL)
                ORDER BY id
                """
            )
            models = [dict(r) for r in cur.fetchall()]
            cur.execute(
                """
                SELECT * FROM v4_ai.agent_model_config
                WHERE deleted = false AND agent_id = ANY(%s)
                ORDER BY agent_id, id
                """,
                (list(AGENT_IDS),),
            )
            bindings = [dict(r) for r in cur.fetchall()]
            cur.execute(
                """
                SELECT id, chat_model_config_id FROM v4_ai.data_agent
                WHERE id = ANY(%s)
                """,
                (list(AGENT_IDS),),
            )
            agents = [dict(r) for r in cur.fetchall()]
            cur.execute(
                """
                SELECT t.* FROM v4_ai.model_tts_config t
                WHERE t.deleted = false
                  AND t.model_config_id IN (
                    SELECT id FROM v4_ai.model_config
                    WHERE deleted = false AND (tenant_id = '1' OR tenant_id IS NULL)
                  )
                """
            )
            tts = [dict(r) for r in cur.fetchall()]

        for model in models:
            try:
                model["api_key"] = decrypt_secret(model.get("api_key"), key_bytes)
                decrypted += 1 if str(model.get("api_key") or "").startswith("sk-") or model.get("api_key") else 0
                if model.get("proxy_password"):
                    model["proxy_password"] = decrypt_secret(model.get("proxy_password"), key_bytes)
            except Exception as ex:
                failed.append((model["id"], model.get("model_name"), type(ex).__name__))
                model["api_key"] = None

        if failed:
            print("decrypt_failed", failed, flush=True)
            # still copy rows that decrypted; drop rows with empty key (NOT NULL)
            models = [m for m in models if m.get("api_key")]
        if not models:
            raise SystemExit("no model_config rows to insert")

        with dst.cursor() as cur:
            cols = local_cols(cur, "model_config")
            bind_cols = local_cols(cur, "agent_model_config")
            tts_cols = local_cols(cur, "model_tts_config") if tts else []
            cur.execute("DELETE FROM agent.agent_model_config WHERE agent_id = ANY(%s)", (list(AGENT_IDS),))
            cur.execute("DELETE FROM agent.data_agent_model_structured_capability")
            cur.execute("DELETE FROM agent.model_tts_config")
            cur.execute("DELETE FROM agent.model_asr_config")
            cur.execute("DELETE FROM agent.model_realtime_voice_config")
            cur.execute("DELETE FROM agent.model_config")

        mapped_models = [remap(m) for m in models]
        n = insert_rows(dst, "model_config", mapped_models, cols)
        print("inserted_models", n, [m["model_name"] for m in mapped_models], flush=True)

        if tts:
            insert_rows(dst, "model_tts_config", [remap(r) for r in tts], tts_cols)
            print("inserted_tts", len(tts), flush=True)

        bind_n = insert_rows(dst, "agent_model_config", [remap(r) for r in bindings], bind_cols)
        print("inserted_bindings", bind_n, flush=True)

        with dst.cursor(cursor_factory=RealDictCursor) as cur:
            for agent in agents:
                cur.execute(
                    "UPDATE agent.data_agent SET chat_model_config_id=%s WHERE id=%s",
                    (agent["chat_model_config_id"], agent["id"]),
                )
            cur.execute(
                """
                SELECT id, model_name, model_type, is_active,
                       CASE WHEN api_key LIKE 'enc:gcm:%%' THEN 'enc' ELSE 'plain' END AS key_kind,
                       length(api_key) AS key_len
                FROM agent.model_config
                WHERE deleted = false
                ORDER BY id
                """
            )
            print("local_models", [dict(r) for r in cur.fetchall()], flush=True)
            cur.execute(
                "SELECT id, name, chat_model_config_id FROM agent.data_agent WHERE id IN (1,4,5) ORDER BY id"
            )
            print("local_agents", [dict(r) for r in cur.fetchall()], flush=True)
            cur.execute(
                """
                SELECT agent_id, model_config_id, is_default, enabled
                FROM agent.agent_model_config
                WHERE deleted = false AND agent_id = ANY(%s)
                ORDER BY agent_id, is_default DESC
                """,
                (list(AGENT_IDS),),
            )
            print("local_bindings", [dict(r) for r in cur.fetchall()], flush=True)
        dst.commit()
        print("MODEL_EXPORT_OK", flush=True)
    except Exception:
        dst.rollback()
        raise
    finally:
        src.close()
        dst.close()


if __name__ == "__main__":
    sys.exit(main())
