// JSON minimale: scrittura e lettura dei pochi campi che servono.
#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <cstdlib>
#include <cctype>

namespace ygt {

class Json {
public:
	Json& beginObj() { sep(); s += '{'; first = true; return *this; }
	Json& endObj() { s += '}'; first = false; return *this; }
	Json& beginArr() { sep(); s += '['; first = true; return *this; }
	Json& endArr() { s += ']'; first = false; return *this; }
	Json& key(const char* k) { sep(); s += '"'; s += k; s += "\":"; first = true; return *this; }
	Json& val(int64_t v) { sep(); s += std::to_string(v); return *this; }
	Json& valU(uint64_t v) { sep(); s += std::to_string(v); return *this; }
	Json& val(bool v) { sep(); s += v ? "true" : "false"; return *this; }
	Json& val(const std::string& v) { sep(); s += '"'; esc(v); s += '"'; return *this; }
	Json& null() { sep(); s += "null"; return *this; }
	Json& raw(const std::string& r) { sep(); s += r; return *this; }
	template<typename T> Json& kv(const char* k, T v) { key(k); return val(v); }
	Json& kvU(const char* k, uint64_t v) { key(k); return valU(v); }
	const std::string& str() const { return s; }
private:
	std::string s;
	bool first = true;
	void sep() { if(!first) s += ','; first = false; }
	void esc(const std::string& v) {
		for(char c : v) {
			switch(c) {
			case '"': s += "\\\""; break;
			case '\\': s += "\\\\"; break;
			case '\n': s += "\\n"; break;
			case '\r': break;
			case '\t': s += "\\t"; break;
			default: s += c;
			}
		}
	}
};

// Estrae un intero dal JSON piatto ({"a":1,...}); ritorna def se assente
inline int64_t jsonInt(const std::string& j, const char* key, int64_t def = 0) {
	std::string k = std::string("\"") + key + "\"";
	auto p = j.find(k);
	if(p == std::string::npos) return def;
	p = j.find(':', p + k.size());
	if(p == std::string::npos) return def;
	++p;
	while(p < j.size() && isspace((unsigned char)j[p])) ++p;
	return std::strtoll(j.c_str() + p, nullptr, 10);
}

inline std::vector<int64_t> jsonIntArray(const std::string& j, const char* key) {
	std::vector<int64_t> out;
	std::string k = std::string("\"") + key + "\"";
	auto p = j.find(k);
	if(p == std::string::npos) return out;
	p = j.find('[', p + k.size());
	if(p == std::string::npos) return out;
	auto e = j.find(']', p);
	if(e == std::string::npos) return out;
	const char* c = j.c_str() + p + 1;
	const char* end = j.c_str() + e;
	while(c < end) {
		while(c < end && !(isdigit((unsigned char)*c) || *c == '-')) ++c;
		if(c >= end) break;
		char* nx = nullptr;
		out.push_back(std::strtoll(c, &nx, 10));
		c = nx;
	}
	return out;
}

} // namespace ygt
