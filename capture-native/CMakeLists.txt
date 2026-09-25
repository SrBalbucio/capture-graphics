# capture-native — builds capture_dxgi.dll (Windows x64).
#
# MinGW-w64 (ucrt64) build:
#   cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
#   # -> build/capture_dxgi.dll, copied to
#   #    ../capture-backend-win-dxgi/src/main/resources/natives/win-x64/
#
# MSVC alternative (Developer Prompt):
#   cmake -S . -B build-ms -G Ninja -DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl
cmake_minimum_required(VERSION 3.20)
project(capture_dxgi CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# JNI headers: resolved from JAVA_HOME, else from `javac -verbose` sysprop.
if(NOT DEFINED JAVA_HOME AND DEFINED ENV{JAVA_HOME})
  set(JAVA_HOME $ENV{JAVA_HOME})
endif()
if(NOT JAVA_HOME)
  message(FATAL_ERROR "JAVA_HOME not set; point it at a JDK for jni.h")
endif()
set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")

add_library(capture_dxgi SHARED src/dxgi_bridge.cpp)
target_include_directories(capture_dxgi PRIVATE include ${JNI_INCLUDE_DIRS})
target_compile_definitions(capture_dxgi PRIVATE CAPTURE_DXGI_BUILD UNICODE _UNICODE)
target_link_libraries(capture_dxgi PRIVATE d3d11 dxgi)

if(MINGW)
  # Static libgcc/libstdc++ so the DLL runs without msys runtime on PATH.
  target_link_options(capture_dxgi PRIVATE -static-libgcc -static-libstdc++)
endif()

set_target_properties(capture_dxgi PROPERTIES OUTPUT_NAME "capture_dxgi" PREFIX "")
