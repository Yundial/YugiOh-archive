#!/usr/bin/env python3
"""Genera cards.bin (formato letto dal motore) da un database .cdb di EDOPro.
Stesso formato prodotto dall'app Android (CardRepository.writeEngineBin)."""
import sqlite3, struct, sys
src, dst = sys.argv[1], sys.argv[2]
c = sqlite3.connect(src)
rows = c.execute("select id, alias, setcode, type, level, attribute, race, atk, def, category from datas").fetchall()
with open(dst, "wb") as f:
    f.write(b"YGTC" + struct.pack("<II", 1, len(rows)))
    for r in rows:
        cid, alias, setcode, typ, level, attr, race, atk, dfn, cat = [x or 0 for x in r]
        f.write(struct.pack("<IIQIIIQiiQ", cid & 0xffffffff, alias & 0xffffffff, setcode & 0xffffffffffffffff,
                            typ & 0xffffffff, level & 0xffffffff, attr & 0xffffffff, race & 0xffffffffffffffff,
                            atk, dfn, cat & 0xffffffffffffffff))
print(len(rows), "carte")
