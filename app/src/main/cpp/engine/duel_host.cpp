#include "duel_host.h"
#include "ocgapi.h"
#include <algorithm>
#include <random>

namespace ygt {

// ------------------------------------------------------------------ callback del motore
struct HostCallbacks {
	static void cardReader(void* payload, uint32_t code, OCG_CardData* data) {
		auto* host = static_cast<DuelHost*>(payload);
		static uint16_t empty[1] = {0};
		std::memset(data, 0, sizeof(OCG_CardData));
		data->code = code;
		data->setcodes = empty;
		const CardInfo* ci = host->db.get(code);
		if(!ci) return;
		data->alias = ci->alias;
		data->setcodes = const_cast<uint16_t*>(ci->setcodes);
		data->type = ci->type;
		data->level = ci->level;
		data->attribute = ci->attribute;
		data->race = ci->race;
		data->attack = ci->atk;
		data->defense = ci->def;
		data->lscale = ci->lscale;
		data->rscale = ci->rscale;
		data->link_marker = ci->link;
	}
	static int scriptReader(void* payload, OCG_Duel d, const char* name) {
		return static_cast<DuelHost*>(payload)->loadScript(name, d) ? 1 : 0;
	}
	static void logHandler(void* payload, const char* str, int type) {
		auto* host = static_cast<DuelHost*>(payload);
		if(host->simulating) return;
		if(type == OCG_LOG_TYPE_ERROR && host->engineErrors.size() < 50)
			host->engineErrors.push_back(str ? str : "");
	}
};

DuelHost::DuelHost(const CardDB& d, ScriptLoader l) : db(d), loader(std::move(l)) {}

DuelHost::~DuelHost() {
	if(duel) OCG_DestroyDuel(duel);
}

bool DuelHost::loadScript(const char* name, void* target) {
	std::string n = name ? name : "";
	// il motore può chiedere percorsi tipo "./script/c123.lua": teniamo solo il nome file
	auto slash = n.find_last_of("/\\");
	if(slash != std::string::npos) n = n.substr(slash + 1);
	auto it = scriptCache.find(n);
	if(it == scriptCache.end()) it = scriptCache.emplace(n, loader(n)).first;
	const std::string& src = it->second;
	if(src.empty()) return false;
	return OCG_LoadScript(target, src.data(), (uint32_t)src.size(), n.c_str()) != 0;
}

void DuelHost::setAutoHuman(int difficulty, uint64_t seed) {
	autoHuman = std::make_unique<Bot>(difficulty, db, seed);
}

void* DuelHost::createEngine(std::string* err) {
	OCG_DuelOptions opt{};
	for(int i = 0; i < 4; ++i) opt.seed[i] = engineSeed[i];
	constexpr uint64_t MODE_MR5 = 0x800 | 0x2000 | 0x4000 | 0x8000 | 0x20000; // DUEL_MODE_MR5
	opt.flags = MODE_MR5;
	opt.team1 = {8000, 5, 1};
	opt.team2 = {8000, 5, 1};
	opt.cardReader = &HostCallbacks::cardReader;
	opt.payload1 = this;
	opt.scriptReader = &HostCallbacks::scriptReader;
	opt.payload2 = this;
	opt.logHandler = &HostCallbacks::logHandler;
	opt.payload3 = this;
	opt.cardReaderDone = nullptr;
	opt.enableUnsafeLibraries = 0;
	OCG_Duel d = nullptr;
	int r = OCG_CreateDuel(&d, &opt);
	if(r != OCG_DUEL_CREATION_SUCCESS) {
		if(err) *err = "Impossibile creare il duello (codice " + std::to_string(r) + ")";
		return nullptr;
	}
	if(!loadScript("constant.lua", d) || !loadScript("utility.lua", d)) {
		if(err) *err = "Script di base mancanti (constant.lua / utility.lua)";
		OCG_DestroyDuel(d);
		return nullptr;
	}
	for(uint8_t team = 0; team < 2; ++team) {
		for(uint32_t code : teamMain[team]) {
			OCG_NewCardInfo ci{team, 0, code, team, LOC_DECK, 0, POS_FACEDOWN_DEFENSE};
			OCG_DuelNewCard(d, &ci);
		}
		for(uint32_t code : teamExtra[team]) {
			OCG_NewCardInfo ci{team, 0, code, team, LOC_EXTRA, 0, POS_FACEDOWN_DEFENSE};
			OCG_DuelNewCard(d, &ci);
		}
	}
	OCG_StartDuel(d);
	return d;
}

bool DuelHost::start(const Deck& humanDeck, const Deck& botDeck, int difficulty, bool humanFirst, uint64_t seed, std::string& err) {
	human = humanFirst ? 0 : 1;
	difficultyLevel = difficulty;
	bot = std::make_unique<Bot>(difficulty, db, seed ^ 0x9E3779B97F4A7C15ull);
	if(difficulty >= 2) {
		bot->setLookahead([this](const Choice& c) { return lookahead(c); });
		bot->setThinkBudgetMs(700);
	}
	std::mt19937_64 seeder(seed);
	for(auto& s : engineSeed) s = seeder() | 1;
	std::mt19937_64 shuf(seed * 31 + 7);
	auto prep = [&](const Deck& d, int team) {
		teamMain[team] = d.main;
		std::shuffle(teamMain[team].begin(), teamMain[team].end(), shuf);
		teamExtra[team] = d.extra;
	};
	prep(humanDeck, human);
	prep(botDeck, 1 - human);
	history.clear();
	duel = createEngine(&err);
	return duel != nullptr;
}

void DuelHost::sendResponse(const std::vector<uint8_t>& resp) {
	OCG_DuelSetResponse(duel, resp.data(), (uint32_t)resp.size());
	history.push_back(resp);
}

// ------------------------------------------------------------------ ciclo principale
std::string DuelHost::advance() {
	int guard = 0;
	while(!ended) {
		if(hasHumanPrompt) return buildView();
		if(++guard > 60000) {
			ended = true; winner = 2;
			pendingError = "Il duello si è bloccato: partita interrotta.";
			break;
		}
		int st = OCG_DuelProcess(duel);
		uint32_t len = 0;
		auto* buf = static_cast<const uint8_t*>(OCG_DuelGetMessage(duel, &len));
		bool gotPrompt = false, gotRetry = false;
		processMessages(buf, len, gotPrompt, gotRetry);
		if(ended) break;
		if(gotRetry) { ++retries; gotPrompt = true; }
		else if(gotPrompt) retries = 0;
		if(gotPrompt) {
			field.query(duel);
			const bool humanTurn = prompt.player == human && !autoHuman;
			if(humanTurn && retries == 0 && prompt.type == MSG_SELECT_CHAIN && prompt.cards.empty() && !prompt.forced) {
				Choice none; none.a = -1;
				sendResponse(encodeResponse(prompt, none));
				continue;
			}
			if(humanTurn && (prompt.type == MSG_SORT_CARD || prompt.type == MSG_SORT_CHAIN) && retries == 0) {
				sendResponse(encodeResponse(prompt, Choice{}));
				continue;
			}
			if(!humanTurn) {
				Bot* b = prompt.player == human ? autoHuman.get() : bot.get();
				if(retries > 30) {
					// il bot non trova una risposta valida: si arrende per non bloccare il duello
					ended = true;
					winner = rel(1 - prompt.player);
					winReason = 99;
					pendingError = "L'avversario non è riuscito a risolvere una scelta e si è arreso.";
					engineErrors.push_back("bot bloccato: msg " + std::to_string(prompt.type) + " hint " + std::to_string(prompt.hint) +
					                       " carte " + std::to_string(prompt.cards.size()) + " min " + std::to_string(prompt.min) +
					                       " max " + std::to_string(prompt.max) + " acc " + std::to_string(prompt.acc));
					break;
				}
				Choice c = retries == 0 ? b->decide(prompt, field, prompt.player)
				                        : b->fallback(prompt, field, prompt.player, retries);
				sendResponse(encodeResponse(prompt, c));
				++botDecisions;
				continue;
			}
			if(gotRetry) pendingError = "Scelta non valida per le regole: riprova.";
			hasHumanPrompt = true;
			return buildView();
		}
		if(st == OCG_DUEL_STATUS_END) {
			ended = true;
			if(winner < 0) winner = 2;
			break;
		}
	}
	return buildView();
}

std::string DuelHost::respond(const std::string& j) {
	if(!hasHumanPrompt || ended) return buildView();
	Choice c;
	c.a = (int)jsonInt(j, "a", 0);
	c.b = (int)jsonInt(j, "b", 0);
	for(auto v : jsonIntArray(j, "idx")) c.idx.push_back((int)v);
	auto pl = jsonIntArray(j, "places");
	for(size_t i = 0; i + 2 < pl.size(); i += 3) {
		int abs = pl[i] == 0 ? human : 1 - human;
		c.places.push_back((uint8_t)abs);
		c.places.push_back((uint8_t)pl[i + 1]);
		c.places.push_back((uint8_t)pl[i + 2]);
	}
	c.mask = (uint64_t)jsonInt(j, "mask", 0);
	sendResponse(encodeResponse(prompt, c));
	hasHumanPrompt = false;
	return advance();
}

std::string DuelHost::surrender() {
	if(!ended) {
		ended = true;
		winner = 1;
		winReason = 100;
		hasHumanPrompt = false;
	}
	return buildView();
}

// ------------------------------------------------------------------ messaggi
void DuelHost::processMessages(const uint8_t* buf, uint32_t len, bool& gotPrompt, bool& gotRetry) {
	Reader all(buf, len);
	while(all.left() >= 4) {
		uint32_t mlen = all.u32();
		if(mlen == 0 || !all.ok(mlen)) break;
		Reader rd(all.p, mlen);
		all.skip(mlen);
		uint8_t t = rd.u8();
		if(t == MSG_RETRY) { gotRetry = true; continue; }
		if(isSelectMessage(t)) {
			Prompt p;
			if(parsePrompt(t, rd, p)) {
				if(t == MSG_ROCK_PAPER_SCISSORS) p.player = p.player; // giocatore indicato dal messaggio
				p.hint = hint[p.player & 1];
				hint[p.player & 1] = 0;
				prompt = p;
				gotPrompt = true;
			}
			continue;
		}
		handleEvent(t, rd);
	}
}

namespace {
uint32_t queryCode(void* duel, uint8_t con, uint32_t loc, uint32_t seq) {
	if(con > 1 || !(loc & 0x7f)) return 0;
	OCG_QueryInfo qi{0x1, con, loc & 0x7f, seq, 0};
	uint32_t len = 0;
	auto* b = static_cast<const uint8_t*>(OCG_DuelQuery(duel, &len, &qi));
	if(!b || len < 10) return 0;
	Reader rd(b, len);
	rd.u16();
	if(rd.u32() != 0x1) return 0;
	return rd.u32();
}
}

void DuelHost::handleEvent(uint8_t t, Reader& rd) {
	auto readLoc = [&](uint8_t& con, uint32_t& loc, uint32_t& seq, uint32_t& pos) {
		con = rd.u8(); loc = rd.u8(); seq = rd.u32(); pos = rd.u32();
	};
	auto botsDo = [&](auto fn) { if(bot) fn(*bot); if(autoHuman) fn(*autoHuman); };
	switch(t) {
	case MSG_HINT: {
		uint8_t type = rd.u8(); uint8_t pl = rd.u8(); uint64_t d = rd.u64();
		if(type == HINT_SELECTMSG && pl < 2) hint[pl] = d;
		break;
	}
	case MSG_NEW_TURN: {
		uint8_t tp = rd.u8();
		field.turn++;
		field.turnPlayer = tp;
		botsDo([&](Bot& b) { b.onNewTurn(tp); });
		Json j; j.beginObj().kv("k", std::string("turn")).kv("pl", (int64_t)rel(tp)).kv("n", (int64_t)field.turn).endObj();
		addLog(j.str());
		break;
	}
	case MSG_NEW_PHASE: {
		uint16_t ph = rd.u16();
		field.phase = ph;
		botsDo([&](Bot& b) { b.onNewPhase(ph); });
		Json j; j.beginObj().kv("k", std::string("phase")).kv("ph", (int64_t)ph).endObj();
		addLog(j.str());
		break;
	}
	case MSG_WIN: {
		uint8_t pl = rd.u8(); uint8_t reason = rd.u8();
		ended = true;
		winner = pl > 1 ? 2 : rel(pl);
		winReason = reason;
		break;
	}
	case MSG_DRAW: {
		uint8_t pl = rd.u8(); uint32_t n = rd.u32();
		Json j; j.beginObj().kv("k", std::string("draw")).kv("pl", (int64_t)rel(pl)).kv("n", (int64_t)n);
		if(pl == human) {
			j.key("c").beginArr();
			for(uint32_t i = 0; i < n && rd.left(); ++i) { j.valU(rd.u32()); rd.u32(); }
			j.endArr();
		}
		j.endObj();
		addLog(j.str());
		break;
	}
	case MSG_DAMAGE: case MSG_RECOVER: case MSG_PAY_LPCOST: {
		uint8_t pl = rd.u8(); uint32_t v = rd.u32();
		const char* k = t == MSG_DAMAGE ? "dmg" : t == MSG_RECOVER ? "rec" : "cost";
		Json j; j.beginObj().kv("k", std::string(k)).kv("pl", (int64_t)rel(pl)).kv("v", (int64_t)v).endObj();
		addLog(j.str());
		break;
	}
	case MSG_SUMMONING: case MSG_SPSUMMONING: case MSG_FLIPSUMMONING: {
		uint32_t code = rd.u32(); uint8_t con; uint32_t loc, seq, pos; readLoc(con, loc, seq, pos);
		botsDo([&](Bot& b) { b.onSummoning(con); b.onSeenCard(con, code); });
		const char* k = t == MSG_SUMMONING ? "summon" : t == MSG_SPSUMMONING ? "spsummon" : "flip";
		Json j; j.beginObj().kv("k", std::string(k)).kv("pl", (int64_t)rel(con)).kvU("c", code).endObj();
		addLog(j.str());
		break;
	}
	case MSG_SUMMONED: case MSG_SPSUMMONED: case MSG_FLIPSUMMONED:
		botsDo([&](Bot& b) { b.onSummoned(); });
		break;
	case MSG_SET: {
		uint32_t code = rd.u32(); uint8_t con; uint32_t loc, seq, pos; readLoc(con, loc, seq, pos);
		Json j; j.beginObj().kv("k", std::string("set")).kv("pl", (int64_t)rel(con)).kv("l", (int64_t)loc)
		    .kvU("c", con == human ? code : 0).endObj();
		addLog(j.str());
		break;
	}
	case MSG_CHAINING: {
		uint32_t code = rd.u32(); uint8_t con; uint32_t loc, seq, pos; readLoc(con, loc, seq, pos);
		uint8_t tc = rd.u8(); rd.u8(); rd.u32(); uint64_t desc = rd.u64(); uint32_t cc = rd.u32();
		lastChainCount = (int)cc;
		botsDo([&](Bot& b) { b.onSeenCard(tc, code); });
		Json j; j.beginObj().kv("k", std::string("chain")).kv("pl", (int64_t)rel(tc)).kvU("c", code)
		    .kvU("d", desc).kv("n", (int64_t)cc).endObj();
		addLog(j.str());
		break;
	}
	case MSG_CHAIN_END:
		lastChainCount = 0;
		botsDo([&](Bot& b) { b.onChainEnd(); });
		break;
	case MSG_CHAIN_NEGATED: case MSG_CHAIN_DISABLED: {
		uint8_t n = rd.u8();
		Json j; j.beginObj().kv("k", std::string("negated")).kv("n", (int64_t)n).endObj();
		addLog(j.str());
		break;
	}
	case MSG_ATTACK: {
		uint8_t ac; uint32_t al, as, ap; readLoc(ac, al, as, ap);
		uint8_t dc; uint32_t dl, ds, dp; readLoc(dc, dl, ds, dp);
		botsDo([&](Bot& b) { b.onAttack(ac); });
		uint32_t acode = queryCode(duel, ac, al, as);
		uint32_t dcode = dl ? queryCode(duel, dc, dl, ds) : 0;
		if(dl && !(dp & POS_FACEUP) && dc != human) dcode = 0;
		Json j; j.beginObj().kv("k", std::string("attack")).kv("pl", (int64_t)rel(ac)).kvU("c", acode)
		    .kv("direct", dl == 0).kvU("t", dcode).endObj();
		addLog(j.str());
		break;
	}
	case MSG_MOVE: {
		uint32_t code = rd.u32();
		uint8_t pc; uint32_t pl, ps, pp; readLoc(pc, pl, ps, pp);
		uint8_t cc; uint32_t cl, cs, cp; readLoc(cc, cl, cs, cp);
		uint32_t reason = rd.u32(); (void)reason;
		if(pl == cl && pc == cc) break;
		uint32_t to = cl & 0x7f, from = pl & 0x7f;
		bool notable = (to == LOC_GRAVE || to == LOC_REMOVED || to == LOC_HAND || to == LOC_DECK || to == LOC_EXTRA)
		               && from != 0 && !(from == LOC_DECK && to == LOC_HAND && false);
		if(!notable || (pl & LOC_OVERLAY)) break;
		bool vis = cc == human || pc == human || to == LOC_GRAVE || to == LOC_REMOVED ||
		           (from & (LOC_GRAVE | LOC_REMOVED)) || ((from & LOC_ONFIELD) && (pp & POS_FACEUP));
		if(cc != human && to == LOC_HAND && (from == LOC_DECK)) vis = false; // ricerca avversaria: si vede solo se rivelata
		Json j; j.beginObj().kv("k", std::string("move")).kv("pl", (int64_t)rel(cc)).kvU("c", vis ? code : 0)
		    .kv("from", (int64_t)from).kv("to", (int64_t)to).endObj();
		addLog(j.str());
		break;
	}
	case MSG_CONFIRM_CARDS: {
		rd.u8();
		uint32_t n = rd.u32();
		Json j; j.beginObj().kv("k", std::string("reveal")).key("c").beginArr();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			uint32_t code = rd.u32(); uint8_t con = rd.u8(); rd.u8(); rd.u32();
			j.valU(code);
			botsDo([&](Bot& b) { b.onSeenCard(con, code); });
		}
		j.endArr().endObj();
		if(n) addLog(j.str());
		break;
	}
	case MSG_TOSS_COIN: case MSG_TOSS_DICE: {
		uint8_t pl = rd.u8(); uint8_t n = rd.u8();
		Json j; j.beginObj().kv("k", std::string(t == MSG_TOSS_COIN ? "coin" : "dice")).kv("pl", (int64_t)rel(pl)).key("r").beginArr();
		for(int i = 0; i < n && rd.left(); ++i) j.val((int64_t)rd.u8());
		j.endArr().endObj();
		addLog(j.str());
		break;
	}
	default:
		break;
	}
}

// ------------------------------------------------------------------ vista per l'interfaccia
bool DuelHost::visibleToHuman(uint8_t con, uint32_t loc, uint32_t pos) const {
	if(con == human) return true;
	if(loc & (LOC_GRAVE | LOC_REMOVED)) return true;
	if(loc & (LOC_ONFIELD | LOC_EXTRA)) {
		if(pos == 0) {
			const FCard* fc = field.find(con, loc, 0);
			(void)fc;
			return false;
		}
		return pos & POS_FACEUP;
	}
	return false;
}

void DuelHost::writeCardRef(Json& j, const CardRef& c) const {
	uint32_t pos = c.pos;
	if(pos == 0 && (c.loc & LOC_ONFIELD)) {
		if(const FCard* fc = field.find(c.con, c.loc, c.seq)) pos = fc->pos;
	}
	bool vis = visibleToHuman(c.con, c.loc, pos);
	if(!vis && (c.loc & LOC_ONFIELD) && pos == 0) vis = false;
	j.beginObj().kvU("c", vis ? c.code : 0).kv("pl", (int64_t)rel(c.con)).kv("l", (int64_t)c.loc)
	    .kv("s", (int64_t)c.seq).kv("pos", (int64_t)pos).kvU("d", c.desc).kv("x", (int64_t)c.param).endObj();
}

void DuelHost::writeFCard(Json& j, const FCard& c, bool hide) const {
	if(!c.exists) { j.null(); return; }
	j.beginObj().kvU("c", hide ? 0 : c.code).kv("p", (int64_t)c.pos);
	if(!hide) {
		j.kv("a", (int64_t)c.atk).kv("df", (int64_t)c.def).kv("lv", (int64_t)c.stars()).kv("t", (int64_t)c.type)
		 .kv("ba", (int64_t)c.baseAtk);
		if(c.overlays) j.kv("o", (int64_t)c.overlays);
	}
	j.endObj();
}

void DuelHost::writePrompt(Json& j) {
	const Prompt& p = prompt;
	auto list = [&](const char* k, const std::vector<CardRef>& v) {
		j.key(k).beginArr();
		for(auto& c : v) writeCardRef(j, c);
		j.endArr();
	};
	j.beginObj();
	j.kv("hint", (int64_t)p.hint);
	switch(p.type) {
	case MSG_SELECT_IDLECMD:
		j.kv("k", std::string("idle"));
		list("summon", p.summon); list("sp", p.spsummon); list("repos", p.repos);
		list("mset", p.mset); list("sset", p.sset); list("act", p.activate);
		j.kv("bp", p.toBP).kv("ep", p.toEP);
		break;
	case MSG_SELECT_BATTLECMD:
		j.kv("k", std::string("battle"));
		list("act", p.activate); list("atk", p.attack);
		j.kv("m2", p.toM2).kv("ep", p.toEP);
		break;
	case MSG_SELECT_EFFECTYN:
		j.kv("k", std::string("effyn")).key("card"); writeCardRef(j, p.card); j.kvU("d", p.desc);
		break;
	case MSG_SELECT_YESNO:
		j.kv("k", std::string("yesno")).kvU("d", p.desc);
		break;
	case MSG_SELECT_OPTION:
	case MSG_ANNOUNCE_NUMBER:
		j.kv("k", std::string(p.type == MSG_SELECT_OPTION ? "option" : "number")).key("opts").beginArr();
		for(auto o : p.options) j.valU(o);
		j.endArr();
		break;
	case MSG_SELECT_CARD:
	case MSG_SELECT_TRIBUTE:
		j.kv("k", std::string(p.type == MSG_SELECT_CARD ? "card" : "tribute"))
		 .kv("min", (int64_t)p.min).kv("max", (int64_t)p.max).kv("cancel", p.cancelable);
		list("cards", p.cards);
		break;
	case MSG_SELECT_UNSELECT_CARD:
		j.kv("k", std::string("unselect")).kv("min", (int64_t)p.min).kv("max", (int64_t)p.max)
		 .kv("cancel", p.cancelable).kv("finish", p.finishable);
		list("cards", p.cards); list("sel", p.cards2);
		break;
	case MSG_SELECT_SUM:
		j.kv("k", std::string("sum")).kv("acc", (int64_t)p.acc).kv("exact", p.sumExact)
		 .kv("min", (int64_t)p.min).kv("max", (int64_t)p.max);
		list("must", p.cards2); list("cards", p.cards);
		break;
	case MSG_SELECT_CHAIN:
		j.kv("k", std::string("chain")).kv("forced", p.forced)
		 .kv("t1", (int64_t)p.timingSelf).kv("t2", (int64_t)p.timingOpp);
		list("cards", p.cards);
		break;
	case MSG_SELECT_PLACE:
	case MSG_SELECT_DISFIELD: {
		j.kv("k", std::string("place")).kv("count", (int64_t)p.count).kv("dis", p.type == MSG_SELECT_DISFIELD);
		j.key("zones").beginArr();
		for(int side = 0; side < 2; ++side) {
			uint32_t shift = side ? 16 : 0;
			for(int s = 0; s < 7; ++s)
				if(!(p.flag & ((1u << s) << shift))) j.beginObj().kv("pl", (int64_t)side).kv("l", (int64_t)LOC_MZONE).kv("s", (int64_t)s).endObj();
			for(int s = 0; s < 8; ++s)
				if(!(p.flag & (((1u << s) << 8) << shift))) j.beginObj().kv("pl", (int64_t)side).kv("l", (int64_t)LOC_SZONE).kv("s", (int64_t)s).endObj();
		}
		j.endArr();
		break;
	}
	case MSG_SELECT_POSITION:
		j.kv("k", std::string("position")).kvU("c", p.posCode).kv("pos", (int64_t)p.positions);
		break;
	case MSG_SELECT_COUNTER:
		j.kv("k", std::string("counter")).kv("ct", (int64_t)p.counterType).kv("count", (int64_t)p.counterCount);
		list("cards", p.cards);
		break;
	case MSG_ANNOUNCE_RACE:
	case MSG_ANNOUNCE_ATTRIB:
		j.kv("k", std::string(p.type == MSG_ANNOUNCE_RACE ? "race" : "attrib"))
		 .kv("count", (int64_t)p.count).kvU("avail", p.available);
		break;
	case MSG_ANNOUNCE_CARD:
		j.kv("k", std::string("anncard"));
		break;
	case MSG_ROCK_PAPER_SCISSORS:
		j.kv("k", std::string("rps"));
		break;
	default:
		j.kv("k", std::string("unknown")).kv("t", (int64_t)p.type);
	}
	j.endObj();
}

std::string DuelHost::buildView() {
	Json j;
	j.beginObj();
	if(duel) field.query(duel);
	j.key("state").beginObj();
	j.kv("turn", (int64_t)field.turn).kv("tp", (int64_t)rel(field.turnPlayer)).kv("phase", (int64_t)field.phase);
	j.key("chain").beginArr();
	for(auto& c : field.chain) j.beginObj().kvU("c", c.code).kv("pl", (int64_t)rel(c.con)).endObj();
	j.endArr();
	j.key("p").beginArr();
	for(int r = 0; r < 2; ++r) {
		int pl = r == 0 ? human : 1 - human;
		const auto& P = field.p[pl];
		const bool me = r == 0;
		j.beginObj();
		j.kv("lp", (int64_t)P.lp).kv("deck", (int64_t)P.deckCount).kv("xc", (int64_t)P.extraCount);
		j.key("hand").beginArr();
		for(auto& c : P.hand) writeFCard(j, c, !me && !c.isPublic);
		j.endArr();
		j.key("m").beginArr();
		for(auto& c : P.mzone) writeFCard(j, c, !me && !c.faceup());
		j.endArr();
		j.key("s").beginArr();
		for(auto& c : P.szone) writeFCard(j, c, !me && !c.faceup());
		j.endArr();
		j.key("gy").beginArr();
		for(auto& c : P.grave) writeFCard(j, c, false);
		j.endArr();
		j.key("ban").beginArr();
		for(auto& c : P.removed) writeFCard(j, c, !c.faceup() && !me);
		j.endArr();
		j.key("ex").beginArr();
		for(auto& c : P.extra) if(me || c.faceup()) writeFCard(j, c, false);
		j.endArr();
		j.endObj();
	}
	j.endArr();
	j.endObj(); // state
	j.key("log").beginArr();
	for(auto& l : log) j.raw(l);
	j.endArr();
	log.clear();
	if(hasHumanPrompt && !ended) { j.key("prompt"); writePrompt(j); }
	if(ended) {
		j.key("end").beginObj().kv("winner", (int64_t)winner).kv("reason", (int64_t)winReason).endObj();
	}
	if(!pendingError.empty()) { j.kv("error", pendingError); pendingError.clear(); }
	j.endObj();
	return j.str();
}

// ------------------------------------------------------------------ simulazione (bot Difficile)
namespace {
// true se nel blocco di messaggi c'è una richiesta di scelta o un MSG_RETRY
struct Scan { bool needResponse = false; bool newTurn = false; int winner = -1; };
}

double DuelHost::evaluate(const FieldState& f, int me, int winnerAbs) const {
	if(winnerAbs == me) return 1e7;
	if(winnerAbs == 1 - me) return -1e7;
	auto side = [&](int pl) {
		const auto& P = f.p[pl];
		double v = 0;
		for(auto& m : P.mzone) {
			if(!m.exists) continue;
			v += 450;
			v += m.faceup() ? std::max<double>(m.atk, m.def * 0.6) * 0.5 : 600;
			v += m.overlays * 150;
		}
		for(int i = 0; i < 5; ++i) if(P.szone[i].exists) v += P.szone[i].faceup() ? 250 : 450;
		if(P.szone[5].exists) v += 300;
		if(P.szone[6].exists) v += 200;
		if(P.szone[7].exists) v += 200;
		v += P.hand.size() * 380.0;
		v += P.grave.size() * 25.0 + P.removed.size() * 8.0;
		return v;
	};
	double score = side(me) - side(1 - me);
	score += (f.p[me].lp - f.p[1 - me].lp) * 0.35;
	score += (8000 - std::min(8000, f.p[1 - me].lp)) * 0.15;
	return score;
}

double DuelHost::lookahead(const Choice& cand) {
	const int me = prompt.player;
	simulating = true;
	void* sd = createEngine(nullptr);
	if(!sd) { simulating = false; return -1e9; }
	auto scan = [&](const uint8_t* buf, uint32_t len, Prompt* outPrompt, uint64_t* hints, Bot* b1, Bot* b2) {
		Scan sc;
		Reader all(buf, len);
		while(all.left() >= 4) {
			uint32_t ml = all.u32();
			if(ml == 0 || !all.ok(ml)) break;
			Reader rd(all.p, ml);
			all.skip(ml);
			uint8_t t = rd.u8();
			if(t == MSG_RETRY) { sc.needResponse = true; continue; }
			if(isSelectMessage(t)) {
				sc.needResponse = true;
				if(outPrompt && parsePrompt(t, rd, *outPrompt) && hints) {
					outPrompt->hint = hints[outPrompt->player & 1];
					hints[outPrompt->player & 1] = 0;
				}
				continue;
			}
			if(t == MSG_HINT && hints) {
				uint8_t ht = rd.u8(); uint8_t pl = rd.u8(); uint64_t d = rd.u64();
				if(ht == HINT_SELECTMSG && pl < 2) hints[pl] = d;
			} else if(t == MSG_NEW_TURN) {
				sc.newTurn = true;
			} else if(t == MSG_WIN) {
				uint8_t pl = rd.u8();
				sc.winner = pl;
			} else if(t == MSG_NEW_PHASE && b1) {
				uint16_t ph = rd.u16();
				b1->onNewPhase(ph); if(b2) b2->onNewPhase(ph);
			} else if((t == MSG_SUMMONING || t == MSG_SPSUMMONING) && b1) {
				rd.u32(); uint8_t con = rd.u8();
				b1->onSummoning(con); if(b2) b2->onSummoning(con);
			} else if((t == MSG_SUMMONED || t == MSG_SPSUMMONED) && b1) {
				b1->onSummoned(); if(b2) b2->onSummoned();
			}
		}
		return sc;
	};
	// 1) ripeti la partita fino al punto attuale
	size_t idx = 0;
	bool atPoint = false;
	for(int guard = 0; guard < 400000; ++guard) {
		int st = OCG_DuelProcess(sd);
		uint32_t len = 0;
		auto* buf = static_cast<const uint8_t*>(OCG_DuelGetMessage(sd, &len));
		Scan sc = scan(buf, len, nullptr, nullptr, nullptr, nullptr);
		if(sc.needResponse) {
			if(idx < history.size()) {
				OCG_DuelSetResponse(sd, history[idx].data(), (uint32_t)history[idx].size());
				++idx;
				continue;
			}
			atPoint = true;
			break;
		}
		if(st == OCG_DUEL_STATUS_END) break;
	}
	if(!atPoint) { OCG_DestroyDuel(sd); simulating = false; return -1e9; }
	// 2) applica la mossa candidata
	auto resp = encodeResponse(prompt, cand);
	OCG_DuelSetResponse(sd, resp.data(), (uint32_t)resp.size());
	// 3) gioca il resto del turno: noi con la logica "media", l'avversario passivo
	Bot mine(1, db, history.size() * 7919 + 1), opp(1, db, history.size() * 104729 + 3);
	opp.setPassive(true);
	FieldState sf = field;
	mine.onNewTurn(sf.turnPlayer); opp.onNewTurn(sf.turnPlayer);
	mine.onNewPhase(sf.phase); opp.onNewPhase(sf.phase);
	uint64_t hints[2] = {0, 0};
	Prompt sp;
	int winnerAbs = -1, retriesHere = 0;
	for(int steps = 0; steps < 500; ) {
		int st = OCG_DuelProcess(sd);
		uint32_t len = 0;
		auto* buf = static_cast<const uint8_t*>(OCG_DuelGetMessage(sd, &len));
		Prompt np;
		bool hadRetry = false;
		{
			// distinguiamo il retry dalla nuova richiesta
			Reader all(buf, len);
			while(all.left() >= 4) { uint32_t ml = all.u32(); if(!ml || !all.ok(ml)) break; if(*all.p == MSG_RETRY) hadRetry = true; all.skip(ml); }
		}
		Scan sc = scan(buf, len, &np, hints, &mine, &opp);
		if(sc.winner >= 0) { winnerAbs = sc.winner > 1 ? 2 : sc.winner; break; }
		if(sc.newTurn) break;
		if(sc.needResponse) {
			if(hadRetry) ++retriesHere; else { retriesHere = 0; sp = np; }
			if(retriesHere > 8) break;
			sf.query(sd);
			Bot& b = sp.player == me ? mine : opp;
			Choice c = retriesHere == 0 ? b.decide(sp, sf, sp.player) : b.fallback(sp, sf, sp.player, retriesHere);
			auto r = encodeResponse(sp, c);
			OCG_DuelSetResponse(sd, r.data(), (uint32_t)r.size());
			++steps;
			continue;
		}
		if(st == OCG_DUEL_STATUS_END) break;
	}
	sf.query(sd);
	double v = evaluate(sf, me, winnerAbs);
	OCG_DestroyDuel(sd);
	simulating = false;
	return v;
}

// ------------------------------------------------------------------ database carte
bool CardDB::loadBinary(const std::string& path) {
	FILE* f = fopen(path.c_str(), "rb");
	if(!f) return false;
	std::vector<uint8_t> data;
	uint8_t tmp[65536];
	size_t n;
	while((n = fread(tmp, 1, sizeof(tmp), f)) > 0) data.insert(data.end(), tmp, tmp + n);
	fclose(f);
	return loadFromMemory(data.data(), data.size());
}

bool CardDB::loadFromMemory(const uint8_t* data, size_t len) {
	Reader rd(data, len);
	if(len < 12 || std::memcmp(data, "YGTC", 4) != 0) return false;
	rd.skip(4);
	if(rd.u32() != 1) return false;
	uint32_t count = rd.u32();
	cards.clear();
	cards.reserve(count);
	for(uint32_t i = 0; i < count && rd.left() >= 52; ++i) {
		CardInfo c;
		c.code = rd.u32();
		c.alias = rd.u32();
		uint64_t set = rd.u64();
		int k = 0;
		for(int s = 0; s < 4; ++s) {
			uint16_t v = (uint16_t)((set >> (s * 16)) & 0xffff);
			if(v) c.setcodes[k++] = v;
		}
		c.type = rd.u32();
		uint32_t lv = rd.u32();
		c.level = lv & 0xff;
		c.lscale = (lv >> 24) & 0xff;
		c.rscale = (lv >> 16) & 0xff;
		c.attribute = rd.u32();
		c.race = rd.u64();
		c.atk = rd.i32();
		c.def = rd.i32();
		c.category = rd.u64();
		if(c.type & TYPE_LINK) { c.link = (uint32_t)c.def; c.def = 0; }
		cards[c.code] = c;
	}
	return !cards.empty();
}

} // namespace ygt
