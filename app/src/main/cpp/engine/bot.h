// L'avversario virtuale: tre livelli con stili di gioco diversi.
//  0 = Facile:   gioca per divertirsi, scelte spesso casuali, non usa negazioni con criterio
//  1 = Medio:    gioca per vincere, combo di base, evocazioni dall'Extra, attacchi ragionati
//  2 = Difficile: massimizza il campo, nega sempre le giocate avversarie, cerca il letale
#pragma once
#include "prompt.h"
#include "field.h"
#include <random>
#include <map>
#include <set>

namespace ygt {

class Bot {
public:
	Bot(int difficulty, const CardDB& db, uint64_t seed);
	Choice decide(const Prompt& p, const FieldState& f, int me);
	// risposta di ripiego quando il motore rifiuta una scelta (MSG_RETRY)
	Choice fallback(const Prompt& p, const FieldState& f, int me, int attempt);

	// eventi dal duello
	void onNewTurn(int turnPlayer);
	void onNewPhase(uint32_t phase);
	void onSummoning(int player);   // inizio evocazione (finestra di negazione)
	void onSummoned();              // evocazione conclusa
	void onChainEnd();
	void onAttack(int player);
	void onSeenCard(int player, uint32_t code); // carta avversaria vista (per le dichiarazioni)
	// Difficile: funzione che valuta una mossa simulando il resto del turno
	void setLookahead(std::function<double(const Choice&)> fn) { lookahead = std::move(fn); }
	// Nelle simulazioni l'avversario resta passivo (non risponde con catene)
	void setPassive(bool v) { passive = v; }
	void setThinkBudgetMs(int ms) { thinkMs = ms; }

private:
	int diff;
	const CardDB& db;
	std::mt19937_64 rng;
	int turnPlayer = 0;
	uint32_t phase = 0;
	int pendingSummonBy = -1;
	int attackBy = -1;
	int actionsThisPhase = 0;
	int extraSummonsThisTurn = 0;
	std::map<uint64_t, int> usedThisTurn;   // chiave effetto -> volte usato
	std::set<uint64_t> attackTried;         // attaccanti già provati in questa battle phase
	uint64_t plannedAttacker = 0;
	std::set<uint32_t> seenOpp;
	std::function<double(const Choice&)> lookahead;
	bool passive = false;
	int thinkMs = 900;

	bool chance(double p) { return std::uniform_real_distribution<double>(0, 1)(rng) < p; }
	int rnd(int n) { return n <= 0 ? 0 : (int)(rng() % (uint64_t)n); }

	int64_t value(uint32_t code, const FCard* onField = nullptr) const;
	int32_t atkOf(const CardRef& c, const FieldState& f) const;
	int32_t oppMaxAtk(const FieldState& f, int me) const;
	bool isNegation(uint32_t code) const;
	static uint64_t key(uint32_t code, uint64_t desc) { return ((uint64_t)code << 20) ^ desc; }

	Choice idle(const Prompt& p, const FieldState& f, int me);
	Choice idleHeuristic(const Prompt& p, const FieldState& f, int me);
	Choice idleSearch(const Prompt& p, const FieldState& f, int me);
	Choice battle(const Prompt& p, const FieldState& f, int me);
	Choice chain(const Prompt& p, const FieldState& f, int me);
	Choice selectCards(const Prompt& p, const FieldState& f, int me);
	Choice unselect(const Prompt& p, const FieldState& f, int me);
	Choice tribute(const Prompt& p, const FieldState& f, int me);
	Choice sum(const Prompt& p, const FieldState& f, int me);
	Choice place(const Prompt& p, const FieldState& f, int me);
	Choice position(const Prompt& p, const FieldState& f, int me);
	Choice counter(const Prompt& p);
	Choice announceCard(const Prompt& p, const FieldState& f, int me);
	Choice announceMask(const Prompt& p, const FieldState& f, int me, bool race);
	std::vector<int> rankCards(const std::vector<CardRef>& cards, const Prompt& p, const FieldState& f, int me) const;
	bool goodTarget(int32_t myAtk, const FCard& t) const;
};

bool isDeclarable(const CardInfo& cd, const std::vector<uint64_t>& opcodes);

} // namespace ygt
