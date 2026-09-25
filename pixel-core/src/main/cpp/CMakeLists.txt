cmake_minimum_required(VERSION 3.22.1)
project(pixel_lab_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Warnings on for native hot paths; JNI headers are external.
add_compile_options(-Wall -Wextra -Wno-unused-parameter)

file(GLOB_RECURSE PIXEL_LAB_SOURCES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/quantization/*.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/dithering/*.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/gif/*.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/pixel_ops/*.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/jni/*.cpp
        )

add_library(pixel_lab_native SHARED ${PIXEL_LAB_SOURCES})

# The cpp root is on the include path so translation units can pull shared
# headers with project-relative includes (e.g. "common/jni_common.h").
target_include_directories(pixel_lab_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(pixel_lab_native ${log-lib})
