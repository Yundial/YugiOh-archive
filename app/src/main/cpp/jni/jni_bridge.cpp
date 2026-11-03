// Ponte JNI tra l'app Kotlin e il motore C++.
#include <jni.h>
#include <android/log.h>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include "../engine/duel_host.h"

using namespace ygt;

namespace {
CardDB* gDb = nullptr;
std::mutex gDbMutex;
JavaVM* gVm = nullptr;
jclass gFetcherCls = nullptr;
jmethodID gFetchMid = nullptr;
thread_local JNIEnv* tEnv = nullptr;
std::string gLastError;

struct Session {
	std::unique_ptr<DuelHost> host;
	std::string scriptDir;
};

std::string readFile(const std::string& path) {
	std::ifstream f(path, std::ios::binary);
	if(!f) return {};
	std::stringstream ss;
	ss << f.rdbuf();
	return ss.str();
}

std::string jstr(JNIEnv* env, jstring s) {
	if(!s) return {};
	const char* c = env->GetStringUTFChars(s, nullptr);
	std::string out = c ? c : "";
	if(c) env->ReleaseStringUTFChars(s, c);
	return out;
}

std::vector<uint32_t> jints(JNIEnv* env, jintArray a) {
	std::vector<uint32_t> v;
	if(!a) return v;
	jsize n = env->GetArrayLength(a);
	std::vector<jint> tmp(n);
	env->GetIntArrayRegion(a, 0, n, tmp.data());
	for(jint x : tmp) v.push_back((uint32_t)x);
	return v;
}

// Chiede all'app di scaricare uno script mancante (bloccante, siamo già su un thread di lavoro)
bool fetchScript(const std::string& name) {
	if(!tEnv || !gFetcherCls || !gFetchMid) return false;
	jstring jn = tEnv->NewStringUTF(name.c_str());
	jboolean ok = tEnv->CallStaticBooleanMethod(gFetcherCls, gFetchMid, jn);
	tEnv->DeleteLocalRef(jn);
	if(tEnv->ExceptionCheck()) { tEnv->ExceptionClear(); return false; }
	return ok;
}

jstring toJ(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
	gVm = vm;
	JNIEnv* env = nullptr;
	if(vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
	jclass cls = env->FindClass("com/yundial/ygotester/engine/ScriptFetcher");
	if(cls) {
		gFetcherCls = static_cast<jclass>(env->NewGlobalRef(cls));
		gFetchMid = env->GetStaticMethodID(gFetcherCls, "fetch", "(Ljava/lang/String;)Z");
	}
	if(env->ExceptionCheck()) env->ExceptionClear();
	return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_loadCards(JNIEnv* env, jobject, jstring path) {
	std::lock_guard<std::mutex> lk(gDbMutex);
	auto* db = new CardDB();
	if(!db->loadBinary(jstr(env, path))) { delete db; return JNI_FALSE; }
	delete gDb;
	gDb = db;
	return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_create(JNIEnv* env, jobject, jstring scriptDir,
		jintArray hMain, jintArray hExtra, jintArray bMain, jintArray bExtra,
		jint difficulty, jboolean humanFirst, jlong seed) {
	tEnv = env;
	if(!gDb) { gLastError = "Database delle carte non caricato"; return 0; }
	auto* s = new Session();
	s->scriptDir = jstr(env, scriptDir);
	std::string dir = s->scriptDir;
	ScriptLoader loader = [dir](const std::string& name) {
		std::string path = dir + "/" + name;
		std::string src = readFile(path);
		if(src.empty() && fetchScript(name)) src = readFile(path);
		return src;
	};
	s->host = std::make_unique<DuelHost>(*gDb, loader);
	Deck h{jints(env, hMain), jints(env, hExtra)};
	Deck b{jints(env, bMain), jints(env, bExtra)};
	std::string err;
	if(!s->host->start(h, b, difficulty, humanFirst, (uint64_t)seed, err)) {
		gLastError = err;
		delete s;
		return 0;
	}
	return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_lastError(JNIEnv* env, jobject) {
	return toJ(env, gLastError);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_advance(JNIEnv* env, jobject, jlong h) {
	tEnv = env;
	auto* s = reinterpret_cast<Session*>(h);
	if(!s) return toJ(env, "{}");
	return toJ(env, s->host->advance());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_respond(JNIEnv* env, jobject, jlong h, jstring json) {
	tEnv = env;
	auto* s = reinterpret_cast<Session*>(h);
	if(!s) return toJ(env, "{}");
	return toJ(env, s->host->respond(jstr(env, json)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_surrender(JNIEnv* env, jobject, jlong h) {
	tEnv = env;
	auto* s = reinterpret_cast<Session*>(h);
	if(!s) return toJ(env, "{}");
	return toJ(env, s->host->surrender());
}

extern "C" JNIEXPORT void JNICALL
Java_com_yundial_ygotester_engine_NativeEngine_destroy(JNIEnv*, jobject, jlong h) {
	delete reinterpret_cast<Session*>(h);
}
