cmake_minimum_required(VERSION 3.22.1)
project(wayver C)

add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwayland-server.so
)

add_library(wayver SHARED
    native_compositor.c
    vk_loader.c
    vk_presenter.c
    wl_compositor_server.c
    wl_selftest.c
    third_party/wayland/xdg-shell-protocol.c
)

target_include_directories(wayver PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/wayland
)

target_compile_options(wayver PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers
)

find_library(android-lib android)
find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(wayver
    wayland-server
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
)
