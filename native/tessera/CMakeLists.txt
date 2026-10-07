# Builds Tessera (the C storage engine, a sibling project) as a shared library that NEXUS calls
# through the Java Foreign Function & Memory API. Tessera itself is not modified.
#
#   cmake -S native/tessera -B native/tessera/build -DTESSERA_DIR=../Tessera
#   cmake --build native/tessera/build --config Release
cmake_minimum_required(VERSION 3.20)
project(nexus_tessera LANGUAGES C)

set(TESSERA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../Tessera" CACHE PATH "the Tessera project folder")
get_filename_component(TESSERA_DIR "${TESSERA_DIR}" ABSOLUTE)
if(NOT EXISTS "${TESSERA_DIR}/include/tessera.h")
  message(FATAL_ERROR "Tessera not found in ${TESSERA_DIR} (set -DTESSERA_DIR=...)")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_WINDOWS_EXPORT_ALL_SYMBOLS ON)    # export the ts_* API from the DLL without touching the sources
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release)
endif()
if(MSVC)
  add_compile_options(/utf-8)
  add_compile_definitions(_CRT_SECURE_NO_WARNINGS WIN32_LEAN_AND_MEAN NOMINMAX)
else()
  add_compile_definitions(_POSIX_C_SOURCE=200809L _DEFAULT_SOURCE)
  if(APPLE)
    add_compile_definitions(_DARWIN_C_SOURCE)
  endif()
endif()

file(GLOB_RECURSE TESSERA_SOURCES "${TESSERA_DIR}/src/*.c")
add_library(tessera_shared SHARED ${TESSERA_SOURCES})
set_target_properties(tessera_shared PROPERTIES OUTPUT_NAME tessera)
target_include_directories(tessera_shared PUBLIC "${TESSERA_DIR}/include" PRIVATE "${TESSERA_DIR}/src")
