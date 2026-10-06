#!/usr/bin/env python3
"""Gnome Heist design sheets: preflight and code generation.

  python3 tools/sheets.py preflight   # list every unfilled cell, bad type, broken reference
  python3 tools/sheets.py gen         # preflight, then write mod/.../gen/Sheets.java

The sheets in sheets/*.json are the source of truth; Sheets.java is generated from them.
"""
import glob
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEETS = os.path.join(ROOT, "sheets")
JAVA_SRC = os.path.join(ROOT, "mod", "src", "main", "java")
GEN_OUT = os.path.join(JAVA_SRC, "gg", "gnomeheist", "gen", "Sheets.java")
ORDER = ["rooms", "places", "loot", "guards", "systems", "hooks"]


def load():
    schema = json.load(open(os.path.join(SHEETS, "_schema.json")))
    schema.pop("_about", None)
    data = {name: json.load(open(os.path.join(SHEETS, name + ".json"))) for name in schema}
    return schema, data


def vanilla_ids():
    """Block/item/entity ids from the Minecraft 1.21.1 client jar Loom downloaded (its en_us.json)."""
    jars = glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/1.21.1/minecraft-client.jar"))
    if not jars:
        return None
    with zipfile.ZipFile(jars[0]) as z:
        lang = json.loads(z.read("assets/minecraft/lang/en_us.json"))
    ids = {"block": set(), "item": set(), "entity": set()}
    for key in lang:
        m = re.fullmatch(r"(block|item|entity)\.minecraft\.([a-z0-9_]+)", key)
        if m:
            ids[m.group(1)].add("minecraft:" + m.group(2))
    ids["item"] |= ids["block"]  # block items share the block's id
    return ids


def preflight(schema, data):
    problems = []
    ids = vanilla_ids()
    if ids is None:
        problems.append("vanilla id list unavailable: run ./gradlew genSources in mod/ first")
    index = {name: {row.get("id"): row for row in rows} for name, rows in data.items()}
    checked = 0

    for name, cols in schema.items():
        seen = set()
        for i, row in enumerate(data[name]):
            rid = row.get("id", f"#{i}")
            if rid in seen:
                problems.append(f"{name}.{rid}: duplicate id")
            seen.add(rid)
            for extra in set(row) - set(cols):
                problems.append(f"{name}.{rid}.{extra}: column not in schema")
            for col, kind in cols.items():
                checked += 1
                where = f"{name}.{rid}.{col}"
                if col not in row or row[col] in ("", None):
                    problems.append(f"{where}: EMPTY")
                    continue
                v = row[col]
                if kind == "str" and not isinstance(v, str):
                    problems.append(f"{where}: expected text")
                elif kind == "int" and not (isinstance(v, int) and not isinstance(v, bool)):
                    problems.append(f"{where}: expected whole number")
                elif kind == "num" and not isinstance(v, (int, float)):
                    problems.append(f"{where}: expected number")
                elif kind == "obj" and not isinstance(v, dict):
                    problems.append(f"{where}: expected object")
                elif kind.startswith("list"):
                    if not isinstance(v, list) or not v:
                        problems.append(f"{where}: expected non-empty list")
                    elif kind.startswith("list:"):
                        sheet = kind.split(":")[1].split(".")[0]
                        for ref in v:
                            if ref not in index[sheet]:
                                problems.append(f"{where}: '{ref}' is not a row in {sheet}")
                elif kind.startswith("ref:"):
                    sheet = kind.split(":")[1].split(".")[0]
                    if v not in index[sheet]:
                        problems.append(f"{where}: '{v}' is not a row in {sheet}")
                elif kind in ("block", "item", "entity") and ids is not None and v not in ids[kind]:
                    problems.append(f"{where}: '{v}' is not a vanilla 1.21.1 {kind}")

    # Cross-sheet rules
    rooms = index["rooms"]
    occupied = {}
    for sheet in ("loot", "guards"):
        for row in data[sheet]:
            room = rooms.get(row.get("room"))
            if not room:
                continue
            x, z = row.get("x"), row.get("z")
            if not (room["x1"] < x < room["x2"] and room["z1"] < z < room["z2"]):
                problems.append(f"{sheet}.{row['id']}: ({x},{z}) is not inside {room['id']}'s interior")
            if (x, row.get("y"), z) in occupied:
                problems.append(f"{sheet}.{row['id']}: same spot as {occupied[(x, row.get('y'), z)]}")
            occupied[(x, row.get("y"), z)] = f"{sheet}.{row['id']}"
    for row in data["loot"]:
        if row.get("kind") not in ("block", "statue"):
            problems.append(f"loot.{row['id']}.kind: must be block or statue")
    for room in data["rooms"]:
        for d in room.get("doorways", []):
            on_wall = (d[0] in (room["x1"], room["x2"]) and room["z1"] <= d[1] <= room["z2"]) or \
                      (d[1] in (room["z1"], room["z2"]) and room["x1"] <= d[0] <= room["x2"])
            if not on_wall:
                problems.append(f"rooms.{room['id']}.doorways: {d} is not on its wall")
    place_ids = set(index["places"])
    for need in ("van", "drop_zone", "start"):
        if need not in place_ids:
            problems.append(f"places: missing required row '{need}'")
    for s in data["systems"]:
        zone = s.get("params", {}).get("zone")
        if zone and zone not in place_ids:
            problems.append(f"systems.{s['id']}.params.zone: '{zone}' is not a row in places")
    # Every hook row must be wired to a handler method that exists in the Java source.
    java = ""
    for path in glob.glob(os.path.join(JAVA_SRC, "**", "*.java"), recursive=True):
        if not path.endswith("Sheets.java"):
            java += open(path).read()
    for h in data["hooks"]:
        if not re.search(r"\b" + re.escape(h["handler"]) + r"\s*\(", java):
            problems.append(f"hooks.{h['id']}.handler: method {h['handler']}() not implemented")
        elif h["event"].split(".")[0] not in java:
            problems.append(f"hooks.{h['id']}.event: {h['event']} is never registered")
        used = any(h["id"] in s["hooks"] for s in data["systems"])
        if not used:
            problems.append(f"hooks.{h['id']}: no system uses it")
    for s in data["systems"]:
        if f'"{s["id"]}"' not in java and f"SYSTEM_{s['id'].upper()}" not in java:
            problems.append(f"systems.{s['id']}: not referenced in code (Sys.{s['id'].upper()})")
    return problems, checked


def jstr(s):
    return json.dumps(s)


def jnum(v):
    return f"{float(v)}"


def gen(data):
    out = ["// GENERATED by tools/sheets.py from sheets/*.json. Edit the sheets, not this file.",
           "package gg.gnomeheist.gen;", "", "import java.util.List;", "import java.util.Map;", "",
           "public final class Sheets {", "    private Sheets() {}", ""]
    out += ["    public record Room(String id, String name, int x1, int z1, int x2, int z2, String floor, String wall, List<int[]> doorways) {}",
            "    public record Place(String id, String name, int x1, int y1, int z1, int x2, int y2, int z2) {}",
            "    public record Loot(String id, String name, int points, String kind, String block, String room, int x, int y, int z) {}",
            "    public record Guard(String id, String name, String entity, double health, double speed, double followRange, int stunSeconds, String room, int x, int y, int z, String helmet, String mainhand) {}",
            "    public record Sys(String id, String name, Map<String, Object> params) {}", ""]

    rows = [f"        new Room({jstr(r['id'])}, {jstr(r['name'])}, {r['x1']}, {r['z1']}, {r['x2']}, {r['z2']}, {jstr(r['floor'])}, {jstr(r['wall'])}, List.of("
            + ", ".join(f"new int[]{{{d[0]}, {d[1]}}}" for d in r["doorways"]) + "))" for r in data["rooms"]]
    out += ["    public static final List<Room> ROOMS = List.of(", ",\n".join(rows), "    );", ""]
    rows = [f"        new Place({jstr(p['id'])}, {jstr(p['name'])}, {p['x1']}, {p['y1']}, {p['z1']}, {p['x2']}, {p['y2']}, {p['z2']})" for p in data["places"]]
    out += ["    public static final List<Place> PLACES = List.of(", ",\n".join(rows), "    );", ""]
    rows = [f"        new Loot({jstr(l['id'])}, {jstr(l['name'])}, {l['points']}, {jstr(l['kind'])}, {jstr(l['block'])}, {jstr(l['room'])}, {l['x']}, {l['y']}, {l['z']})" for l in data["loot"]]
    out += ["    public static final List<Loot> LOOT = List.of(", ",\n".join(rows), "    );", ""]
    rows = [f"        new Guard({jstr(g['id'])}, {jstr(g['name'])}, {jstr(g['entity'])}, {jnum(g['health'])}, {jnum(g['speed'])}, {jnum(g['followRange'])}, {g['stunSeconds']}, {jstr(g['room'])}, {g['x']}, {g['y']}, {g['z']}, {jstr(g['helmet'])}, {jstr(g['mainhand'])})" for g in data["guards"]]
    out += ["    public static final List<Guard> GUARDS = List.of(", ",\n".join(rows), "    );", ""]

    def jval(v):
        if isinstance(v, str):
            return jstr(v)
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, int):
            return f"{v}"
        return jnum(v)

    for s in data["systems"]:
        params = ", ".join(f"{jstr(k)}, {jval(v)}" for k, v in s["params"].items())
        out.append(f"    public static final Sys SYSTEM_{s['id'].upper()} = new Sys({jstr(s['id'])}, {jstr(s['name'])}, Map.of({params}));")
    out += ["", "    public static Place place(String id) {",
            "        return PLACES.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();", "    }",
            "", "    public static int intParam(Sys s, String key) {", "        return ((Number) s.params().get(key)).intValue();", "    }",
            "", "    public static double numParam(Sys s, String key) {", "        return ((Number) s.params().get(key)).doubleValue();", "    }", "}", ""]
    os.makedirs(os.path.dirname(GEN_OUT), exist_ok=True)
    open(GEN_OUT, "w").write("\n".join(out))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "preflight"
    schema, data = load()
    if cmd == "gen":
        gen(data)  # generate first so handler checks see the current code
        print(f"wrote {os.path.relpath(GEN_OUT, ROOT)}")
    problems, checked = preflight(schema, data)
    total_rows = sum(len(data[n]) for n in schema)
    print(f"preflight: {total_rows} rows, {checked} cells checked, {len(problems)} problem(s)")
    for p in problems:
        print("  - " + p)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
