cmake_minimum_required(VERSION 3.18)
project(ygotester C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(CORE ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ocgcore)

# ---- Lua 5.4 compilato come C++ (come richiede ocgcore)
file(GLOB LUA_SRC ${CORE}/lua/src/*.c)
list(FILTER LUA_SRC EXCLUDE REGEX ".*/(lbitlib|lcorolib|ldblib|linit|loadlib|loslib|ltests|lua|luac|lutf8lib|onelua)\\.c$")
set_source_files_properties(${LUA_SRC} PROPERTIES LANGUAGE CXX)
add_library(lua STATIC ${LUA_SRC})
target_include_directories(lua PUBLIC ${CORE}/lua/src)
target_compile_options(lua PRIVATE -x c++ -include ${CORE}/lua/luaconf-customize.h -Wno-deprecated -fPIC)

# ---- ocgcore (motore delle regole di EDOPro)
file(GLOB CORE_SRC ${CORE}/*.cpp)
add_library(ocgcore STATIC ${CORE_SRC})
target_include_directories(ocgcore PUBLIC ${CORE})
target_compile_options(ocgcore PRIVATE -fno-rtti -Wno-unused-parameter -fPIC)
target_link_libraries(ocgcore PUBLIC lua)

# ---- motore dell'app: duello + bot
set(ENGINE_SRC
  engine/prompt.cpp
  engine/field.cpp
  engine/bot.cpp
  engine/duel_host.cpp)

if(ANDROID)
  add_library(ygotester SHARED ${ENGINE_SRC} jni/jni_bridge.cpp)
  target_link_libraries(ygotester ocgcore log)
  target_link_options(ygotester PRIVATE "-Wl,-z,max-page-size=16384")
else()
  add_executable(duel_test ${ENGINE_SRC} test/duel_test.cpp)
  target_link_libraries(duel_test ocgcore)
endif()
