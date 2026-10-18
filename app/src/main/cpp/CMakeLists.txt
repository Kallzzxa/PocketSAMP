# CMakeLists.txt for PocketSAMP Pawn compiler JNI bridge.
# Bridge files (Compiler.cpp, Buffer.cpp, etc.) adapted from novusr/Pawn-MC
# (Apache-2.0); the Pawn compiler itself is Zeex/pawn 3.10.10 vendored under pawn/.

cmake_minimum_required(VERSION 3.22.1)
project("pawncc" C CXX)

set(PAWN_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/pawn")

# Version header (Zeex/pawn 3.10.10)
set(VERSION_MAJOR 3)
set(VERSION_MINOR 10)
set(VERSION_BUILD 10)
set(VERSION_STR "${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_BUILD}")
math(EXPR VERSION_INT "${VERSION_MAJOR} << 8 | ${VERSION_MINOR}")

set(COMMON_DEFINITIONS
    NO_MAIN
    NDEBUG
    LINUX
    PAWN_CELL_SIZE=32
    HAVE_UNISTD_H
    HAVE_INTTYPES_H
    HAVE_STDINT_H
    __BYTE_ORDER=1234
    __LITTLE_ENDIAN=1234
    __BIG_ENDIAN=4321
)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")

set(COMMON_C_FLAGS
    -Wno-implicit-function-declaration
    -Wno-int-conversion
    -Wno-pointer-sign
    -Wno-incompatible-pointer-types
    -Wno-format
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-parentheses
    -Wno-switch
    -Wno-dangling-else
    -Wno-empty-body
    -Wno-misleading-indentation
    -Wno-address-of-packed-member
    -fPIC
)

# Pawn compiler sources (Zeex/pawn)
set(PAWNC_SOURCES
    ${PAWN_SOURCE_DIR}/sc1.c
    ${PAWN_SOURCE_DIR}/sc2.c
    ${PAWN_SOURCE_DIR}/sc3.c
    ${PAWN_SOURCE_DIR}/sc4.c
    ${PAWN_SOURCE_DIR}/sc5.c
    ${PAWN_SOURCE_DIR}/sc6.c
    ${PAWN_SOURCE_DIR}/sc7.c
    ${PAWN_SOURCE_DIR}/sci18n.c
    ${PAWN_SOURCE_DIR}/sclist.c
    ${PAWN_SOURCE_DIR}/scmemfil.c
    ${PAWN_SOURCE_DIR}/scstate.c
    ${PAWN_SOURCE_DIR}/scvars.c
    ${PAWN_SOURCE_DIR}/lstring.c
    ${PAWN_SOURCE_DIR}/memfile.c
    ${PAWN_SOURCE_DIR}/hashtable/wrap_hashtable.c
    ${PAWN_SOURCE_DIR}/binreloc.c
)

# PawnMC JNI bridge sources
set(BRIDGE_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/Compiler.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/Diagnostics.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/Buffer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/Source.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/Files.cpp
)

add_library(pawncc_jni SHARED
    ${PAWNC_SOURCES}
    ${BRIDGE_SOURCES}
)

target_include_directories(pawncc_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${PAWN_SOURCE_DIR}
    ${PAWN_SOURCE_DIR}/hashtable
)

target_compile_definitions(pawncc_jni PRIVATE
    ${COMMON_DEFINITIONS}
    VERSION_MAJOR=${VERSION_MAJOR}
    VERSION_MINOR=${VERSION_MINOR}
    VERSION_BUILD=${VERSION_BUILD}
    VERSION_STR="${VERSION_STR}"
    VERSION_INT=${VERSION_INT}
)

target_compile_options(pawncc_jni PRIVATE
    $<$<COMPILE_LANGUAGE:C>:${COMMON_C_FLAGS}>
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++17 -fPIC>
)

target_link_libraries(pawncc_jni android log)
