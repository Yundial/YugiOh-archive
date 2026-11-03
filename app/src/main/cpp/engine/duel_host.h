// Gestione di un duello: fa girare il motore, fa giocare il bot e
// prepara per l'interfaccia lo stato del campo, il registro e le richieste.
#pragma once
#include "common.h"
#include "prompt.h"
#include "field.h"
#include "bot.h"
#include "json.h"
#include <memory>

namespace ygt {

class DuelHost {
public:
	DuelHost(const CardDB& db, ScriptLoader loader);
	~DuelHost();
	// difficulty: 0 facile, 1 medio, 2 difficile
	bool start(const Deck& human, const Deck& bot, int difficulty, bool humanFirst, uint64_t seed, std::string& err);
	// Avanza finché serve una scelta del giocatore o il duello finisce. Ritorna JSON.
	std::string advance();
	// Applica la scelta del giocatore (JSON {"a":..,"b":..,"idx":[..],"places":[..],"mask":..}) e avanza.
	std::string respond(const std::string& choiceJson);
	std::string surrender();
	// Solo per i test: il lato "umano" viene giocato da un secondo bot
	void setAutoHuman(int difficulty, uint64_t seed);
	bool finished() const { return ended; }
	int winnerRel() const { return winner; }
	int turn() const { return field.turn; }
	const std::vector<std::string>& errors() const { return engineErrors; }
	int botActions() const { return botDecisions; }

private:
	const CardDB& db;
	ScriptLoader loader;
	void* duel = nullptr;
	int human = 0;               // indice motore del giocatore umano
	std::unique_ptr<Bot> bot, autoHuman;
	FieldState field;
	Prompt prompt;
	bool hasHumanPrompt = false;
	bool ended = false;
	int winner = -1;             // 0 = umano, 1 = bot, 2 = pareggio (relativo)
	int winReason = 0;
	uint64_t hint[2] = {0, 0};
	std::vector<std::string> log;  // eventi JSON non ancora inviati
	std::string pendingError;
	std::vector<std::string> engineErrors;
	int retries = 0;
	int botDecisions = 0;
	int lastChainCount = 0;

	friend struct HostCallbacks;
	bool loadScript(const char* name, void* target);
	void* createEngine(std::string* err);
	void sendResponse(const std::vector<uint8_t>& resp);
	double lookahead(const Choice& c);   // valuta una mossa simulando il resto del turno
	double evaluate(const FieldState& f, int me, int winnerAbs) const;
	uint64_t engineSeed[4] = {1, 1, 1, 1};
	std::vector<uint32_t> teamMain[2], teamExtra[2];
	std::vector<std::vector<uint8_t>> history;  // tutte le risposte inviate al motore
	std::unordered_map<std::string, std::string> scriptCache;
	bool simulating = false;
	int difficultyLevel = 1;

	int rel(int p) const { return p == human ? 0 : 1; }
	void processMessages(const uint8_t* buf, uint32_t len, bool& gotPrompt, bool& gotRetry);
	void handleEvent(uint8_t t, Reader& rd);
	bool visibleToHuman(uint8_t con, uint32_t loc, uint32_t pos) const;
	void writeCardRef(Json& j, const CardRef& c) const;
	void writeFCard(Json& j, const FCard& c, bool hide) const;
	std::string buildView();
	void writePrompt(Json& j);
	void addLog(const std::string& entry) { log.push_back(entry); if(log.size() > 400) log.erase(log.begin()); }
};

} // namespace ygt
