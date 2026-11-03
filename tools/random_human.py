#!/usr/bin/env python3
"""Fa giocare un 'umano' casuale contro il bot passando dal JSON, come fa l'app Android."""
import json, random, subprocess, sys
exe, cards, scripts, mine, bot, diff, seed = sys.argv[1:8]
rnd = random.Random(int(seed))
p = subprocess.Popen([exe, cards, scripts, mine, bot, "1", diff, "interactive", seed], stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, text=True, bufsize=1)
stats = {"prompts": 0, "errors": 0, "kinds": {}}
last_err_streak = 0
while True:
    line = p.stdout.readline()
    if not line: break
    v = json.loads(line)  # deve essere JSON valido
    if "end" in v:
        print("FINE", v["end"], "turni", v["state"]["turn"], "LP", [x["lp"] for x in v["state"]["p"]], stats)
        break
    pr = v.get("prompt")
    if pr is None:
        print("NESSUNA RICHIESTA senza fine!", v.get("error")); break
    k = pr["k"]; stats["prompts"] += 1; stats["kinds"][k] = stats["kinds"].get(k, 0) + 1
    if "error" in v: stats["errors"] += 1; last_err_streak += 1
    else: last_err_streak = 0
    if last_err_streak > 40:
        print("TROPPI ERRORI su", k, json.dumps(pr)[:400]); p.stdin.write("surrender\n"); p.stdin.flush(); continue
    r = {"a": 0, "b": 0}
    if k in ("idle", "battle"):
        opts = []
        lists = ["summon", "sp", "repos", "mset", "sset", "act"] if k == "idle" else ["act", "atk"]
        for t, key in enumerate(lists):
            for i, _ in enumerate(pr.get(key, [])): opts.append((t, i))
        ends = []
        if k == "idle":
            if pr.get("bp"): ends.append((6, 0))
            if pr.get("ep"): ends.append((7, 0))
        else:
            if pr.get("m2"): ends.append((2, 0))
            if pr.get("ep"): ends.append((3, 0))
        pick = rnd.choice(opts) if opts and (rnd.random() < 0.6 or not ends) else rnd.choice(ends or opts)
        r = {"a": pick[0], "b": pick[1]}
    elif k in ("effyn", "yesno"): r = {"a": rnd.randint(0, 1)}
    elif k in ("option", "number"): r = {"a": rnd.randrange(max(1, len(pr["opts"])))}
    elif k in ("card", "tribute", "sum"):
        n = len(pr["cards"]); mn = max(pr["min"], 1); mx = max(min(pr["max"], n), mn)
        cnt = min(n, rnd.randint(mn, mx)) if n else 0
        if pr.get("cancel") and last_err_streak > 3: r = {"a": -1}
        else: r = {"a": 0, "idx": rnd.sample(range(n), cnt)}
    elif k == "unselect":
        if pr.get("finish") and rnd.random() < 0.5 or not pr["cards"]: r = {"a": -1} if (pr.get("finish") or pr.get("cancel")) else {"a": 1, "b": 0}
        else: r = {"a": 1, "b": rnd.randrange(len(pr["cards"]))}
    elif k == "chain":
        if pr["forced"] or rnd.random() < 0.4: r = {"a": rnd.randrange(len(pr["cards"]))}
        else: r = {"a": -1}
    elif k == "place":
        z = pr["zones"]; sel = rnd.sample(z, min(len(z), pr["count"]))
        r = {"places": [x for zz in sel for x in (zz["pl"], zz["l"], zz["s"])]}
    elif k == "position":
        for b in (1, 4, 8, 2):
            if pr["pos"] & b: r = {"a": b}; break
    elif k == "counter":
        rem = pr["count"]; idx = []
        for c in pr["cards"]: t = min(c["x"], rem); idx.append(t); rem -= t
        r = {"idx": idx}
    elif k in ("race", "attrib"):
        bits = [1 << i for i in range(64) if pr["avail"] >> i & 1]; m = 0
        for b in rnd.sample(bits, min(len(bits), pr["count"])): m |= b
        r = {"mask": m}
    elif k == "anncard": r = {"a": rnd.choice([89631139, 46986414, 55144522, 83764718, 5318639])}
    elif k == "rps": r = {"a": rnd.randint(1, 3)}
    else: print("TIPO SCONOSCIUTO", k)
    p.stdin.write(json.dumps(r) + "\n"); p.stdin.flush()
p.wait()
