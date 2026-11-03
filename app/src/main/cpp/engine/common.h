// Tipi e utilità condivise del motore di YGO Tester.
#pragma once
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <unordered_map>
#include <functional>

namespace ygt {

// ---- costanti ocgcore (sottoinsieme) ----
constexpr uint32_t LOC_DECK = 0x01, LOC_HAND = 0x02, LOC_MZONE = 0x04, LOC_SZONE = 0x08,
                   LOC_GRAVE = 0x10, LOC_REMOVED = 0x20, LOC_EXTRA = 0x40, LOC_OVERLAY = 0x80;
constexpr uint32_t LOC_ONFIELD = LOC_MZONE | LOC_SZONE;

constexpr uint32_t POS_FACEUP_ATTACK = 0x1, POS_FACEDOWN_ATTACK = 0x2,
                   POS_FACEUP_DEFENSE = 0x4, POS_FACEDOWN_DEFENSE = 0x8;
constexpr uint32_t POS_FACEUP = 0x5, POS_FACEDOWN = 0xa, POS_ATTACK = 0x3, POS_DEFENSE = 0xc;

constexpr uint32_t TYPE_MONSTER = 0x1, TYPE_SPELL = 0x2, TYPE_TRAP = 0x4, TYPE_NORMAL = 0x10,
                   TYPE_EFFECT = 0x20, TYPE_FUSION = 0x40, TYPE_RITUAL = 0x80, TYPE_TRAPMONSTER = 0x100,
                   TYPE_SPIRIT = 0x200, TYPE_UNION = 0x400, TYPE_GEMINI = 0x800, TYPE_TUNER = 0x1000,
                   TYPE_SYNCHRO = 0x2000, TYPE_TOKEN = 0x4000, TYPE_QUICKPLAY = 0x10000,
                   TYPE_CONTINUOUS = 0x20000, TYPE_EQUIP = 0x40000, TYPE_FIELD = 0x80000,
                   TYPE_COUNTER = 0x100000, TYPE_FLIP = 0x200000, TYPE_TOON = 0x400000,
                   TYPE_XYZ = 0x800000, TYPE_PENDULUM = 0x1000000, TYPE_SPSUMMON = 0x2000000,
                   TYPE_LINK = 0x4000000;
constexpr uint32_t TYPE_EXTRA = TYPE_FUSION | TYPE_SYNCHRO | TYPE_XYZ | TYPE_LINK;

// Categorie del database (cdb, usate dalla ricerca di EDOPro)
constexpr uint64_t CAT_DESTROY_MONSTER = 0x1, CAT_DESTROY_ST = 0x2, CAT_NEGATE_SUMMON = 0x10000,
                   CAT_NEGATE_EFFECT = 0x20000;

// Messaggi
enum Msg : uint8_t {
	MSG_RETRY = 1, MSG_HINT = 2, MSG_WAITING = 3, MSG_START = 4, MSG_WIN = 5,
	MSG_SELECT_BATTLECMD = 10, MSG_SELECT_IDLECMD = 11, MSG_SELECT_EFFECTYN = 12,
	MSG_SELECT_YESNO = 13, MSG_SELECT_OPTION = 14, MSG_SELECT_CARD = 15, MSG_SELECT_CHAIN = 16,
	MSG_SELECT_PLACE = 18, MSG_SELECT_POSITION = 19, MSG_SELECT_TRIBUTE = 20, MSG_SORT_CHAIN = 21,
	MSG_SELECT_COUNTER = 22, MSG_SELECT_SUM = 23, MSG_SELECT_DISFIELD = 24, MSG_SORT_CARD = 25,
	MSG_SELECT_UNSELECT_CARD = 26, MSG_CONFIRM_DECKTOP = 30, MSG_CONFIRM_CARDS = 31,
	MSG_SHUFFLE_DECK = 32, MSG_SHUFFLE_HAND = 33, MSG_NEW_TURN = 40, MSG_NEW_PHASE = 41,
	MSG_MOVE = 50, MSG_POS_CHANGE = 53, MSG_SET = 54, MSG_SWAP = 55,
	MSG_SUMMONING = 60, MSG_SUMMONED = 61, MSG_SPSUMMONING = 62, MSG_SPSUMMONED = 63,
	MSG_FLIPSUMMONING = 64, MSG_FLIPSUMMONED = 65, MSG_CHAINING = 70, MSG_CHAINED = 71,
	MSG_CHAIN_SOLVING = 72, MSG_CHAIN_SOLVED = 73, MSG_CHAIN_END = 74, MSG_CHAIN_NEGATED = 75,
	MSG_CHAIN_DISABLED = 76, MSG_DRAW = 90, MSG_DAMAGE = 91, MSG_RECOVER = 92, MSG_EQUIP = 93,
	MSG_LPUPDATE = 94, MSG_PAY_LPCOST = 100, MSG_ATTACK = 110, MSG_BATTLE = 111,
	MSG_TOSS_COIN = 130, MSG_TOSS_DICE = 131, MSG_ROCK_PAPER_SCISSORS = 132, MSG_HAND_RES = 133,
	MSG_ANNOUNCE_RACE = 140, MSG_ANNOUNCE_ATTRIB = 141, MSG_ANNOUNCE_CARD = 142,
	MSG_ANNOUNCE_NUMBER = 143
};
constexpr uint8_t HINT_SELECTMSG = 3;

constexpr uint32_t PHASE_DRAW = 0x01, PHASE_STANDBY = 0x02, PHASE_MAIN1 = 0x04,
                   PHASE_BATTLE_START = 0x08, PHASE_BATTLE_STEP = 0x10, PHASE_DAMAGE = 0x20,
                   PHASE_DAMAGE_CAL = 0x40, PHASE_BATTLE = 0x80, PHASE_MAIN2 = 0x100, PHASE_END = 0x200;

// ---- lettore binario ----
struct Reader {
	const uint8_t* p; const uint8_t* end;
	Reader(const uint8_t* d, size_t n) : p(d), end(d + n) {}
	bool ok(size_t n) const { return p + n <= end; }
	template<typename T> T r() {
		T v{};
		if(ok(sizeof(T))) { std::memcpy(&v, p, sizeof(T)); p += sizeof(T); } else p = end;
		return v;
	}
	uint8_t u8() { return r<uint8_t>(); }
	uint16_t u16() { return r<uint16_t>(); }
	uint32_t u32() { return r<uint32_t>(); }
	int32_t i32() { return r<int32_t>(); }
	uint64_t u64() { return r<uint64_t>(); }
	void skip(size_t n) { p = ok(n) ? p + n : end; }
	size_t left() const { return end - p; }
};

struct Writer {
	std::vector<uint8_t> b;
	template<typename T> void w(T v) {
		size_t s = b.size(); b.resize(s + sizeof(T)); std::memcpy(&b[s], &v, sizeof(T));
	}
};

// ---- dati carta ----
struct CardInfo {
	uint32_t code = 0, alias = 0;
	uint16_t setcodes[5] = {0, 0, 0, 0, 0}; // terminato da 0
	uint32_t type = 0, level = 0, attribute = 0;
	uint64_t race = 0;
	int32_t atk = 0, def = 0;
	uint32_t lscale = 0, rscale = 0, link = 0;
	uint64_t category = 0;
	bool isMonster() const { return type & TYPE_MONSTER; }
	bool isExtra() const { return isMonster() && (type & TYPE_EXTRA); }
};

class CardDB {
public:
	// Formato cards.bin: "YGTC", u32 versione(1), u32 numero, poi record da 52 byte
	bool loadBinary(const std::string& path);
	bool loadFromMemory(const uint8_t* data, size_t len);
	const CardInfo* get(uint32_t code) const {
		auto it = cards.find(code);
		return it == cards.end() ? nullptr : &it->second;
	}
	const std::unordered_map<uint32_t, CardInfo>& all() const { return cards; }
private:
	std::unordered_map<uint32_t, CardInfo> cards;
};

// Restituisce il contenuto di uno script (es. "c12345.lua", "utility.lua") o stringa vuota
using ScriptLoader = std::function<std::string(const std::string& name)>;

struct Deck { std::vector<uint32_t> main, extra; };

} // namespace ygt
