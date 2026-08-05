cmake_minimum_required(VERSION 3.22.1)
project(chromatic_pitch LANGUAGES CXX)

include(FetchContent)

# Pin the professional pitch engine to the audited 4.0.0 release. The source is
# downloaded at configure time and compiled into the APK with the Android NDK.
FetchContent_Declare(
        rubberband
        GIT_REPOSITORY https://github.com/breakfastquay/rubberband.git
        GIT_TAG v4.0.0
        GIT_SHALLOW TRUE
        GIT_PROGRESS TRUE
)
FetchContent_GetProperties(rubberband)
if (NOT rubberband_POPULATED)
    FetchContent_Populate(rubberband)
endif ()

add_library(
        chromatic_pitch
        SHARED
        native_pitch_shifter.cpp
        ${rubberband_SOURCE_DIR}/single/RubberBandSingle.cpp
)

target_include_directories(
        chromatic_pitch
        PRIVATE
        ${rubberband_SOURCE_DIR}
)

target_compile_features(chromatic_pitch PRIVATE cxx_std_17)
target_compile_options(
        chromatic_pitch
        PRIVATE
        -O3
        -fexceptions
        -frtti
        -ffast-math
)

target_link_libraries(
        chromatic_pitch
        android
        log
)
