#!/usr/bin/env python3
"""Prova di stabilità: ogni mazzo del bot gioca contro il successivo, per ogni livello."""
import json, os, subprocess, sys, tempfile
exe, cards, scripts, decks = sys.argv[1:5]
d = json.load(open(decks))
tmp = tempfile.mkdtemp()
paths = []
for x in d:
    p = os.path.join(tmp, x["id"] + ".ydk")
    open(p, "w").write("#main\n" + "\n".join(map(str, x["main"])) + "\n#extra\n" + "\n".join(map(str, x["extra"])) + "\n")
    paths.append(p)
bad = 0
for i, p in enumerate(paths):
    q = paths[(i + 1) % len(paths)]
    for lvl in (0, 1, 2):
        out = subprocess.run([exe, cards, scripts, p, q, str(lvl), str(lvl), "2", str(i + 1)], capture_output=True, text=True, timeout=900).stdout
        last = out.strip().splitlines()[-1]
        print(d[i]["id"], "lvl", lvl, last)
        if "bloccate 0" not in last or "!" in out:
            bad += 1
            print(out)
sys.exit(1 if bad else 0)
