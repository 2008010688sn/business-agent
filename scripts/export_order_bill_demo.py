#!/usr/bin/env python3
"""Copy order/bill agent skill config and this-month business rows into local demo_biz.

Source connection is read from SRC_PG_* env vars. Destination is the local
business_agent database (POSTGRES_*). Does not print passwords.
"""
from __future__ import annotations

import json
import os
import re
import sys
from datetime import datetime
from io import StringIO
from pathlib import Path

import psycopg2
from psycopg2 import sql
from psycopg2.extras import Json, RealDictCursor

ROOT = Path(__file__).resolve().parents[1]
SQL_DIR = ROOT / "backend" / "src" / "main" / "resources" / "sql" / "pg"

MONTH_START = os.environ.get("DEMO_MONTH_START", "2026-09-01")
MONTH_END = os.environ.get("DEMO_MONTH_END", "2026-10-01")
MONTH_LIKE = os.environ.get("DEMO_MONTH_LIKE", "2026-09")

AGENT_IDS = (4, 5)
TENANT = "default"
LOCAL_DS_ID = 3
LOCAL_DS_HOST = "127.0.0.1"
LOCAL_DS_PORT = int(os.environ.get("POSTGRES_PORT", "5433"))
LOCAL_DS_DB = os.environ.get("POSTGRES_DB", "business_agent")
LOCAL_DS_USER = os.environ.get("POSTGRES_USER", "agent")
LOCAL_DS_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "agent")
LOCAL_SCHEMA = "demo_biz"

TABLES = [
    "dis_demand",
    "dis_demand_address",
    "dis_demand_product",
    "dis_order",
    "dis_order_address",
    "dis_order_product",
    "additional_cost",
    "additional_cost_item",
    "bill_contract",
    "bill_contract_product",
    "bill_contract_product_route",
    "bill_cost",
    "bill_cost_breakdown",
    "bill_cost_return",
    "daily_product_num",
    "daily_rent_cost",
    "daily_rent_cost_return",
    "daily_rent_order_product",
    "order_rent_cost",
    "order_rent_cost_product",
    "overdue_bill",
    "overdue_rent_order_product",
    "project_bill_detail",
    "project_profit_cost",
    "project_profit_cost_record",
]

PII_COL = re.compile(
    r"(^|_)(tel|phone|mobile|bank_account|id_card|password|api_key|secret)(_|$)",
    re.I,
)
BRAND = (
    ("xx-cloud", "business-agent"),
    ("XX-CLOUD", "BUSINESS-AGENT"),
    ("com.xx.cloud", "com.sn68.agent"),
    ("HOREN", "Demo"),
    ("suning", "example"),
    ("xiangxiang", "example"),
    ("箱箱云", "演示客户"),
)


def src_conn():
    host = os.environ.get("SRC_PG_HOST")
    if not host:
        raise SystemExit("SRC_PG_HOST is required")
    return psycopg2.connect(
        host=host,
        port=int(os.environ.get("SRC_PG_PORT", "5432")),
        dbname=os.environ.get("SRC_PG_DB", "postgres"),
        user=os.environ.get("SRC_PG_USER", "postgres"),
        password=os.environ.get("SRC_PG_PASSWORD", ""),
    )


def dst_conn():
    return psycopg2.connect(
        host=os.environ.get("POSTGRES_HOST", "127.0.0.1"),
        port=int(os.environ.get("POSTGRES_PORT", "5433")),
        dbname=os.environ.get("POSTGRES_DB", "business_agent"),
        user=os.environ.get("POSTGRES_USER", "agent"),
        password=os.environ.get("POSTGRES_PASSWORD", "agent"),
    )


def scrub(value):
    if isinstance(value, str):
        for old, new in BRAND:
            value = value.replace(old, new)
        if "192.168." in value or "aliyuncs.com" in value:
            return None
        return value
    if isinstance(value, dict):
        return {k: scrub(v) for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    return value


def mask_row(row: dict) -> dict:
    out = {}
    for key, value in row.items():
        if PII_COL.search(key):
            out[key] = None
        else:
            out[key] = scrub(value)
    return out


def fetchall(cur, sql_text, params=None):
    cur.execute(sql_text, params or ())
    return [dict(r) for r in cur.fetchall()]


def ids(rows, *cols):
    found = set()
    for row in rows:
        for col in cols:
            value = row.get(col)
            if value is not None:
                found.add(value)
    return found


def has_col(cur, schema, table, column):
    cur.execute(
        """
        SELECT 1 FROM information_schema.columns
        WHERE table_schema=%s AND table_name=%s AND column_name=%s
        """,
        (schema, table, column),
    )
    return cur.fetchone() is not None


def table_columns(cur, schema, table):
    cur.execute(
        """
        SELECT a.attname AS name,
               format_type(a.atttypid, a.atttypmod) AS typ,
               a.attnotnull AS not_null
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname=%s AND c.relname=%s AND a.attnum>0 AND NOT a.attisdropped
        ORDER BY a.attnum
        """,
        (schema, table),
    )
    return [dict(r) for r in cur.fetchall()]


def create_table_sql(cols, table):
    parts = []
    for col in cols:
        piece = f'{quote(col["name"])} {col["typ"]}'
        parts.append(piece)
    return f"CREATE TABLE {LOCAL_SCHEMA}.{quote(table)} (\n  " + ",\n  ".join(parts) + "\n);"


def quote(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def insert_rows(dst, schema, table, rows, col_names):
    if not rows:
        return 0
    cols = [c for c in col_names if c in rows[0]]
    stmt = sql.SQL("INSERT INTO {}.{} ({}) VALUES ({})").format(
        sql.Identifier(schema),
        sql.Identifier(table),
        sql.SQL(", ").join(map(sql.Identifier, cols)),
        sql.SQL(", ").join(sql.Placeholder() for _ in cols),
    )
    payload = []
    for row in rows:
        values = []
        for col in cols:
            value = row.get(col)
            if isinstance(value, (dict, list)):
                value = Json(value)
            values.append(value)
        payload.append(tuple(values))
    with dst.cursor() as cur:
        cur.executemany(stmt, payload)
    return len(payload)


def copy_filtered(src, table, where_sql, params):
    with src.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(f"SELECT * FROM v4_zeus.{quote(table)} WHERE {where_sql}", params)
        return [mask_row(dict(r)) for r in cur.fetchall()]


def month_ts(col):
    return f"{col} >= %s AND {col} < %s"


def build_id_sets(src):
    with src.cursor(cursor_factory=RealDictCursor) as cur:
        products = fetchall(
            cur,
            f"""
            SELECT * FROM v4_zeus.dis_order_product
            WHERE COALESCE(deleted,false)=false
              AND ((sign_time >= %s AND sign_time < %s) OR (create_time >= %s AND create_time < %s))
            """,
            (MONTH_START, MONTH_END, MONTH_START, MONTH_END),
        )
        orders_month = fetchall(
            cur,
            f"""
            SELECT id FROM v4_zeus.dis_order
            WHERE COALESCE(deleted,false)=false
              AND create_time >= %s AND create_time < %s
            """,
            (MONTH_START, MONTH_END),
        )
        demands_month = fetchall(
            cur,
            f"""
            SELECT id FROM v4_zeus.dis_demand
            WHERE COALESCE(deleted,false)=false
              AND create_time >= %s AND create_time < %s
            """,
            (MONTH_START, MONTH_END),
        )
        bills = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.bill_cost
            WHERE COALESCE(deleted,false)=false
              AND (settlement_date LIKE %s OR settlement_date LIKE %s
                   OR (create_time >= %s AND create_time < %s))
            """,
            (f"{MONTH_LIKE}%", f"%{MONTH_LIKE}%", MONTH_START, MONTH_END),
        )
        extra = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.additional_cost
            WHERE COALESCE(deleted,false)=false
              AND (period LIKE %s OR (create_time >= %s AND create_time < %s))
            """,
            (f"%{MONTH_LIKE}%", MONTH_START, MONTH_END),
        )
        daily = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.daily_rent_cost
            WHERE COALESCE(deleted,false)=false
              AND (settlement_date LIKE %s OR (create_time >= %s AND create_time < %s))
            """,
            (f"%{MONTH_LIKE}%", MONTH_START, MONTH_END),
        )
        order_rent = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.order_rent_cost
            WHERE COALESCE(deleted,false)=false
              AND (settlement_date LIKE %s OR (create_time >= %s AND create_time < %s))
            """,
            (f"%{MONTH_LIKE}%", MONTH_START, MONTH_END),
        )
        overdue = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.overdue_bill
            WHERE COALESCE(deleted,false)=false
              AND (settlement_date LIKE %s OR (create_time >= %s AND create_time < %s))
            """,
            (f"%{MONTH_LIKE}%", MONTH_START, MONTH_END),
        )
        profit = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.project_profit_cost
            WHERE COALESCE(deleted,false)=false
              AND create_time >= %s AND create_time < %s
            """,
            (MONTH_START, MONTH_END),
        )
        project_bills = fetchall(
            cur,
            """
            SELECT * FROM v4_zeus.project_bill_detail
            WHERE COALESCE(deleted,false)=false
              AND (period LIKE %s OR cost_settlement_date LIKE %s
                   OR (create_time >= %s AND create_time < %s)
                   OR (billing_time >= %s AND billing_time < %s))
            """,
            (f"%{MONTH_LIKE}%", f"%{MONTH_LIKE}%", MONTH_START, MONTH_END, MONTH_START, MONTH_END),
        )

    product_ids = ids(products, "id")
    order_ids = ids(products, "order_id") | ids(orders_month, "id")
    demand_ids = ids(products, "demand_id") | ids(demands_month, "id")
    bill_ids = ids(bills, "id")
    extra_ids = ids(extra, "id")
    daily_ids = ids(daily, "id")
    order_rent_ids = ids(order_rent, "id")
    overdue_ids = ids(overdue, "id")
    profit_ids = ids(profit, "id")
    contract_ids = ids(bills, "contract_id") | ids(daily, "contract_id") | ids(order_rent, "contract_id")

    return {
        "dis_order_product": product_ids,
        "dis_order": order_ids,
        "dis_demand": demand_ids,
        "bill_cost": bill_ids,
        "additional_cost": extra_ids,
        "daily_rent_cost": daily_ids,
        "order_rent_cost": order_rent_ids,
        "overdue_bill": overdue_ids,
        "project_profit_cost": profit_ids,
        "project_bill_detail": ids(project_bills, "id"),
        "bill_contract": contract_ids,
        "counts": {
            "dis_order_product": len(products),
            "dis_order": len(order_ids),
            "dis_demand": len(demand_ids),
            "bill_cost": len(bills),
        },
    }


def load_by_ids(src, table, id_set):
    if not id_set:
        return []
    values = list(id_set)
    with src.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            f"SELECT * FROM v4_zeus.{quote(table)} WHERE id = ANY(%s)",
            (values,),
        )
        return [mask_row(dict(r)) for r in cur.fetchall()]


def load_by_fk(src, table, fk, parent_ids):
    if not parent_ids:
        return []
    with src.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            f"SELECT * FROM v4_zeus.{quote(table)} WHERE {quote(fk)} = ANY(%s)",
            (list(parent_ids),),
        )
        return [mask_row(dict(r)) for r in cur.fetchall()]


def dump_schema_and_data(dst, schema_path: Path, data_path: Path):
    lines = [
        "-- Demo business schema for order/bill agents. Generated, do not edit by hand.",
        f"-- Month window: {MONTH_START} .. {MONTH_END}",
        "CREATE SCHEMA IF NOT EXISTS demo_biz;",
        "SET search_path TO demo_biz, public;",
        "",
    ]
    data_lines = [
        "-- This-month snapshot for order/bill NL2SQL demo.",
        "SET search_path TO demo_biz, public;",
        "",
    ]
    with dst.cursor(cursor_factory=RealDictCursor) as cur:
        for table in TABLES:
            cols = table_columns(cur, LOCAL_SCHEMA, table)
            lines.append(f"DROP TABLE IF EXISTS {LOCAL_SCHEMA}.{quote(table)} CASCADE;")
            lines.append(create_table_sql(cols, table))
            lines.append("")
            buf = StringIO()
            copy_sql = f"COPY {LOCAL_SCHEMA}.{quote(table)} TO STDOUT"
            cur.copy_expert(copy_sql, buf)
            payload = buf.getvalue()
            data_lines.append(f"COPY {quote(table)} FROM stdin;")
            data_lines.append(payload.rstrip("\n"))
            data_lines.append("\\.")
            data_lines.append("")
    schema_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    data_path.write_text("\n".join(data_lines) + "\n", encoding="utf-8")


def local_cols(dst, table):
    with dst.cursor() as cur:
        cur.execute(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema='agent' AND table_name=%s
            ORDER BY ordinal_position
            """,
            (table,),
        )
        return [r[0] for r in cur.fetchall()]


def unique_latest(rows, keys):
    best = {}
    for row in rows:
        key = tuple(row.get(k) for k in keys)
        prev = best.get(key)
        if prev is None or (row.get("id") or 0) > (prev.get("id") or 0):
            best[key] = row
    return list(best.values())


def remap_tenant(row: dict) -> dict:
    out = {key: scrub(value) for key, value in row.items()}
    if "tenant_id" in out:
        out["tenant_id"] = TENANT
    for key in ("create_by", "last_modify_by", "published_by"):
        if key in out and out.get(key) in (None, "", "1"):
            out[key] = "sn68"
    for key in ("create_name", "last_modify_name"):
        if out.get(key):
            out[key] = "sn68"
    return out


def insert_mapped(dst, table, rows):
    cols = local_cols(dst, table)
    mapped = []
    for row in rows:
        item = remap_tenant(row)
        mapped.append({k: item.get(k) for k in cols if k in item})
    return insert_rows(dst, "agent", table, mapped, cols)


def sql_literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, datetime):
        return "'" + value.isoformat(sep=" ") + "'"
    if isinstance(value, (dict, list)):
        return "'" + json.dumps(value, ensure_ascii=False).replace("'", "''") + "'::jsonb"
    text = str(value).replace("'", "''")
    return "'" + text + "'"


def emit_inserts(table, rows, cols):
    if not rows:
        return []
    lines = []
    use_cols = [c for c in cols if c in rows[0]]
    col_sql = ", ".join(quote(c) for c in use_cols)
    for row in rows:
        values = ", ".join(sql_literal(row.get(c)) for c in use_cols)
        lines.append(f"INSERT INTO {quote(table)} ({col_sql}) VALUES ({values});")
    return lines


def migrate_biz(src, dst, id_sets):
    print("counts", id_sets["counts"], flush=True)
    with dst.cursor() as cur:
        cur.execute(f"DROP SCHEMA IF EXISTS {LOCAL_SCHEMA} CASCADE")
        cur.execute(f"CREATE SCHEMA {LOCAL_SCHEMA}")
    with src.cursor(cursor_factory=RealDictCursor) as scur:
        for table in TABLES:
            cols = table_columns(scur, "v4_zeus", table)
            ddl = create_table_sql(cols, table)
            with dst.cursor() as dcur:
                dcur.execute(ddl)
            print("created", table, flush=True)

    rows_by_table = {
        "dis_order_product": load_by_ids(src, "dis_order_product", id_sets["dis_order_product"]),
        "dis_order": load_by_ids(src, "dis_order", id_sets["dis_order"]),
        "dis_demand": load_by_ids(src, "dis_demand", id_sets["dis_demand"]),
        "dis_order_address": load_by_fk(src, "dis_order_address", "order_id", id_sets["dis_order"]),
        "dis_demand_address": load_by_fk(src, "dis_demand_address", "demand_id", id_sets["dis_demand"]),
        "dis_demand_product": load_by_fk(src, "dis_demand_product", "demand_id", id_sets["dis_demand"]),
        "bill_cost": load_by_ids(src, "bill_cost", id_sets["bill_cost"]),
        "bill_cost_breakdown": load_by_fk(src, "bill_cost_breakdown", "cost_id", id_sets["bill_cost"]),
        "bill_cost_return": load_by_fk(src, "bill_cost_return", "cost_id", id_sets["bill_cost"]),
        "additional_cost": load_by_ids(src, "additional_cost", id_sets["additional_cost"]),
        "additional_cost_item": load_by_fk(src, "additional_cost_item", "cost_id", id_sets["additional_cost"]),
        "daily_rent_cost": load_by_ids(src, "daily_rent_cost", id_sets["daily_rent_cost"]),
        "daily_rent_cost_return": load_by_fk(src, "daily_rent_cost_return", "cost_id", id_sets["daily_rent_cost"]),
        "daily_rent_order_product": load_by_fk(src, "daily_rent_order_product", "cost_id", id_sets["daily_rent_cost"]),
        "order_rent_cost": load_by_ids(src, "order_rent_cost", id_sets["order_rent_cost"]),
        "order_rent_cost_product": load_by_fk(src, "order_rent_cost_product", "cost_id", id_sets["order_rent_cost"]),
        "overdue_bill": load_by_ids(src, "overdue_bill", id_sets["overdue_bill"]),
        "overdue_rent_order_product": load_by_fk(src, "overdue_rent_order_product", "cost_id", id_sets["overdue_bill"]),
        "bill_contract": load_by_ids(src, "bill_contract", id_sets["bill_contract"]),
        "project_profit_cost": load_by_ids(src, "project_profit_cost", id_sets["project_profit_cost"]),
        "project_profit_cost_record": load_by_fk(src, "project_profit_cost_record", "profit_cost_id", id_sets["project_profit_cost"]),
        "project_bill_detail": load_by_ids(src, "project_bill_detail", id_sets["project_bill_detail"]),
        "daily_product_num": copy_filtered(
            src,
            "daily_product_num",
            "COALESCE(deleted,false)=false AND create_time >= %s AND create_time < %s",
            (MONTH_START, MONTH_END),
        ),
    }
    contract_product = load_by_fk(src, "bill_contract_product", "contract_id", id_sets["bill_contract"])
    rows_by_table["bill_contract_product"] = contract_product
    rows_by_table["bill_contract_product_route"] = load_by_fk(
        src, "bill_contract_product_route", "contract_product_id", ids(contract_product, "id")
    )

    for table in TABLES:
        rows = rows_by_table.get(table, [])
        if not rows:
            print("empty", table, flush=True)
            continue
        col_names = list(rows[0].keys())
        n = insert_rows(dst, LOCAL_SCHEMA, table, rows, col_names)
        print("inserted", table, n, flush=True)


def cleanup_agent_config(dst):
    with dst.cursor() as cur:
        cur.execute("DELETE FROM agent.data_agent_skill_binding WHERE agent_id = ANY(%s)", (list(AGENT_IDS),))
        cur.execute(
            """
            DELETE FROM agent.skill_datasource_tables
            WHERE skill_datasource_id IN (
              SELECT id FROM agent.skill_datasource WHERE skill_id IN (
                SELECT skill_id FROM agent.data_agent_skill_binding WHERE agent_id = ANY(%s)
                UNION SELECT id FROM agent.data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')
              )
            )
            """,
            (list(AGENT_IDS),),
        )
        cur.execute(
            """
            DELETE FROM agent.skill_datasource WHERE skill_id IN (
              SELECT id FROM agent.data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')
            )
            """
        )
        cur.execute(
            """
            DELETE FROM agent.semantic_model WHERE skill_id IN (
              SELECT id FROM agent.data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')
            )
            """
        )
        cur.execute(
            """
            DELETE FROM agent.business_knowledge WHERE skill_id IN (
              SELECT id FROM agent.data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')
            )
            """
        )
        cur.execute("DELETE FROM agent.agent_preset_question WHERE agent_id = ANY(%s)", (list(AGENT_IDS),))
        cur.execute("DELETE FROM agent.data_agent_visibility_policy WHERE agent_id = ANY(%s)", (list(AGENT_IDS),))
        cur.execute(
            """
            UPDATE agent.data_agent_skill
            SET published_version_id = NULL, latest_draft_version_id = NULL
            WHERE skill_code IN ('waybill-query','bill-query','profit-query')
            """
        )
        cur.execute(
            """
            DELETE FROM agent.data_agent_skill_version WHERE skill_id IN (
              SELECT id FROM agent.data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')
            )
            """
        )
        cur.execute(
            "DELETE FROM agent.data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')"
        )
        cur.execute("DELETE FROM agent.data_agent WHERE id = ANY(%s)", (list(AGENT_IDS),))
        cur.execute("DELETE FROM agent.datasource_column WHERE datasource_id = %s", (LOCAL_DS_ID,))
        cur.execute("DELETE FROM agent.datasource_table WHERE datasource_id = %s", (LOCAL_DS_ID,))
        cur.execute("DELETE FROM agent.logical_relation WHERE datasource_id = %s", (LOCAL_DS_ID,))
        cur.execute("DELETE FROM agent.datasource WHERE id = %s", (LOCAL_DS_ID,))


def migrate_agent_config(src, dst):
    cleanup_agent_config(dst)
    jdbc = (
        f"jdbc:postgresql://{LOCAL_DS_HOST}:{LOCAL_DS_PORT}/{LOCAL_DS_DB}"
        "?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai"
    )
    with src.cursor(cursor_factory=RealDictCursor) as cur:
        agents = fetchall(cur, "SELECT * FROM v4_ai.data_agent WHERE id = ANY(%s)", (list(AGENT_IDS),))
        bindings = fetchall(
            cur,
            """
            SELECT * FROM v4_ai.data_agent_skill_binding
            WHERE agent_id = ANY(%s) AND deleted = false
            """,
            (list(AGENT_IDS),),
        )
        skill_ids = [r["skill_id"] for r in bindings]
        skills = fetchall(cur, "SELECT * FROM v4_ai.data_agent_skill WHERE id = ANY(%s)", (skill_ids,))
        versions = fetchall(
            cur,
            """
            SELECT v.* FROM v4_ai.data_agent_skill_version v
            JOIN v4_ai.data_agent_skill s ON s.published_version_id = v.id
            WHERE s.id = ANY(%s)
            """,
            (skill_ids,),
        )
        skill_ds = fetchall(
            cur,
            "SELECT * FROM v4_ai.skill_datasource WHERE skill_id = ANY(%s) AND deleted = false",
            (skill_ids,),
        )
        skill_tables = fetchall(
            cur,
            """
            SELECT t.* FROM v4_ai.skill_datasource_tables t
            JOIN v4_ai.skill_datasource sd ON sd.id = t.skill_datasource_id
            WHERE sd.skill_id = ANY(%s) AND t.deleted = false
            """,
            (skill_ids,),
        )
        knowledge = fetchall(
            cur,
            "SELECT * FROM v4_ai.business_knowledge WHERE skill_id = ANY(%s) AND deleted = false",
            (skill_ids,),
        )
        for row in knowledge:
            row["superseded_by_id"] = None
        knowledge = unique_latest(knowledge, ("skill_id", "business_term"))
        semantic = fetchall(
            cur,
            "SELECT * FROM v4_ai.semantic_model WHERE skill_id = ANY(%s) AND deleted = false",
            (skill_ids,),
        )
        for row in semantic:
            row["superseded_by_id"] = None
        semantic = unique_latest(semantic, ("skill_id", "datasource_id", "table_name", "column_name"))
        relations = fetchall(
            cur,
            """
            SELECT * FROM v4_ai.logical_relation
            WHERE datasource_id = 3 AND deleted = false
              AND source_table_name = ANY(%s) AND target_table_name = ANY(%s)
            """,
            (TABLES, TABLES),
        )
        ds_tables = fetchall(
            cur,
            """
            SELECT * FROM v4_ai.datasource_table
            WHERE datasource_id = 3 AND deleted = false AND table_name = ANY(%s)
            """,
            (TABLES,),
        )
        ds_cols = fetchall(
            cur,
            """
            SELECT * FROM v4_ai.datasource_column
            WHERE datasource_id = 3 AND deleted = false AND table_name = ANY(%s)
            """,
            (TABLES,),
        )
        presets = fetchall(
            cur,
            "SELECT * FROM v4_ai.agent_preset_question WHERE agent_id = ANY(%s) AND deleted = false",
            (list(AGENT_IDS),),
        )

    datasource = {
        "id": LOCAL_DS_ID,
        "tenant_id": TENANT,
        "name": "Demo Business Data",
        "type": "postgresql",
        "host": LOCAL_DS_HOST,
        "port": LOCAL_DS_PORT,
        "database_name": f"{LOCAL_DS_DB}|{LOCAL_SCHEMA}",
        "username": LOCAL_DS_USER,
        "password": LOCAL_DS_PASSWORD,
        "connection_url": jdbc,
        "status": "active",
        "test_status": "unknown",
        "description": "Local this-month snapshot for order/bill demo skills.",
        "create_by": "sn68",
        "create_name": "sn68",
        "deleted": False,
    }
    insert_mapped(dst, "datasource", [datasource])
    insert_mapped(dst, "datasource_table", ds_tables)
    insert_mapped(dst, "datasource_column", ds_cols)
    insert_mapped(dst, "logical_relation", relations)

    for agent in agents:
        agent["tenant_id"] = TENANT
        agent["api_key"] = None
        agent["api_key_enabled"] = False
        agent["avatar"] = None
        agent["chat_model_config_id"] = None
        agent["admin_id"] = None
        agent["status"] = "published"
    insert_mapped(dst, "data_agent", agents)

    policies = []
    for i, agent_id in enumerate(AGENT_IDS, start=2):
        policies.append(
            {
                "id": agent_id,
                "tenant_id": TENANT,
                "agent_id": agent_id,
                "conversation_scope": "TENANT",
                "catalog_scope": "TENANT",
                "apply_mode": "DISABLED",
                "approval_mode": "LOCAL",
                "status": "ENABLED",
                "create_by": "sn68",
                "create_name": "sn68",
                "deleted": False,
            }
        )
    insert_mapped(dst, "data_agent_visibility_policy", policies)

    for skill in skills:
        skill["tenant_id"] = TENANT
        skill["published_version_id"] = None
        skill["latest_draft_version_id"] = None
        skill["status"] = "PUBLISHED"
    insert_mapped(dst, "data_agent_skill", skills)

    for version in versions:
        version["tenant_id"] = TENANT
        version["status"] = "PUBLISHED"
        version["analysis_config"] = version.get("analysis_config") or {}
        version["revoked"] = False
    insert_mapped(dst, "data_agent_skill_version", versions)

    with dst.cursor() as cur:
        for skill in skills:
            published = next((v["id"] for v in versions if v["skill_id"] == skill["id"]), None)
            cur.execute(
                "UPDATE agent.data_agent_skill SET published_version_id=%s WHERE id=%s",
                (published, skill["id"]),
            )

    insert_mapped(dst, "skill_datasource", skill_ds)
    insert_mapped(dst, "skill_datasource_tables", skill_tables)
    insert_mapped(dst, "business_knowledge", knowledge)
    insert_mapped(dst, "semantic_model", semantic)
    insert_mapped(dst, "data_agent_skill_binding", bindings)
    insert_mapped(dst, "agent_preset_question", presets)
    print(
        "config",
        f"agents={len(agents)} skills={len(skills)} versions={len(versions)} tables={len(skill_tables)}",
        flush=True,
    )
    return {
        "agents": agents,
        "skills": skills,
        "versions": versions,
        "bindings": bindings,
        "skill_ds": skill_ds,
        "skill_tables": skill_tables,
        "knowledge": knowledge,
        "semantic": semantic,
        "relations": relations,
        "ds_tables": ds_tables,
        "ds_cols": ds_cols,
        "presets": presets,
        "datasource": datasource,
        "policies": policies,
    }


def write_seed_sql(bundle):
    path = SQL_DIR / "seed-order-bill-agents.sql"
    lines = [
        "-- Order / bill demo agents + published skills. Tenant default, local datasource.",
        "SET search_path TO agent, public;",
        "",
        "DELETE FROM data_agent_skill_binding WHERE agent_id IN (4, 5);",
        "DELETE FROM skill_datasource_tables WHERE skill_datasource_id IN (SELECT id FROM skill_datasource WHERE skill_id IN (SELECT id FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')));",
        "DELETE FROM skill_datasource WHERE skill_id IN (SELECT id FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query'));",
        "DELETE FROM semantic_model WHERE skill_id IN (SELECT id FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query'));",
        "DELETE FROM business_knowledge WHERE skill_id IN (SELECT id FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query'));",
        "DELETE FROM agent_preset_question WHERE agent_id IN (4, 5);",
        "DELETE FROM data_agent_visibility_policy WHERE agent_id IN (4, 5);",
        "UPDATE data_agent_skill SET published_version_id = NULL, latest_draft_version_id = NULL WHERE skill_code IN ('waybill-query','bill-query','profit-query');",
        "DELETE FROM data_agent_skill_version WHERE skill_id IN (SELECT id FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query'));",
        "DELETE FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query');",
        "DELETE FROM data_agent WHERE id IN (4, 5);",
        "DELETE FROM datasource_column WHERE datasource_id = 3;",
        "DELETE FROM datasource_table WHERE datasource_id = 3;",
        "DELETE FROM logical_relation WHERE datasource_id = 3;",
        "DELETE FROM datasource WHERE id = 3;",
        "",
    ]

    def add(table, rows):
        if not rows:
            return
        cols = list(rows[0].keys())
        lines.extend(emit_inserts(table, rows, cols))
        lines.append("")

    add("datasource", [bundle["datasource"]])
    add("datasource_table", [remap_tenant(r) for r in bundle["ds_tables"]])
    add("datasource_column", [remap_tenant(r) for r in bundle["ds_cols"]])
    add("logical_relation", [remap_tenant(r) for r in bundle["relations"]])
    agents = []
    for agent in bundle["agents"]:
        row = remap_tenant(agent)
        row["api_key"] = None
        row["avatar"] = None
        row["chat_model_config_id"] = None
        row["admin_id"] = None
        agents.append(row)
    add("data_agent", agents)
    add("data_agent_visibility_policy", bundle["policies"])
    skills = []
    for skill in bundle["skills"]:
        row = remap_tenant(skill)
        row["published_version_id"] = None
        row["latest_draft_version_id"] = None
        skills.append(row)
    add("data_agent_skill", skills)
    add("data_agent_skill_version", [remap_tenant(r) for r in bundle["versions"]])
    for skill in bundle["skills"]:
        published = next((v["id"] for v in bundle["versions"] if v["skill_id"] == skill["id"]), None)
        lines.append(
            f"UPDATE data_agent_skill SET published_version_id = {published} WHERE id = {skill['id']};"
        )
    lines.append("")
    add("skill_datasource", [remap_tenant(r) for r in bundle["skill_ds"]])
    add("skill_datasource_tables", [remap_tenant(r) for r in bundle["skill_tables"]])
    add("business_knowledge", [remap_tenant(r) for r in bundle["knowledge"]])
    add("semantic_model", [remap_tenant(r) for r in bundle["semantic"]])
    add("data_agent_skill_binding", [remap_tenant(r) for r in bundle["bindings"]])
    add("agent_preset_question", [remap_tenant(r) for r in bundle["presets"]])
    lines.extend(
        [
            "SELECT setval(pg_get_serial_sequence('data_agent', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent)));",
            "SELECT setval(pg_get_serial_sequence('datasource', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM datasource)));",
            "SELECT setval(pg_get_serial_sequence('data_agent_skill', 'id'), GREATEST(1, (SELECT COALESCE(MAX(id), 1) FROM data_agent_skill)));",
            "",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("wrote", path, flush=True)


def main():
    SQL_DIR.mkdir(parents=True, exist_ok=True)
    src = src_conn()
    dst = dst_conn()
    src.autocommit = True
    try:
        if os.environ.get("DEMO_SKIP_BIZ") != "1":
            id_sets = build_id_sets(src)
            migrate_biz(src, dst, id_sets)
            dst.commit()
        bundle = migrate_agent_config(src, dst)
        dst.commit()
        (ROOT / "data").mkdir(parents=True, exist_ok=True)
        dump_schema_and_data(
            dst,
            SQL_DIR / "demo-biz-schema.sql",
            ROOT / "data" / "demo-biz-data.sql",
        )
        write_seed_sql(bundle)
        with dst.cursor() as cur:
            cur.execute("SET search_path TO agent, public")
            cur.execute("SELECT id, name, status FROM data_agent ORDER BY id")
            print("agents", cur.fetchall(), flush=True)
            cur.execute(
                "SELECT skill_code, skill_name, status FROM data_agent_skill WHERE skill_code IN ('waybill-query','bill-query','profit-query')"
            )
            print("skills", cur.fetchall(), flush=True)
            cur.execute("SELECT count(*) FROM demo_biz.dis_order")
            print("demo_biz.dis_order", cur.fetchone()[0], flush=True)
            cur.execute("SELECT count(*) FROM demo_biz.bill_cost")
            print("demo_biz.bill_cost", cur.fetchone()[0], flush=True)
        print("EXPORT_OK", flush=True)
    except Exception:
        dst.rollback()
        raise
    finally:
        src.close()
        dst.close()


if __name__ == "__main__":
    sys.exit(main())
