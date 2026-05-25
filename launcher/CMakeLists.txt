cmake_minimum_required(VERSION 3.21)

project(ArklightLauncher VERSION 0.1.0 LANGUAGES CXX)

add_executable(ArklightLauncher WIN32
    src/ArklightLauncher.cpp
)

target_compile_features(ArklightLauncher PRIVATE cxx_std_17)
target_link_libraries(ArklightLauncher PRIVATE ws2_32 shell32)

set_target_properties(ArklightLauncher PROPERTIES
    OUTPUT_NAME Arklight
)

if(MSVC)
    target_compile_options(ArklightLauncher PRIVATE /utf-8)
else()
    target_link_options(ArklightLauncher PRIVATE -municode -static -static-libgcc -static-libstdc++)
endif()
