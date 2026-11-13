"""Generates qbm_mtk_tables.h: the MediaTek ICD layouts qbm-mtk.c decodes, and the FM350's
original DHL trace filter tables (for "Restore MTK DIAG profile").

    python gen_mtk_tables.py <icd.json> [<icd.json> ...] <trace-profile dir> <out .h>

<icd.json>: the ICD database extracted from a firmware's MDDB .EDB (mtk-band-menu
prototype/mt6880-81600.0000.00.<firmware>-icd.json): per log a code, a root table (Version, then
a version switch) and struct tables of rows [Field, Type Name, Count, Offset, Length(bits), ...].
Count is a number or "N" (the table's "Ref" map names the field holding N); Length is a bit
count or "VAR" (always last).

Several databases (one per firmware) are merged. A record's version byte selects its layout, so
the header carries the union of the versions; a newer firmware only adds versions. A struct table
that two databases define with a different layout (field, type, count, offset, length or Ref)
is an error, since the decoder could not tell which one a record uses.

<trace-profile dir>: the modem's original filter tables as read from its native connect dump
(cmd-05/06/09/0a-*.bin, original-nr-filter.bin), firmware 81600.0000.00.19.15.13.
"""
import json
import sys
from pathlib import Path

# The logs qbm-mtk.c decodes (qbm-icd-codes.json plus the MAC TB logs for TB counting and
# the CSI-RS measurement behind the antenna card's CRSRP column).
RECORDS = [0x9001, 0x9002, 0x9003, 0x9006, 0x9007, 0x9009, 0x900E, 0x9011, 0x9014, 0x9015,
           0x9016, 0x9023, 0x9083, 0x9084, 0x9101, 0x7001, 0x7002, 0x7003, 0x7006, 0x7007,
           0x7008, 0x700A, 0x700C, 0x700D, 0x7012, 0x7013, 0x7014, 0x7015, 0x7018, 0x7102,
           0x7104, 0x7105]
EVENTS = [0x960D]
PRIM = {'UINT8': 1, 'UINT16': 2, 'UINT32': 3, 'UINT64': 4, 'INT8': 5, 'INT16': 6, 'INT32': 7}
TRACE = [('05', 'cmd-05-primitive_modules.bin', 251), ('06', 'cmd-06-primitive_saps.bin', 1950),
         ('09', 'cmd-09-ps_traces.bin', 16016), ('0a', 'cmd-0a-l1_traces.bin', 2256),
         ('nr', 'original-nr-filter.bin', 128)]


def layout(t):
    return [tuple(r[:5]) for r in t['Data'].values()], t.get('Ref') or {}


def main(db_paths, trace_dir, out_path):
    dbs = []
    for db_path in db_paths:
        logs = json.loads(Path(db_path).read_text(encoding='utf-8'))['logs']
        tables = {}
        for entry in logs.values():
            tables.update(entry['table'])
        by_code = {int(e['info']['Code'], 16): n for n, e in logs.items() if e['info'].get('Code')}
        dbs.append((logs, tables, by_code))

    # Struct tables of all databases (the first one's text wins); a layout clash cannot be merged.
    tables, clash = {}, set()
    for _, db_tables, _ in dbs:
        for tname, t in db_tables.items():
            if tname not in tables:
                tables[tname] = t
            elif layout(tables[tname]) != layout(t):
                clash.add(tname)

    order, index = [], {}

    def visit(tname):
        if tname in index:
            return
        if tname in clash:
            raise SystemExit(f'{tname}: layout differs between the ICD databases')
        index[tname] = len(order)
        order.append(tname)
        for row in tables[tname]['Data'].values():
            if row[1] not in PRIM:
                visit(row[1])

    versions, chosen = [], {}
    for code in RECORDS + EVENTS:
        for logs, db_tables, by_code in dbs:
            root = next(iter(logs[by_code[code]]['table'].values()))
            switch = db_tables[list(root['Data'].values())[1][1]]
            for r in switch['Data'].values():
                key = (code, int(r[0]))
                if key in chosen:
                    if chosen[key] != r[1]:
                        raise SystemExit(f'0x{code:04X} version {key[1]}: {chosen[key]} vs {r[1]}')
                    continue
                chosen[key] = r[1]
                visit(r[1])
                versions.append((code, int(r[0]), r[1]))

    names, name_at = [], {}

    def name_id(s):
        if s not in name_at:
            name_at[s] = sum(len(n) + 1 for n in names)
            names.append(s)
        return name_at[s]

    rows, tabs = [], []
    for tname in order:
        t = tables[tname]
        data = list(t['Data'].values())
        fields = [r[0] for r in data]
        refs = t.get('Ref') or {}
        first = len(rows)
        for field, typ, count, offset, length, *_ in data:
            prim = PRIM.get(typ, 0)
            sub = 0 if prim else index[typ]
            if count == 'N':
                ref = fields.index(refs[field])
                cnt = 0xFFFF
                assert ref < 256, f'{tname}.{field}: count row {ref} does not fit uint8_t'
            else:
                ref, cnt = 0, int(count)
            ln = 0xFFFFFFFF if length == 'VAR' else int(length)
            rows.append((name_id(field), prim, sub, cnt, ref, int(offset), ln))
        tabs.append((first, len(data)))

    pool = '\\0'.join(n.replace('\\', '\\\\').replace('"', '\\"') for n in names) + '\\0'
    trace = {}
    for key, fname, size in TRACE:
        data = (Path(trace_dir) / fname).read_bytes()
        if len(data) != size:
            raise SystemExit(f'{fname}: {len(data)} bytes, expected {size}')
        trace[key] = data

    def c_bytes(name, data):
        lines = [f'static const uint8_t {name}[{len(data)}] = {{']
        for i in range(0, len(data), 24):
            lines.append('    ' + ','.join(str(b) for b in data[i:i + 24]) + ',')
        lines.append('};')
        return '\n'.join(lines)

    out = [
        '/*',
        ' * qbm_mtk_tables.h: generated by tools/gen_mtk_tables.py; do not edit.',
        ' * MediaTek ICD layouts for the logs qbm-mtk.c decodes, merged from the FM350 MDDB ICD',
        ' * databases ' + ', '.join(Path(p).name for p in db_paths) + ',',
        ' * and firmware 81600.0000.00.19.15.13\'s original DHL trace filter tables.',
        ' */',
        '',
        '/* prim: 0 struct (sub = table), 1-4 UINT8/16/32/64, 5-7 INT8/16/32. count 0xFFFF:',
        ' * the value of row `ref` (same table). len 0xFFFFFFFF: VAR (to the end). Bits LSB-first. */',
        'struct mtk_row { uint16_t name; uint8_t prim, ref; uint16_t sub, count; uint32_t off, len; };',
        'struct mtk_table { uint16_t first, rows; };',
        'struct mtk_version { uint16_t code; uint8_t version; uint16_t table; };',
        '',
        f'static const char MTK_NAMES[] = "{pool}";',
        '',
        f'static const struct mtk_row MTK_ROWS[{len(rows)}] = {{',
    ]
    for n, prim, sub, cnt, ref, off, ln in rows:
        out.append(f'    {{ {n}, {prim}, {ref}, {sub}, {cnt}, {off}, {ln}u }},')
    out.append('};')
    out.append('')
    out.append(f'static const struct mtk_table MTK_TABLES[{len(tabs)}] = {{')
    out.extend(f'    {{ {f}, {n} }},' for f, n in tabs)
    out.append('};')
    out.append('')
    out.append(f'static const struct mtk_version MTK_VERSIONS[{len(versions)}] = {{')
    out.extend(f'    {{ 0x{c:04X}, {v}, {index[t]} }},' for c, v, t in versions)
    out.append('};')
    out.append('')
    out.append('#define MTK_RECORD_CODES ' + ', '.join(f'0x{c:04X}' for c in RECORDS))
    out.append('#define MTK_EVENT_CODES ' + ', '.join(f'0x{c:04X}' for c in EVENTS))
    out.append('')
    out.append('/* Original trace filter tables (cmd 05 modules, 06 SAPs, 09 PS, 0A MCU L1; NR DSP masks). */')
    for key, data in trace.items():
        out.append(c_bytes(f'MTK_TRACE_ORIG_{key.upper()}', data))
    Path(out_path).write_text('\n'.join(out) + '\n', encoding='utf-8', newline='\n')
    print(f'{len(order)} tables, {len(rows)} rows, {len(versions)} versions, names {len(pool)} chars')


if __name__ == '__main__':
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    main(sys.argv[1:-2], sys.argv[-2], sys.argv[-1])
