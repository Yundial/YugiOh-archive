#include "bot.h"
#include <algorithm>
#include <numeric>
#include <stack>
#include <chrono>

namespace ygt {

namespace {
// suggerimenti di selezione (strings.conf, !system 5xx)
bool hintNegative(uint64_t h) {
	switch(h) {
	case 500: case 501: case 502: case 503: case 505: case 507: case 519: case 520:
	case 521: case 528: case 530: case 531: case 532: case 570: case 575: case 576:
		return true;
	default: return false;
	}
}
bool hintMaterial(uint64_t h) { return h == 511 || h == 512 || h == 513 || h == 533 || h == 578 || h == 500 || h == 531; }
bool hintPositive(uint64_t h) {
	switch(h) {
	case 506: case 508: case 509: case 510: case 518: case 527: case 529: case 573: case 577:
		return true;
	default: return false;
	}
}
constexpr uint64_t HINT_ATTACK_TARGET = 549;
}

Bot::Bot(int difficulty, const CardDB& d, uint64_t seed) : diff(difficulty), db(d), rng(seed) {}

void Bot::onNewTurn(int tp) {
	turnPlayer = tp;
	usedThisTurn.clear();
	extraSummonsThisTurn = 0;
	attackTried.clear();
	actionsThisPhase = 0;
	pendingSummonBy = -1;
}
void Bot::onNewPhase(uint32_t ph) {
	phase = ph;
	actionsThisPhase = 0;
	if(ph == PHASE_BATTLE_START || ph == PHASE_BATTLE_STEP) attackTried.clear();
}
void Bot::onSummoning(int player) { pendingSummonBy = player; }
void Bot::onSummoned() { pendingSummonBy = -1; }
void Bot::onChainEnd() { attackBy = -1; }
void Bot::onAttack(int player) { attackBy = player; }
void Bot::onSeenCard(int player, uint32_t code) { (void)player; if(code) seenOpp.insert(code); }

int64_t Bot::value(uint32_t code, const FCard* fc) const {
	const CardInfo* ci = db.get(code);
	if(fc && fc->exists && fc->isMonster() && (fc->loc & LOC_MZONE))
		return 1000 + fc->atk + 100 * fc->stars() + (fc->overlays * 300);
	if(!ci) return 500;
	if(ci->isMonster()) {
		int64_t v = 800 + std::max(ci->atk, 0) + 100 * (ci->level & 0xff);
		if(ci->isExtra()) v += 800;
		return v;
	}
	return 1200; // magie/trappole: valore medio fisso
}

int32_t Bot::atkOf(const CardRef& c, const FieldState& f) const {
	if(const FCard* fc = f.find(c.con, c.loc, c.seq); fc && fc->exists && (c.loc & LOC_MZONE))
		return fc->atk;
	if(const CardInfo* ci = db.get(c.code)) return ci->atk;
	return 0;
}

int32_t Bot::oppMaxAtk(const FieldState& f, int me) const {
	int32_t m = 0;
	for(auto& c : f.p[1 - me].mzone)
		if(c.exists) m = std::max(m, c.faceup() ? std::max(c.atk, (c.pos & POS_DEFENSE) ? c.def : 0) : 1500);
	return m;
}

bool Bot::isNegation(uint32_t code) const {
	const CardInfo* ci = db.get(code);
	return ci && (ci->category & (CAT_NEGATE_EFFECT | CAT_NEGATE_SUMMON));
}

bool Bot::goodTarget(int32_t myAtk, const FCard& t) const {
	if(!t.exists) return false;
	if(!t.faceup()) return myAtk > (diff == 2 ? 1900 : 1500);
	if(t.pos & POS_ATTACK) return diff == 2 ? myAtk >= t.atk && myAtk > 0 : myAtk > t.atk;
	return myAtk > t.def;
}

Choice Bot::decide(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	switch(p.type) {
	case MSG_SELECT_IDLECMD: return idle(p, f, me);
	case MSG_SELECT_BATTLECMD: return battle(p, f, me);
	case MSG_SELECT_CHAIN: return chain(p, f, me);
	case MSG_SELECT_EFFECTYN:
		c.a = diff == 0 ? (chance(0.7) ? 1 : 0) : 1;
		return c;
	case MSG_SELECT_YESNO:
		if(p.desc == 31) { c.a = 1; return c; } // attacco diretto
		if(p.desc == 210 || p.desc == 218 || p.desc == 219 || p.desc == 220) { c.a = 0; return c; }
		c.a = diff == 0 ? (chance(0.7) ? 1 : 0) : 1;
		return c;
	case MSG_SELECT_OPTION:
		c.a = diff == 0 ? rnd((int)p.options.size()) : 0;
		return c;
	case MSG_SELECT_CARD: return selectCards(p, f, me);
	case MSG_SELECT_UNSELECT_CARD: return unselect(p, f, me);
	case MSG_SELECT_TRIBUTE: return tribute(p, f, me);
	case MSG_SELECT_SUM: return sum(p, f, me);
	case MSG_SELECT_PLACE:
	case MSG_SELECT_DISFIELD: return place(p, f, me);
	case MSG_SELECT_POSITION: return position(p, f, me);
	case MSG_SELECT_COUNTER: return counter(p);
	case MSG_SORT_CARD:
	case MSG_SORT_CHAIN: return c; // ordine predefinito
	case MSG_ANNOUNCE_RACE: return announceMask(p, f, me, true);
	case MSG_ANNOUNCE_ATTRIB: return announceMask(p, f, me, false);
	case MSG_ANNOUNCE_CARD: return announceCard(p, f, me);
	case MSG_ANNOUNCE_NUMBER:
		c.a = diff == 0 ? rnd((int)p.options.size()) : 0;
		return c;
	case MSG_ROCK_PAPER_SCISSORS:
		c.a = 1 + rnd(3);
		return c;
	default:
		return c;
	}
}

// ------------------------------------------------------------------ IDLE
Choice Bot::idle(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	++actionsThisPhase;
	const int cap = diff == 0 ? 8 : diff == 1 ? 20 : 40;
	const bool main2 = phase == PHASE_MAIN2;
	const auto& mine = f.p[me];
	const auto& opp = f.p[1 - me];
	const int32_t oppMax = oppMaxAtk(f, me);
	auto endPhase = [&]() {
		Choice e;
		if(!main2 && p.toBP && f.turn > 1) { e.a = 6; return e; }
		if(p.toEP) { e.a = 7; return e; }
		if(p.toBP) { e.a = 6; return e; }
		// nessun passaggio di fase possibile: serve un'azione
		if(!p.activate.empty()) { e.a = 5; e.b = 0; return e; }
		if(!p.summon.empty()) { e.a = 0; e.b = 0; return e; }
		if(!p.spsummon.empty()) { e.a = 1; e.b = 0; return e; }
		e.a = 7;
		return e;
	};
	if(actionsThisPhase > cap) return endPhase();
	(void)mine; (void)opp; (void)oppMax;

	// ---------------- FACILE: gioca "a caso", per divertirsi
	if(diff == 0) {
		struct Opt { int a, b; double w; };
		std::vector<Opt> opts;
		for(size_t i = 0; i < p.activate.size(); ++i)
			if(usedThisTurn[key(p.activate[i].code, p.activate[i].desc)] < 1) opts.push_back({5, (int)i, 2.0});
		for(size_t i = 0; i < p.summon.size(); ++i) opts.push_back({0, (int)i, 3.0});
		for(size_t i = 0; i < p.spsummon.size(); ++i)
			if(usedThisTurn[key(p.spsummon[i].code, 1)] < 1) opts.push_back({1, (int)i, 1.0});
		for(size_t i = 0; i < p.sset.size(); ++i) {
			const CardInfo* ci = db.get(p.sset[i].code);
			if(ci && (ci->type & TYPE_TRAP)) opts.push_back({4, (int)i, 1.0});
		}
		for(size_t i = 0; i < p.mset.size(); ++i) opts.push_back({3, (int)i, 0.4});
		if(opts.empty() || chance(0.2)) return endPhase();
		double tot = 0; for(auto& o : opts) tot += o.w;
		double r = std::uniform_real_distribution<double>(0, tot)(rng);
		for(auto& o : opts) {
			r -= o.w;
			if(r <= 0) {
				if(o.a == 5) usedThisTurn[key(p.activate[o.b].code, p.activate[o.b].desc)]++;
				if(o.a == 1) usedThisTurn[key(p.spsummon[o.b].code, 1)]++;
				c.a = o.a; c.b = o.b; return c;
			}
		}
		return endPhase();
	}
	if(diff == 2 && lookahead && (phase == PHASE_MAIN1 || phase == PHASE_MAIN2) && f.turnPlayer == me)
		return idleSearch(p, f, me);
	return idleHeuristic(p, f, me);
}

Choice Bot::idleHeuristic(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	const bool main2 = phase == PHASE_MAIN2;
	const auto& mine = f.p[me];
	const auto& opp = f.p[1 - me];
	const int32_t oppMax = oppMaxAtk(f, me);
	auto endPhase = [&]() {
		Choice e;
		if(!main2 && p.toBP && f.turn > 1) { e.a = 6; return e; }
		if(p.toEP) { e.a = 7; return e; }
		if(p.toBP) { e.a = 6; return e; }
		if(!p.activate.empty()) { e.a = 5; e.b = 0; return e; }
		if(!p.summon.empty()) { e.a = 0; e.b = 0; return e; }
		if(!p.spsummon.empty()) { e.a = 1; e.b = 0; return e; }
		e.a = 7;
		return e;
	};
	int32_t myMaxAtk = 0;
	for(auto& m : mine.mzone) if(m.exists && m.faceup()) myMaxAtk = std::max(myMaxAtk, m.atk);

	// ---------------- MEDIO / DIFFICILE
	const int actLimit = diff == 2 ? 2 : 1;
	// 1) effetti e carte da attivare
	for(size_t i = 0; i < p.activate.size(); ++i) {
		const auto& a = p.activate[i];
		auto k = key(a.code, a.desc);
		if(usedThisTurn[k] >= actLimit) continue;
		const CardInfo* ci = db.get(a.code);
		if(ci) {
			if(isNegation(a.code) && !(ci->type & TYPE_MONSTER)) continue; // negazioni: tienile per l'avversario
			// "Buco Nero" e simili: solo se conviene
			if((ci->category & CAT_DESTROY_MONSTER) && (ci->type & TYPE_SPELL) && a.loc == LOC_HAND &&
			   mine.monsterCount() > 0 && mine.monsterCount() >= opp.monsterCount())
				continue;
			if((ci->category & CAT_DESTROY_ST) && (ci->type & (TYPE_SPELL | TYPE_TRAP)) && opp.stCount() == 0 && !opp.szone[5].exists)
				continue;
			// medio: non attiva trappole già piazzate nel proprio turno (le tiene come risposta)
			if(diff == 1 && (ci->type & TYPE_TRAP) && (a.loc & LOC_SZONE) && !(ci->category & CAT_DESTROY_ST))
				continue;
		}
		usedThisTurn[k]++;
		c.a = 5; c.b = (int)i;
		return c;
	}
	// 2) evocazioni speciali
	{
		int best = -1; int64_t bestScore = INT64_MIN;
		const int extraCap = diff == 2 ? 10 : 3;
		for(size_t i = 0; i < p.spsummon.size(); ++i) {
			const auto& s = p.spsummon[i];
			auto k = key(s.code, 1);
			if(usedThisTurn[k] >= (diff == 2 ? 2 : 1)) continue;
			const CardInfo* ci = db.get(s.code);
			int32_t atk = ci ? ci->atk : 0;
			bool fromExtra = s.loc == LOC_EXTRA;
			if(fromExtra) {
				if(extraSummonsThisTurn >= extraCap) continue;
				if(diff == 1 && atk < 2000 && atk <= myMaxAtk) continue;
			}
			int64_t sc = atk + (fromExtra ? 500 : 0) + (ci && (ci->type & TYPE_LINK) && diff == 2 ? 300 : 0);
			if(sc > bestScore) { bestScore = sc; best = (int)i; }
		}
		if(best >= 0 && !main2) {
			const auto& s = p.spsummon[best];
			usedThisTurn[key(s.code, 1)]++;
			if(s.loc == LOC_EXTRA) ++extraSummonsThisTurn;
			c.a = 1; c.b = best;
			return c;
		}
	}
	// 3) evocazione normale / posizionamento
	if(!main2 || mine.monsterCount() == 0) {
		std::vector<int64_t> fieldVals;
		for(auto& m : mine.mzone) if(m.exists) fieldVals.push_back(value(m.code, &m));
		std::sort(fieldVals.begin(), fieldVals.end());
		int best = -1; int64_t bestV = INT64_MIN;
		for(size_t i = 0; i < p.summon.size(); ++i) {
			const CardInfo* ci = db.get(p.summon[i].code);
			if(!ci) continue;
			int lv = ci->level & 0xff;
			int need = lv >= 7 ? 2 : lv >= 5 ? 1 : 0;
			if(need > 0) {
				if((int)fieldVals.size() < need) continue;
				int64_t lost = 0; for(int j = 0; j < need; ++j) lost += fieldVals[j];
				if(value(p.summon[i].code) <= lost) continue;
			}
			int64_t v = value(p.summon[i].code);
			if(v > bestV) { bestV = v; best = (int)i; }
		}
		if(best >= 0) {
			const CardInfo* ci = db.get(p.summon[best].code);
			bool weak = ci && ci->atk < oppMax && ci->atk < 1800;
			if(weak && !p.mset.empty()) {
				int bm = 0; int32_t bd = -1;
				for(size_t i = 0; i < p.mset.size(); ++i) {
					const CardInfo* m = db.get(p.mset[i].code);
					if(m && (m->level & 0xff) <= 4 && m->def > bd) { bd = m->def; bm = (int)i; }
				}
				if(bd >= 0) { c.a = 3; c.b = bm; return c; }
			}
			c.a = 0; c.b = best;
			return c;
		}
	}
	// 4) piazza trappole (e magie rapide al livello difficile)
	for(size_t i = 0; i < p.sset.size(); ++i) {
		const CardInfo* ci = db.get(p.sset[i].code);
		if(!ci) continue;
		bool setIt = (ci->type & TYPE_TRAP) || (diff == 2 && (ci->type & TYPE_QUICKPLAY));
		if(setIt) { c.a = 4; c.b = (int)i; return c; }
	}
	// 5) cambio posizione
	for(size_t i = 0; i < p.repos.size(); ++i) {
		const auto& r = p.repos[i];
		auto k = key(r.code, 0x9999 + r.seq);
		if(usedThisTurn[k]) continue;
		const FCard* fc = f.find(r.con, r.loc, r.seq);
		if(!fc || !fc->exists) continue;
		bool wantAtk = fc->atk > oppMax || opp.monsterCount() == 0;
		if(main2) wantAtk = fc->atk >= oppMax;
		bool isAtk = fc->pos & POS_ATTACK;
		if(wantAtk != isAtk && !(fc->pos & POS_FACEDOWN && !wantAtk)) {
			usedThisTurn[k]++;
			c.a = 2; c.b = (int)i;
			return c;
		}
	}
	return endPhase();
}

// ------------------------------------------------------------------ IDLE con ricerca (Difficile)
Choice Bot::idleSearch(const Prompt& p, const FieldState& f, int me) {
	using clock = std::chrono::steady_clock;
	const auto t0 = clock::now();
	auto savedUsed = usedThisTurn; int savedExtra = extraSummonsThisTurn;
	Choice heur = idleHeuristic(p, f, me);
	usedThisTurn = savedUsed; extraSummonsThisTurn = savedExtra;
	std::vector<Choice> cands;
	auto add = [&](int a, int b) {
		for(auto& c : cands) if(c.a == a && c.b == b) return;
		Choice c; c.a = a; c.b = b; cands.push_back(c);
	};
	add(heur.a, heur.b);
	for(size_t i = 0; i < p.activate.size(); ++i) add(5, (int)i);
	for(size_t i = 0; i < p.spsummon.size(); ++i) add(1, (int)i);
	for(size_t i = 0; i < p.summon.size(); ++i) add(0, (int)i);
	// posizionamento coperto: solo il mostro con più DIFESA
	{
		int bm = -1; int32_t bd = -1;
		for(size_t i = 0; i < p.mset.size(); ++i) {
			const CardInfo* m = db.get(p.mset[i].code);
			if(m && m->def > bd) { bd = m->def; bm = (int)i; }
		}
		if(bm >= 0) add(3, bm);
	}
	for(size_t i = 0; i < p.sset.size(); ++i) {
		const CardInfo* ci = db.get(p.sset[i].code);
		if(ci && (ci->type & TYPE_TRAP)) { add(4, (int)i); break; }
	}
	if(p.toBP && f.turn > 1 && phase == PHASE_MAIN1) add(6, 0);
	if(p.toEP) add(7, 0);
	if(cands.size() <= 1) return heur;
	double best = -1e18; int bestIdx = 0;
	for(size_t i = 0; i < cands.size(); ++i) {
		if(i > 0 && std::chrono::duration<double, std::milli>(clock::now() - t0).count() > thinkMs) break;
		double v = lookahead(cands[i]);
		if(v > best + 1e-6) { best = v; bestIdx = (int)i; }
	}
	Choice c = cands[bestIdx];
	if(c.a == 5) usedThisTurn[key(p.activate[c.b].code, p.activate[c.b].desc)]++;
	if(c.a == 1) { usedThisTurn[key(p.spsummon[c.b].code, 1)]++; if(p.spsummon[c.b].loc == LOC_EXTRA) ++extraSummonsThisTurn; }
	return c;
}

// ------------------------------------------------------------------ BATTLE
Choice Bot::battle(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	const auto& opp = f.p[1 - me];
	int oppMonsters = opp.monsterCount();
	int best = -1; int64_t bestScore = 0;
	for(size_t i = 0; i < p.attack.size(); ++i) {
		const auto& a = p.attack[i];
		uint64_t k = ((uint64_t)a.code << 8) | a.seq;
		if(attackTried.count(k)) continue;
		int32_t myAtk = atkOf(a, f);
		int64_t sc = 0;
		if(oppMonsters == 0) sc = 100000 + myAtk;
		else {
			for(auto& t : opp.mzone) {
				if(goodTarget(myAtk, t)) sc = std::max<int64_t>(sc, 10000 + value(t.code, &t));
			}
			if(sc == 0 && a.param) sc = 50000 + myAtk;
			if(sc == 0 && diff == 0 && chance(0.4)) sc = 1; // per divertimento attacca lo stesso
		}
		if(sc > bestScore) { bestScore = sc; best = (int)i; }
	}
	if(best >= 0) {
		const auto& a = p.attack[best];
		attackTried.insert(((uint64_t)a.code << 8) | a.seq);
		plannedAttacker = ((uint64_t)a.code << 8) | a.seq;
		c.a = 1; c.b = best;
		return c;
	}
	// difficile: usa effetti rapidi in battaglia (una volta ciascuno)
	if(diff == 2) {
		for(size_t i = 0; i < p.activate.size(); ++i) {
			auto k = key(p.activate[i].code, p.activate[i].desc) ^ 0xB;
			if(usedThisTurn[k] || isNegation(p.activate[i].code)) continue;
			usedThisTurn[k]++;
			c.a = 0; c.b = (int)i;
			return c;
		}
	}
	if(p.toM2) c.a = 2;
	else if(p.toEP) c.a = 3;
	else if(!p.attack.empty()) { c.a = 1; c.b = 0; } // obbligato ad attaccare
	else if(!p.activate.empty()) { c.a = 0; c.b = 0; }
	else c.a = 3;
	return c;
}

// ------------------------------------------------------------------ CATENE
Choice Bot::chain(const Prompt& p, const FieldState& f, int me) {
	Choice c; c.a = -1;
	if(p.cards.empty()) return c;
	if(p.forced) { c.a = 0; return c; }
	if(passive) return c;
	const bool lastIsOpp = !f.chain.empty() && f.chain.back().con != me;
	const bool lastIsMine = !f.chain.empty() && f.chain.back().con == me;
	const bool oppSummoning = pendingSummonBy >= 0 && pendingSummonBy != me;
	const bool myTurn = f.turnPlayer == me;

	std::vector<int> order(p.cards.size());
	std::iota(order.begin(), order.end(), 0);
	if(diff == 0) std::shuffle(order.begin(), order.end(), rng);

	for(int i : order) {
		const auto& o = p.cards[i];
		auto k = key(o.code, o.desc);
		const bool neg = isNegation(o.code);
		const CardInfo* ci = db.get(o.code);
		const bool mine = o.con == me;
		(void)mine;
		if(diff == 0) {
			if(lastIsMine) continue;
			if(usedThisTurn[k] >= 1) continue;
			if(chance(0.35)) { usedThisTurn[k]++; c.a = i; return c; }
			continue;
		}
		if(neg) {
			// le negazioni si usano solo contro l'avversario
			if(lastIsOpp || (oppSummoning && f.chain.empty())) {
				if(diff == 1 && !chance(0.6)) continue;
				usedThisTurn[k]++;
				c.a = i; return c;
			}
			continue;
		}
		const int limit = diff == 2 ? 2 : 1;
		if(usedThisTurn[k] >= limit) continue;
		if(lastIsMine && diff == 1) continue;
		bool isTrapOrQuick = ci && (ci->type & (TYPE_TRAP | TYPE_QUICKPLAY));
		bool good = false;
		if(f.chain.empty()) {
			if(myTurn) good = !isTrapOrQuick || (ci && (ci->category & (CAT_DESTROY_MONSTER | CAT_DESTROY_ST)));
			else good = lastIsOpp || attackBy >= 0 || phase == PHASE_END || oppSummoning ||
			            (diff == 2 && phase == PHASE_BATTLE_STEP);
			// effetti innescati (non trappole/rapide) vanno sempre bene
			if(!isTrapOrQuick && (o.loc & (LOC_MZONE | LOC_GRAVE | LOC_HAND | LOC_REMOVED))) good = true;
		} else {
			good = lastIsOpp || (diff == 2 && !isTrapOrQuick);
		}
		if(!good) continue;
		usedThisTurn[k]++;
		c.a = i; return c;
	}
	return c;
}

// ------------------------------------------------------------------ SELEZIONI
std::vector<int> Bot::rankCards(const std::vector<CardRef>& cards, const Prompt& p, const FieldState& f, int me) const {
	std::vector<std::pair<int64_t, int>> sc;
	const bool neg = hintNegative(p.hint);
	const bool mat = hintMaterial(p.hint);
	const bool pos = hintPositive(p.hint);
	std::set<uint32_t> inHand;
	for(auto& h : f.p[me].hand) inHand.insert(h.code);
	for(size_t i = 0; i < cards.size(); ++i) {
		const auto& c = cards[i];
		const FCard* fc = f.find(c.con, c.loc, c.seq);
		int64_t v = value(c.code, fc);
		const bool isOpp = c.con != me;
		int64_t s;
		if(p.hint == HINT_ATTACK_TARGET) {
			int64_t best = 0;
			if(fc && fc->exists) {
				// bersaglio pianificato: il più forte tra quelli battibili
				int32_t myAtk = 0;
				for(auto& m : f.p[me].mzone) if(m.exists && (((uint64_t)m.code << 8) | m.seq) == plannedAttacker) myAtk = m.atk;
				best = goodTarget(myAtk, *fc) ? 100000 + v : -v;
			}
			s = best;
		} else if(mat) {
			s = isOpp ? v : -v; // materiali/tributi propri: i meno preziosi
		} else if(neg) {
			s = isOpp ? 100000 + v : -v;
		} else if(pos) {
			s = isOpp ? -v : v + (inHand.count(c.code) ? 0 : 400);
		} else {
			// sconosciuto: avversario = bersaglio negativo, proprio = positivo
			s = isOpp ? 50000 + v : v;
		}
		sc.push_back({s, (int)i});
	}
	std::stable_sort(sc.begin(), sc.end(), [](auto& a, auto& b) { return a.first > b.first; });
	std::vector<int> out;
	for(auto& s : sc) out.push_back(s.second);
	return out;
}

Choice Bot::selectCards(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	if(p.cards.empty()) { c.a = -1; return c; }
	uint32_t mn = std::max<uint32_t>(p.min, 1), mx = std::max(p.max, mn);
	mx = std::min<uint32_t>(mx, (uint32_t)p.cards.size());
	mn = std::min(mn, mx);
	if(diff == 0) {
		std::vector<int> idx(p.cards.size());
		std::iota(idx.begin(), idx.end(), 0);
		std::shuffle(idx.begin(), idx.end(), rng);
		uint32_t n = mn + (mx > mn ? (uint32_t)rnd((int)(mx - mn + 1)) : 0);
		c.idx.assign(idx.begin(), idx.begin() + n);
		return c;
	}
	auto order = rankCards(p.cards, p, f, me);
	uint32_t n = mn;
	if(hintPositive(p.hint) || (hintNegative(p.hint) && !hintMaterial(p.hint))) {
		// più bersagli avversari possibile, oppure più carte "buone"
		n = 0;
		for(int i : order) {
			bool isOpp = p.cards[i].con != me;
			if(n >= mx) break;
			if(hintNegative(p.hint) && !isOpp && n >= mn) break;
			++n;
		}
		n = std::max(n, mn);
	}
	c.idx.assign(order.begin(), order.begin() + n);
	return c;
}

Choice Bot::unselect(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	if(p.finishable && (diff == 0 || !hintPositive(p.hint) || p.cards.empty())) { c.a = -1; return c; }
	if(p.cards.empty()) {
		if(p.cancelable || p.finishable) { c.a = -1; return c; }
		c.a = 1; c.b = 0; return c;
	}
	auto order = rankCards(p.cards, p, f, me);
	c.a = 1;
	c.b = diff == 0 ? rnd((int)p.cards.size()) : order.front();
	return c;
}

Choice Bot::tribute(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	std::vector<std::pair<int64_t, int>> v;
	for(size_t i = 0; i < p.cards.size(); ++i) {
		const auto& t = p.cards[i];
		int64_t val = value(t.code, f.find(t.con, t.loc, t.seq));
		if(t.con != me) val -= 100000; // tributare mostri avversari è sempre un affare
		v.push_back({val, (int)i});
	}
	std::sort(v.begin(), v.end());
	uint32_t got = 0;
	for(auto& x : v) {
		if(got >= p.min && !c.idx.empty()) break;
		c.idx.push_back(x.second);
		got += std::max<uint32_t>(p.cards[x.second].param, 1);
		if(c.idx.size() >= std::max<uint32_t>(p.max, 1)) break;
	}
	return c;
}

Choice Bot::sum(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	// ordina dalle carte meno preziose
	std::vector<int> order(p.cards.size());
	std::iota(order.begin(), order.end(), 0);
	std::sort(order.begin(), order.end(), [&](int a, int b) {
		return value(p.cards[a].code, f.find(p.cards[a].con, p.cards[a].loc, p.cards[a].seq)) <
		       value(p.cards[b].code, f.find(p.cards[b].con, p.cards[b].loc, p.cards[b].seq));
	});
	(void)me;
	int32_t mustSum = 0, mustMin = 0;
	// le carte obbligatorie contano col valore minore
	for(auto& m : p.cards2) { int32_t o1 = m.param & 0xffff, o2 = m.param >> 16; mustSum += (o2 && o2 < o1) ? o2 : o1; }
	(void)mustMin;
	const int32_t acc = (int32_t)p.acc;
	std::vector<int> cur, best;
	int steps = 0;
	std::function<bool(size_t, int32_t)> dfsExact = [&](size_t i, int32_t rem) -> bool {
		if(++steps > 200000) return false;
		if(rem == 0) return cur.size() >= p.min && (p.max == 0 || cur.size() <= p.max) && !cur.empty();
		if(rem < 0 || i >= order.size()) return false;
		if(p.max && cur.size() >= p.max) return false;
		int idx = order[i];
		int32_t o1 = p.cards[idx].param & 0xffff, o2 = p.cards[idx].param >> 16;
		cur.push_back(idx);
		if(dfsExact(i + 1, rem - o1)) return true;
		if(o2 && o2 != o1 && dfsExact(i + 1, rem - o2)) return true;
		cur.pop_back();
		return dfsExact(i + 1, rem);
	};
	if(p.sumExact) {
		// le obbligatorie possono valere anche il secondo valore: proviamo solo il primo (caso tipico)
		int32_t mustFirst = 0;
		for(auto& m : p.cards2) mustFirst += m.param & 0xffff;
		if(!dfsExact(0, acc - mustFirst)) { cur.clear(); steps = 0; dfsExact(0, acc - mustSum); }
		c.idx = cur;
		if(c.idx.empty() && !order.empty()) c.idx.push_back(order[0]);
		return c;
	}
	// modalità "almeno": accumula finché raggiungi la soglia
	int32_t tot = mustSum;
	for(int idx : order) {
		if(tot >= acc) break;
		int32_t o1 = p.cards[idx].param & 0xffff, o2 = p.cards[idx].param >> 16;
		c.idx.push_back(idx);
		tot += std::max(o1, o2);
	}
	return c;
}

Choice Bot::place(const Prompt& p, const FieldState& f, int me) {
	(void)f;
	Choice c;
	uint32_t flag = p.flag;
	const bool dis = p.type == MSG_SELECT_DISFIELD;
	static const int mOrder[] = {2, 1, 3, 0, 4, 5, 6};
	static const int sOrder[] = {2, 1, 3, 0, 4, 5, 6, 7};
	for(int n = 0; n < std::max<int>(p.count, 1); ++n) {
		bool found = false;
		// per le zone da disabilitare si preferisce il lato avversario
		for(int side = 0; side < 2 && !found; ++side) {
			int rel = dis ? 1 - side : side;   // 0 = proprio, 1 = avversario
			uint32_t shift = rel ? 16 : 0;
			for(int pass = 0; pass < 2 && !found; ++pass) {
				if(pass == 0) {
					for(int s : mOrder) {
						uint32_t bit = (1u << s) << shift;
						if(!(flag & bit)) { flag |= bit; found = true;
							c.places.push_back((uint8_t)(rel ? 1 - me : me)); c.places.push_back((uint8_t)LOC_MZONE); c.places.push_back((uint8_t)s); break; }
					}
				} else {
					for(int s : sOrder) {
						uint32_t bit = ((1u << s) << 8) << shift;
						if(!(flag & bit)) { flag |= bit; found = true;
							c.places.push_back((uint8_t)(rel ? 1 - me : me)); c.places.push_back((uint8_t)LOC_SZONE); c.places.push_back((uint8_t)s); break; }
					}
				}
			}
		}
	}
	return c;
}

Choice Bot::position(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	const CardInfo* ci = db.get(p.posCode);
	int32_t atk = ci ? ci->atk : 0, def = ci ? ci->def : 0;
	int32_t oppMax = oppMaxAtk(f, me);
	bool wantAtk = f.p[1 - me].monsterCount() == 0 || atk >= oppMax || (diff == 0 && chance(0.6)) ||
	               (ci && (ci->type & TYPE_LINK));
	if(!wantAtk && def < atk && atk >= 1500 && diff == 2) wantAtk = true;
	const uint32_t prefs[] = {wantAtk ? POS_FACEUP_ATTACK : POS_FACEUP_DEFENSE,
	                          wantAtk ? POS_FACEUP_DEFENSE : POS_FACEDOWN_DEFENSE,
	                          POS_FACEUP_ATTACK, POS_FACEUP_DEFENSE, POS_FACEDOWN_DEFENSE, POS_FACEDOWN_ATTACK};
	for(uint32_t pr : prefs) if(p.positions & pr) { c.a = (int)pr; return c; }
	c.a = POS_FACEUP_ATTACK;
	return c;
}

Choice Bot::counter(const Prompt& p) {
	Choice c;
	int remaining = p.counterCount;
	for(auto& card : p.cards) {
		int take = std::min<int>((int)card.param, remaining);
		c.idx.push_back(take);
		remaining -= take;
	}
	return c;
}

Choice Bot::announceMask(const Prompt& p, const FieldState& f, int me, bool race) {
	Choice c;
	std::map<uint64_t, int> freq;
	auto add = [&](const FCard& fc) {
		if(!fc.exists || !fc.isMonster() || !fc.faceup()) return;
		uint64_t v = race ? fc.race : fc.attribute;
		for(int b = 0; b < 64; ++b) if(v & (1ull << b)) freq[1ull << b] += 2;
	};
	for(auto& m : f.p[1 - me].mzone) add(m);
	for(auto& g : f.p[1 - me].grave) add(g);
	std::vector<std::pair<int, uint64_t>> cand;
	for(int b = 0; b < 64; ++b) {
		uint64_t bit = 1ull << b;
		if(p.available & bit) cand.push_back({freq[bit] * 10 + (diff == 0 ? rnd(10) : 0), bit});
	}
	std::sort(cand.begin(), cand.end(), [](auto& a, auto& b) { return a.first > b.first; });
	for(int i = 0; i < p.count && i < (int)cand.size(); ++i) c.mask |= cand[i].second;
	return c;
}

Choice Bot::announceCard(const Prompt& p, const FieldState& f, int me) {
	Choice c;
	std::vector<uint32_t> cands;
	auto addField = [&](const FCard& fc) { if(fc.exists && fc.code && fc.faceup()) cands.push_back(fc.code); };
	for(auto& m : f.p[1 - me].mzone) addField(m);
	for(auto& m : f.p[1 - me].szone) addField(m);
	for(auto& g : f.p[1 - me].grave) cands.push_back(g.code);
	for(auto& r : f.p[1 - me].removed) cands.push_back(r.code);
	for(auto s : seenOpp) cands.push_back(s);
	for(uint32_t code : cands) {
		const CardInfo* ci = db.get(code);
		if(ci && isDeclarable(*ci, p.options)) { c.a = (int)code; return c; }
	}
	// ripiego: la prima carta valida del database (ordine stabile)
	std::vector<uint32_t> all;
	for(auto& kv : db.all()) if(!kv.second.alias) all.push_back(kv.first);
	std::sort(all.begin(), all.end());
	if(diff == 0) std::shuffle(all.begin(), all.end(), rng);
	for(uint32_t code : all) {
		if(isDeclarable(db.all().at(code), p.options)) { c.a = (int)code; return c; }
	}
	c.a = 0;
	return c;
}

Choice Bot::fallback(const Prompt& p, const FieldState& f, int me, int attempt) {
	Choice c;
	switch(p.type) {
	case MSG_SELECT_IDLECMD: {
		if(attempt < 3 && (p.toBP || p.toEP)) { c.a = (p.toBP && (f.turn > 1 || !p.toEP)) ? 6 : 7; return c; }
		// nessuna fase disponibile: prova le azioni elencate a rotazione
		std::vector<std::pair<int, int>> all;
		const std::vector<CardRef>* lists[] = {&p.summon, &p.spsummon, &p.repos, &p.mset, &p.sset, &p.activate};
		for(int t = 0; t < 6; ++t) for(size_t i = 0; i < lists[t]->size(); ++i) all.push_back({t, (int)i});
		if(!all.empty()) { auto& x = all[attempt % all.size()]; c.a = x.first; c.b = x.second; return c; }
		c.a = p.toEP ? 7 : 6;
		return c;
	}
	case MSG_SELECT_BATTLECMD:
		if(attempt < 3 && (p.toM2 || p.toEP)) { c.a = p.toM2 ? 2 : 3; return c; }
		if(!p.attack.empty()) { c.a = 1; c.b = attempt % (int)p.attack.size(); return c; }
		if(!p.activate.empty()) { c.a = 0; c.b = attempt % (int)p.activate.size(); return c; }
		c.a = p.toEP ? 3 : 2;
		return c;
	case MSG_SELECT_CHAIN:
		c.a = p.forced ? 0 : -1;
		return c;
	case MSG_SELECT_CARD:
	case MSG_SELECT_TRIBUTE: {
		if(p.cancelable && attempt >= 2) { c.a = -1; return c; }
		uint32_t n = std::max<uint32_t>(p.min, 1);
		for(uint32_t i = 0; i < n && i < p.cards.size(); ++i) c.idx.push_back((int)((i + attempt) % p.cards.size()));
		std::sort(c.idx.begin(), c.idx.end());
		c.idx.erase(std::unique(c.idx.begin(), c.idx.end()), c.idx.end());
		return c;
	}
	case MSG_SELECT_SUM: {
		// prova combinazioni piccole diverse
		for(size_t i = 0; i < p.cards.size() && i <= (size_t)attempt; ++i) c.idx.push_back((int)i);
		return c;
	}
	case MSG_SELECT_UNSELECT_CARD:
		if(p.finishable || p.cancelable) { c.a = -1; return c; }
		c.a = 1; c.b = attempt % std::max<int>(1, (int)(p.cards.size() + p.cards2.size()));
		return c;
	case MSG_SELECT_POSITION:
		for(uint32_t pr : {1u, 4u, 8u, 2u}) if(p.positions & pr) { c.a = (int)pr; return c; }
		c.a = 1; return c;
	case MSG_SELECT_EFFECTYN:
	case MSG_SELECT_YESNO:
		c.a = attempt % 2; return c;
	case MSG_SELECT_OPTION:
	case MSG_ANNOUNCE_NUMBER:
		c.a = attempt % std::max<int>(1, (int)p.options.size()); return c;
	default:
		return decide(p, f, me);
	}
}

// ---- valutatore delle dichiarazioni (replica di is_declarable del motore)
bool isDeclarable(const CardInfo& cd, const std::vector<uint64_t>& opcodes) {
	constexpr uint64_t ADD = 0x4000000000000000ull, SUB = 0x4000000100000000ull, MUL = 0x4000000200000000ull,
	    DIV = 0x4000000300000000ull, AND = 0x4000000400000000ull, OR = 0x4000000500000000ull,
	    NEG = 0x4000000600000000ull, NOT = 0x4000000700000000ull, BAND = 0x4000000800000000ull,
	    BOR = 0x4000000900000000ull, BNOT = 0x4000001000000000ull, BXOR = 0x4000001100000000ull,
	    LSHIFT = 0x4000001200000000ull, RSHIFT = 0x4000001300000000ull,
	    ALLOW_ALIASES = 0x4000001400000000ull, ALLOW_TOKENS = 0x4000001500000000ull,
	    ISCODE = 0x4000010000000000ull, ISSETCARD = 0x4000010100000000ull, ISTYPE = 0x4000010200000000ull,
	    ISRACE = 0x4000010300000000ull, ISATTRIBUTE = 0x4000010400000000ull, GETCODE = 0x4000010500000000ull,
	    GETTYPE = 0x4000010700000000ull, GETRACE = 0x4000010800000000ull, GETATTRIBUTE = 0x4000010900000000ull;
	std::stack<int64_t> st;
	bool alias = false, token = false;
	auto bin = [&](auto op) { if(st.size() >= 2) { auto r = st.top(); st.pop(); auto l = st.top(); st.pop(); st.push(op(l, r)); } };
	auto un = [&](auto op) { if(!st.empty()) { auto v = st.top(); st.pop(); st.push(op(v)); } };
	for(uint64_t op : opcodes) {
		switch(op) {
		case ADD: bin([](int64_t a, int64_t b) { return a + b; }); break;
		case SUB: bin([](int64_t a, int64_t b) { return a - b; }); break;
		case MUL: bin([](int64_t a, int64_t b) { return a * b; }); break;
		case DIV: bin([](int64_t a, int64_t b) { return b ? a / b : 0; }); break;
		case AND: bin([](int64_t a, int64_t b) { return (int64_t)(a && b); }); break;
		case OR: bin([](int64_t a, int64_t b) { return (int64_t)(a || b); }); break;
		case NEG: un([](int64_t a) { return -a; }); break;
		case NOT: un([](int64_t a) { return (int64_t)!a; }); break;
		case BAND: bin([](int64_t a, int64_t b) { return a & b; }); break;
		case BOR: bin([](int64_t a, int64_t b) { return a | b; }); break;
		case BXOR: bin([](int64_t a, int64_t b) { return a ^ b; }); break;
		case BNOT: un([](int64_t a) { return ~a; }); break;
		case LSHIFT: bin([](int64_t a, int64_t b) { return a << b; }); break;
		case RSHIFT: bin([](int64_t a, int64_t b) { return a >> b; }); break;
		case ISCODE: un([&](int64_t a) { return (int64_t)(cd.code == (uint32_t)a); }); break;
		case ISTYPE: un([&](int64_t a) { return (int64_t)(cd.type & a); }); break;
		case ISRACE: un([&](int64_t a) { return (int64_t)(cd.race & a); }); break;
		case ISATTRIBUTE: un([&](int64_t a) { return (int64_t)(cd.attribute & a); }); break;
		case GETCODE: st.push(cd.code); break;
		case GETTYPE: st.push(cd.type); break;
		case GETRACE: st.push((int64_t)cd.race); break;
		case GETATTRIBUTE: st.push(cd.attribute); break;
		case ISSETCARD:
			if(!st.empty()) {
				int32_t set_code = (int32_t)st.top(); st.pop();
				bool res = false;
				uint16_t settype = set_code & 0xfff, setsub = set_code & 0xf000;
				for(int i = 0; i < 5 && cd.setcodes[i]; ++i) {
					uint16_t sc = cd.setcodes[i];
					if((sc & 0xfff) == settype && (sc & 0xf000 & setsub) == setsub) { res = true; break; }
				}
				st.push(res);
			}
			break;
		case ALLOW_ALIASES: alias = true; break;
		case ALLOW_TOKENS: token = true; break;
		default: st.push((int64_t)op); break;
		}
	}
	if(st.size() != 1 || st.top() == 0) return false;
	return cd.code == 78734254 || cd.code == 13857930 ||
	       ((alias || !cd.alias) && (token || ((cd.type & (TYPE_MONSTER | TYPE_TOKEN)) != (TYPE_MONSTER | TYPE_TOKEN))));
}

} // namespace ygt
