cmake_minimum_required(VERSION 3.22.1)

project(minix_target_probe LANGUAGES CXX)

add_library(
    minix_target_probe
    SHARED
    target_native_probe.cpp
)

target_compile_features(minix_target_probe PRIVATE cxx_std_20)
target_compile_definitions(
    minix_target_probe
    PRIVATE
    _FILE_OFFSET_BITS=64
    _LARGEFILE64_SOURCE
    _GNU_SOURCE
)
target_compile_options(
    minix_target_probe
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

set_target_properties(
    minix_target_probe
    PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
