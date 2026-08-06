cmake_minimum_required(VERSION 3.22.1)
project(chromatic_pitch LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The Windows executable embeds praat-parselmouth 0.4.1, which is based on
# Praat 6.1.38. Build that exact source tree so Android executes the same
# Sound -> Manipulation -> PitchTier -> overlap-add pipeline as the desktop.
FetchContent_Declare(
        parselmouth_source
        GIT_REPOSITORY https://github.com/YannickJadoul/Parselmouth.git
        GIT_TAG v0.4.1
        GIT_SHALLOW TRUE
        GIT_PROGRESS TRUE
)
FetchContent_GetProperties(parselmouth_source)
if (NOT parselmouth_source_POPULATED)
    FetchContent_Populate(parselmouth_source)
endif ()

# Praat's CMake target expects fmt::fmt. Add only Parselmouth's pinned fmt
# submodule; all numerical/audio dependencies continue to come from Praat's
# own `praat/external` tree.
add_subdirectory(
        ${parselmouth_source_SOURCE_DIR}/extern/fmt
        ${CMAKE_CURRENT_BINARY_DIR}/parselmouth-fmt
        EXCLUDE_FROM_ALL
)
add_subdirectory(
        ${parselmouth_source_SOURCE_DIR}/praat
        ${CMAKE_CURRENT_BINARY_DIR}/parselmouth-praat
        EXCLUDE_FROM_ALL
)

# `sendpraat` is a desktop IPC helper that sends commands to a running Praat
# GUI. It is unrelated to Sound/Manipulation and includes X11 headers on Unix,
# which do not exist on Android. Keep the rest of the exact Praat target.
get_target_property(PRAAT_NATIVE_SOURCES praat SOURCES)
list(FILTER PRAAT_NATIVE_SOURCES EXCLUDE REGEX "(^|/)sendpraat\\.c$")
set_property(TARGET praat PROPERTY SOURCES "${PRAAT_NATIVE_SOURCES}")

set_target_properties(praat fmt PROPERTIES POSITION_INDEPENDENT_CODE TRUE)

add_library(
        chromatic_pitch
        SHARED
        native_pitch_shifter.cpp
)

target_compile_features(chromatic_pitch PRIVATE cxx_std_17)
target_compile_options(
        chromatic_pitch
        PRIVATE
        -O2
        -fexceptions
        -frtti
        -Wno-unknown-pragmas
)

target_include_directories(
        chromatic_pitch
        PRIVATE
        ${parselmouth_source_SOURCE_DIR}
        ${parselmouth_source_SOURCE_DIR}/praat
        ${parselmouth_source_SOURCE_DIR}/praat/fon
        ${parselmouth_source_SOURCE_DIR}/praat/melder
        ${parselmouth_source_SOURCE_DIR}/praat/sys
)

# Android 15 compatibility for devices using 16 KB memory pages.
target_link_options(
        chromatic_pitch
        PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_link_libraries(
        chromatic_pitch
        PRIVATE
        praat
        android
        log
)
