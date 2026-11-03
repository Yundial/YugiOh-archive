#include "prompt.h"

namespace ygt {

static void readLocInfo(Reader& rd, CardRef& c) {
	c.con = rd.u8();
	c.loc = rd.u8();
	c.seq = rd.u32();
	c.pos = rd.u32();
}

bool isSelectMessage(uint8_t t) {
	switch(t) {
	case MSG_SELECT_BATTLECMD: case MSG_SELECT_IDLECMD: case MSG_SELECT_EFFECTYN:
	case MSG_SELECT_YESNO: case MSG_SELECT_OPTION: case MSG_SELECT_CARD: case MSG_SELECT_CHAIN:
	case MSG_SELECT_PLACE: case MSG_SELECT_POSITION: case MSG_SELECT_TRIBUTE: case MSG_SORT_CHAIN:
	case MSG_SELECT_COUNTER: case MSG_SELECT_SUM: case MSG_SELECT_DISFIELD: case MSG_SORT_CARD:
	case MSG_SELECT_UNSELECT_CARD: case MSG_ROCK_PAPER_SCISSORS: case MSG_ANNOUNCE_RACE:
	case MSG_ANNOUNCE_ATTRIB: case MSG_ANNOUNCE_CARD: case MSG_ANNOUNCE_NUMBER:
		return true;
	default:
		return false;
	}
}

bool parsePrompt(uint8_t t, Reader& rd, Prompt& p) {
	p = Prompt{};
	p.type = t;
	if(t == MSG_ROCK_PAPER_SCISSORS) { p.player = rd.u8(); return true; }
	p.player = rd.u8();
	switch(t) {
	case MSG_SELECT_IDLECMD: {
		auto readList = [&](std::vector<CardRef>& v, bool seq8) {
			uint32_t n = rd.u32();
			for(uint32_t i = 0; i < n && rd.left(); ++i) {
				CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u8();
				c.seq = seq8 ? rd.u8() : rd.u32();
				v.push_back(c);
			}
		};
		readList(p.summon, false);
		readList(p.spsummon, false);
		readList(p.repos, true);
		readList(p.mset, false);
		readList(p.sset, false);
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u8(); c.seq = rd.u32();
			c.desc = rd.u64(); c.mode = rd.u8();
			p.activate.push_back(c);
		}
		p.toBP = rd.u8(); p.toEP = rd.u8(); p.canShuffle = rd.u8();
		return true;
	}
	case MSG_SELECT_BATTLECMD: {
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u8(); c.seq = rd.u32();
			c.desc = rd.u64(); c.mode = rd.u8();
			p.activate.push_back(c);
		}
		n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u8(); c.seq = rd.u8();
			c.param = rd.u8();
			p.attack.push_back(c);
		}
		p.toM2 = rd.u8(); p.toEP = rd.u8();
		return true;
	}
	case MSG_SELECT_EFFECTYN:
		p.card.code = rd.u32(); readLocInfo(rd, p.card); p.desc = rd.u64();
		return true;
	case MSG_SELECT_YESNO:
		p.desc = rd.u64();
		return true;
	case MSG_SELECT_OPTION: {
		uint8_t n = rd.u8();
		for(int i = 0; i < n; ++i) p.options.push_back(rd.u64());
		return true;
	}
	case MSG_SELECT_CARD: {
		p.cancelable = rd.u8(); p.min = rd.u32(); p.max = rd.u32();
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); readLocInfo(rd, c); p.cards.push_back(c);
		}
		return true;
	}
	case MSG_SELECT_TRIBUTE: {
		p.cancelable = rd.u8(); p.min = rd.u32(); p.max = rd.u32();
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u8(); c.seq = rd.u32();
			c.param = rd.u8();
			p.cards.push_back(c);
		}
		return true;
	}
	case MSG_SELECT_UNSELECT_CARD: {
		p.finishable = rd.u8(); p.cancelable = rd.u8(); p.min = rd.u32(); p.max = rd.u32();
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); readLocInfo(rd, c); p.cards.push_back(c);
		}
		n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); readLocInfo(rd, c); p.cards2.push_back(c);
		}
		return true;
	}
	case MSG_SELECT_CHAIN: {
		p.specount = rd.u8(); p.forced = rd.u8();
		p.timingSelf = rd.u32(); p.timingOpp = rd.u32();
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); readLocInfo(rd, c); c.desc = rd.u64(); c.mode = rd.u8();
			p.cards.push_back(c);
		}
		return true;
	}
	case MSG_SELECT_PLACE:
	case MSG_SELECT_DISFIELD:
		p.count = rd.u8(); p.flag = rd.u32();
		return true;
	case MSG_SELECT_POSITION:
		p.posCode = rd.u32(); p.positions = rd.u8();
		return true;
	case MSG_SELECT_COUNTER: {
		p.counterType = rd.u16(); p.counterCount = rd.u16();
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u8(); c.seq = rd.u8();
			c.param = rd.u16();
			p.cards.push_back(c);
		}
		return true;
	}
	case MSG_SELECT_SUM: {
		p.sumExact = rd.u8() == 0;
		p.acc = rd.u32(); p.min = rd.u32(); p.max = rd.u32();
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); readLocInfo(rd, c); c.param = rd.u32(); p.cards2.push_back(c);
		}
		n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); readLocInfo(rd, c); c.param = rd.u32(); p.cards.push_back(c);
		}
		return true;
	}
	case MSG_SORT_CARD:
	case MSG_SORT_CHAIN: {
		uint32_t n = rd.u32();
		for(uint32_t i = 0; i < n && rd.left(); ++i) {
			CardRef c; c.code = rd.u32(); c.con = rd.u8(); c.loc = rd.u32(); c.seq = rd.u32();
			p.cards.push_back(c);
		}
		return true;
	}
	case MSG_ANNOUNCE_RACE:
		p.count = rd.u8(); p.available = rd.u64();
		return true;
	case MSG_ANNOUNCE_ATTRIB:
		p.count = rd.u8(); p.available = rd.u32();
		return true;
	case MSG_ANNOUNCE_CARD:
	case MSG_ANNOUNCE_NUMBER: {
		uint8_t n = rd.u8();
		for(int i = 0; i < n; ++i) p.options.push_back(rd.u64());
		return true;
	}
	default:
		return false;
	}
}

std::vector<uint8_t> encodeResponse(const Prompt& p, const Choice& c) {
	Writer w;
	switch(p.type) {
	case MSG_SELECT_IDLECMD:
	case MSG_SELECT_BATTLECMD:
		w.w<int32_t>((c.a & 0xffff) | (c.b << 16));
		break;
	case MSG_SELECT_CARD:
	case MSG_SELECT_TRIBUTE:
	case MSG_SELECT_SUM:
		if(c.a < 0) { w.w<int32_t>(-1); break; }
		w.w<int32_t>(0);
		w.w<uint32_t>((uint32_t)c.idx.size());
		for(int i : c.idx) w.w<uint32_t>((uint32_t)i);
		break;
	case MSG_SELECT_UNSELECT_CARD:
		if(c.a < 0) { w.w<int32_t>(-1); break; }
		w.w<int32_t>(1);
		w.w<int32_t>(c.b);
		break;
	case MSG_SELECT_PLACE:
	case MSG_SELECT_DISFIELD:
		for(uint8_t v : c.places) w.w<uint8_t>(v);
		break;
	case MSG_SELECT_COUNTER:
		for(int v : c.idx) w.w<int16_t>((int16_t)v);
		break;
	case MSG_SORT_CARD:
	case MSG_SORT_CHAIN:
		if(c.idx.empty()) w.w<int8_t>(-1);
		else for(int v : c.idx) w.w<int8_t>((int8_t)v);
		break;
	case MSG_ANNOUNCE_RACE:
		w.w<uint64_t>(c.mask);
		break;
	case MSG_ANNOUNCE_ATTRIB:
		w.w<uint32_t>((uint32_t)c.mask);
		break;
	default: // EFFECTYN, YESNO, OPTION, CHAIN, POSITION, ANNOUNCE_CARD, ANNOUNCE_NUMBER, RPS
		w.w<int32_t>(c.a);
		break;
	}
	return w.b;
}

} // namespace ygt
