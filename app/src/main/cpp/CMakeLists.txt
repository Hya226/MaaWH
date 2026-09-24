cmake_minimum_required(VERSION 3.22)
project(maawh_vd CXX)

add_library(maawh_vd SHARED maawh_vd.cpp)

find_library(log-lib log)

target_link_libraries(maawh_vd
    EGL
    GLESv2
    android
    ${log-lib})
