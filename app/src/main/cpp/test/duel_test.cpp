// Banco di prova da riga di comando: fa giocare due bot con mazzi .ydk
// uso: duel_test <cards.bin> <cartella CardScripts> <deckA.ydk> <deckB.ydk> <diffA> <diffB> <partite> [seme]
#include "../engine/duel_host.h"
#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>

using namespace ygt;

static std::string readFile(const std::string& p) {
	std::ifstream f(p, std::ios::binary);
	if(!f) return {};
	std::stringstream ss; ss << f.rdbuf();
	return ss.str();
}

static Deck readYdk(const std::string& path) {
	Deck d; std::ifstream f(path); std::string line; int sec = 0;
	while(std::getline(f, line)) {
		if(!line.empty() && line.back() == '\r') line.pop_back();
		if(line.rfind("#main", 0) == 0) sec = 0;
		else if(line.rfind("#extra", 0) == 0) sec = 1;
		else if(line.rfind("!side", 0) == 0) sec = 2;
		else if(!line.empty() && isdigit((unsigned char)line[0])) {
			uint32_t c = (uint32_t)std::stoul(line);
			if(sec == 0) d.main.push_back(c); else if(sec == 1) d.extra.push_back(c);
		}
	}
	return d;
}

int main(int argc, char** argv) {
	if(argc < 8) { std::cerr << "argomenti mancanti\n"; return 2; }
	CardDB db;
	if(!db.loadBinary(argv[1])) { std::cerr << "cards.bin non valido\n"; return 1; }
	std::string dir = argv[2];
	int missing = 0;
	ScriptLoader loader = [&](const std::string& name) {
		for(const char* sub : {"official/", "", "pre-errata/"}) {
			auto s = readFile(dir + "/" + sub + name);
			if(!s.empty()) return s;
		}
		++missing; if(getenv("VERBOSE")) fprintf(stderr, "script mancante: %s\n", name.c_str());
		return std::string();
	};
	Deck a = readYdk(argv[3]), b = readYdk(argv[4]);
	int da = atoi(argv[5]), dbf = atoi(argv[6]);
	if(std::string(argv[7]) == "interactive") {
		// modalità interattiva: stampa la vista JSON, legge la risposta JSON (come fa l'app)
		uint64_t seed = argc > 8 ? strtoull(argv[8], nullptr, 10) : 1234;
		DuelHost host(db, loader);
		std::string err;
		if(!host.start(a, b, dbf, seed % 2 == 0, seed, err)) { std::cerr << "start: " << err << "\n"; return 1; }
		(void)da;
		std::string view = host.advance();
		std::string line;
		while(true) {
			printf("%s\n", view.c_str());
			fflush(stdout);
			if(host.finished()) break;
			if(!std::getline(std::cin, line)) break;
			view = line == "surrender" ? host.surrender() : host.respond(line);
		}
		return 0;
	}
	int games = atoi(argv[7]);
	uint64_t seed = argc > 8 ? strtoull(argv[8], nullptr, 10) : 1234;
	int winsA = 0, winsB = 0, draws = 0, stuck = 0;
	for(int g = 0; g < games; ++g) {
		auto t0 = std::chrono::steady_clock::now();
		DuelHost host(db, loader);
		host.setAutoHuman(da, seed + g * 977);
		std::string err;
		if(!host.start(a, b, dbf, g % 2 == 0, seed + g, err)) { std::cerr << "start: " << err << "\n"; return 1; }
		std::string view = host.advance();
		auto t1 = std::chrono::steady_clock::now();
		double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
		int w = host.winnerRel();
		if(view.find("\"error\"") != std::string::npos) ++stuck;
		if(w == 0) ++winsA; else if(w == 1) ++winsB; else ++draws;
		printf("partita %d: vince %s, turni %d, decisioni %d, %.0f ms, errori motore %zu\n", g + 1,
		       w == 0 ? "A" : w == 1 ? "B" : "nessuno", host.turn(), host.botActions(), ms, host.errors().size());
		for(size_t i = 0; i < host.errors().size() && i < 3; ++i) printf("   ! %s\n", host.errors()[i].c_str());
		if(getenv("VERBOSE")) printf("%s\n", view.c_str());
		auto ep = view.find("\"error\"");
		if(ep != std::string::npos) printf("   > %s\n", view.substr(ep, 120).c_str());
	}
	printf("TOTALE: A %d, B %d, pari %d, bloccate %d, script mancanti %d\n", winsA, winsB, draws, stuck, missing);
	return 0;
}
