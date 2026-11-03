#include "field.h"
#include "ocgapi.h"

namespace ygt {

namespace {
constexpr uint32_t Q_CODE = 0x1, Q_POSITION = 0x2, Q_TYPE = 0x8, Q_LEVEL = 0x10, Q_RANK = 0x20,
                   Q_ATTRIBUTE = 0x40, Q_RACE = 0x80, Q_ATTACK = 0x100, Q_DEFENSE = 0x200,
                   Q_BASE_ATTACK = 0x400, Q_OVERLAY = 0x10000, Q_IS_PUBLIC = 0x100000,
                   Q_LINK = 0x800000, Q_END = 0x80000000;
constexpr uint32_t QUERY_FLAGS = Q_CODE | Q_POSITION | Q_TYPE | Q_LEVEL | Q_RANK | Q_ATTRIBUTE |
                                 Q_RACE | Q_ATTACK | Q_DEFENSE | Q_BASE_ATTACK | Q_OVERLAY | Q_LINK;

// Legge una sequenza di carte; per le zone (mzone/szone) i buchi diventano carte vuote
std::vector<FCard> readCards(Reader& rd) {
	std::vector<FCard> out;
	while(rd.left() >= 2) {
		uint16_t size = rd.u16();
		if(size == 0) { out.emplace_back(); continue; } // slot vuoto
		FCard c; c.exists = true;
		// prima voce già iniziata: leggiamo il flag
		while(true) {
			uint32_t flag = rd.u32();
			size_t payload = size >= 4 ? size - 4 : 0;
			if(flag == Q_END || !rd.left()) break;
			Reader sub(rd.p, std::min(payload, rd.left()));
			switch(flag) {
			case Q_CODE: c.code = sub.u32(); break;
			case Q_POSITION: c.pos = sub.u32(); break;
			case Q_TYPE: c.type = sub.u32(); break;
			case Q_LEVEL: c.level = sub.u32(); break;
			case Q_RANK: c.rank = sub.u32(); break;
			case Q_ATTRIBUTE: c.attribute = sub.u32(); break;
			case Q_RACE: c.race = sub.u64(); break;
			case Q_ATTACK: c.atk = sub.i32(); break;
			case Q_DEFENSE: c.def = sub.i32(); break;
			case Q_BASE_ATTACK: c.baseAtk = sub.i32(); break;
			case Q_OVERLAY: c.overlays = sub.u32(); break;
			case Q_IS_PUBLIC: c.isPublic = sub.u8(); break;
			case Q_LINK: c.link = sub.u32(); break;
			default: break;
			}
			rd.skip(payload);
			if(rd.left() < 2) break;
			size = rd.u16();
		}
		out.push_back(c);
	}
	return out;
}
}

void FieldState::query(void* duel) {
	for(int pl = 0; pl < 2; ++pl) {
		auto& P = p[pl];
		auto q = [&](uint32_t loc) {
			OCG_QueryInfo info{QUERY_FLAGS, (uint8_t)pl, loc, 0, 0};
			uint32_t len = 0;
			auto* buf = static_cast<uint8_t*>(OCG_DuelQueryLocation(duel, &len, &info));
			if(!buf || len < 4) return std::vector<FCard>{};
			Reader rd(buf + 4, len - 4);
			auto v = readCards(rd);
			for(size_t i = 0; i < v.size(); ++i) { v[i].con = (uint8_t)pl; v[i].loc = loc; v[i].seq = (uint32_t)i; }
			return v;
		};
		auto mz = q(LOC_MZONE);
		for(int i = 0; i < 7; ++i) P.mzone[i] = i < (int)mz.size() ? mz[i] : FCard{};
		auto sz = q(LOC_SZONE);
		for(int i = 0; i < 8; ++i) P.szone[i] = i < (int)sz.size() ? sz[i] : FCard{};
		P.hand = q(LOC_HAND);
		P.grave = q(LOC_GRAVE);
		P.removed = q(LOC_REMOVED);
		P.extra = q(LOC_EXTRA);
		for(int i = 0; i < 7; ++i) { P.mzone[i].con = (uint8_t)pl; P.mzone[i].loc = LOC_MZONE; P.mzone[i].seq = i; }
		for(int i = 0; i < 8; ++i) { P.szone[i].con = (uint8_t)pl; P.szone[i].loc = LOC_SZONE; P.szone[i].seq = i; }
		P.deckCount = OCG_DuelQueryCount(duel, (uint8_t)pl, LOC_DECK);
		P.extraCount = (uint32_t)P.extra.size();
		P.extraFaceUp = 0;
		for(auto& c : P.extra) if(c.faceup()) ++P.extraFaceUp;
	}
	// LP e catena dal riepilogo del campo
	uint32_t len = 0;
	auto* buf = static_cast<uint8_t*>(OCG_DuelQueryField(duel, &len));
	if(!buf) return;
	Reader rd(buf, len);
	rd.u32(); // opzioni
	for(int pl = 0; pl < 2; ++pl) {
		p[pl].lp = (int32_t)rd.u32();
		for(int i = 0; i < 7 + 8; ++i) {
			if(rd.u8()) { rd.u8(); rd.u32(); }
		}
		rd.u32(); rd.u32(); rd.u32(); rd.u32(); rd.u32(); rd.u32();
	}
	chain.clear();
	uint32_t n = rd.u32();
	for(uint32_t i = 0; i < n && rd.left(); ++i) {
		ChainLink cl; cl.code = rd.u32();
		rd.u8(); rd.u8(); rd.u32(); rd.u32();
		cl.con = rd.u8(); cl.loc = rd.u8(); rd.u32(); cl.desc = rd.u64();
		chain.push_back(cl);
	}
}

const FCard* FieldState::find(uint8_t con, uint32_t loc, uint32_t seq) const {
	if(con > 1) return nullptr;
	auto& P = p[con];
	auto at = [&](const std::vector<FCard>& v) -> const FCard* { return seq < v.size() ? &v[seq] : nullptr; };
	switch(loc & 0x7f) {
	case LOC_MZONE: return seq < 7 ? &P.mzone[seq] : nullptr;
	case LOC_SZONE: return seq < 8 ? &P.szone[seq] : nullptr;
	case LOC_HAND: return at(P.hand);
	case LOC_GRAVE: return at(P.grave);
	case LOC_REMOVED: return at(P.removed);
	case LOC_EXTRA: return at(P.extra);
	default: return nullptr;
	}
}

} // namespace ygt
