cmake_minimum_required(VERSION 3.22.1)
project(gorillaxr CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(gorillaxr SHARED xr_bridge.cpp)
target_compile_features(gorillaxr PRIVATE cxx_std_17)
target_link_libraries(gorillaxr OpenXR::openxr_loader EGL GLESv3 android log)
# Android 15+: страницы 16 КБ
target_link_options(gorillaxr PRIVATE "-Wl,-z,max-page-size=16384")
