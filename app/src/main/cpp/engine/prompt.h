// Richieste di scelta del motore (MSG_SELECT_*) e codifica delle risposte.
#pragma once
#include "common.h"

namespace ygt {

struct CardRef {
	uint32_t code = 0;
	uint8_t con = 0;
	uint32_t loc = 0;
	uint32_t seq = 0;
	uint32_t pos = 0;
	uint64_t desc = 0;   // descrizione effetto (attivazioni)
	uint8_t mode = 0;    // client mode (attivazioni)
	uint32_t param = 0;  // release_param / sum_param / counter / direct_attackable
};

struct Prompt {
	int type = 0;          // id messaggio
	int player = 0;        // giocatore che deve scegliere (indice motore)
	uint64_t hint = 0;     // ultimo HINT_SELECTMSG ricevuto per il giocatore
	// IDLE / BATTLE
	std::vector<CardRef> summon, spsummon, repos, mset, sset, activate, attack;
	bool toBP = false, toEP = false, toM2 = false, canShuffle = false;
	// generico
	CardRef card;          // EFFECTYN
	uint64_t desc = 0;     // EFFECTYN / YESNO
	std::vector<uint64_t> options; // OPTION / ANNOUNCE_CARD (opcodes) / ANNOUNCE_NUMBER
	bool cancelable = false, finishable = false;
	uint32_t min = 0, max = 0;
	std::vector<CardRef> cards;    // SELECT_CARD / TRIBUTE / SUM (selezionabili) / CHAIN / COUNTER / SORT
	std::vector<CardRef> cards2;   // SUM obbligatorie / UNSELECT già selezionate
	uint32_t acc = 0;              // SUM
	bool sumExact = true;          // SUM: true = somma esatta
	uint8_t specount = 0; bool forced = false; // CHAIN
	uint32_t timingSelf = 0, timingOpp = 0;     // CHAIN: momenti di gioco
	uint8_t count = 0; uint32_t flag = 0;      // PLACE
	uint32_t posCode = 0; uint8_t positions = 0; // POSITION
	uint16_t counterType = 0; uint16_t counterCount = 0; // COUNTER
	uint64_t available = 0;        // ANNOUNCE_RACE / ATTRIB
};

// Una risposta "astratta", tradotta in byte da encodeResponse
struct Choice {
	int a = 0;               // IDLE/BATTLE: tipo comando; altri: valore/indice; -1 = annulla
	int b = 0;               // IDLE/BATTLE: indice
	std::vector<int> idx;    // selezione multipla / contatori / ordine
	std::vector<uint8_t> places; // triple (giocatore, location, seq)
	uint64_t mask = 0;       // annunci tipo/attributo
};

// Legge un messaggio di scelta. Ritorna false se il tipo non è una scelta.
bool parsePrompt(uint8_t msgType, Reader& rd, Prompt& out);
bool isSelectMessage(uint8_t msgType);
std::vector<uint8_t> encodeResponse(const Prompt& p, const Choice& c);

} // namespace ygt
