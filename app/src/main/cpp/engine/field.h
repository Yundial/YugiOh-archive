// Fotografia dello stato del campo, letta dal motore tramite query.
#pragma once
#include "common.h"

namespace ygt {

struct FCard {
	bool exists = false;
	uint32_t code = 0;
	uint32_t pos = 0;
	uint32_t type = 0;
	uint32_t level = 0, rank = 0, link = 0;
	uint32_t attribute = 0;
	uint64_t race = 0;
	int32_t atk = 0, def = 0, baseAtk = 0;
	uint32_t overlays = 0;
	bool isPublic = false;
	uint8_t con = 0;
	uint32_t loc = 0, seq = 0;
	bool faceup() const { return pos & POS_FACEUP; }
	bool isMonster() const { return type & TYPE_MONSTER; }
	int stars() const { return link ? (int)link : rank ? (int)rank : (int)level; }
};

struct PlayerField {
	int32_t lp = 8000;
	FCard mzone[7];
	FCard szone[8];
	std::vector<FCard> hand, grave, removed, extra;
	uint32_t deckCount = 0, extraCount = 0, extraFaceUp = 0;
	int monsterCount() const { int n = 0; for(auto& c : mzone) if(c.exists) ++n; return n; }
	int stCount() const { int n = 0; for(int i = 0; i < 5; ++i) if(szone[i].exists) ++n; return n; }
};

struct ChainLink { uint32_t code = 0; uint8_t con = 0; uint32_t loc = 0; uint64_t desc = 0; };

struct FieldState {
	PlayerField p[2];
	std::vector<ChainLink> chain;
	int turn = 0;          // numero turno
	int turnPlayer = 0;    // indice motore
	uint32_t phase = 0;
	void query(void* duel);
	const FCard* find(uint8_t con, uint32_t loc, uint32_t seq) const;
};

} // namespace ygt
