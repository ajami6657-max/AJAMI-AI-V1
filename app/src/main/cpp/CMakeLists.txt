cmake_minimum_required(VERSION 3.22.1)
project(vulkan_probe LANGUAGES CXX)

add_library(vulkan_probe SHARED vulkan_probe.cpp)
find_library(vulkan-lib vulkan)
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(vulkan_probe ${vulkan-lib} ${log-lib})

add_library(MNN SHARED IMPORTED GLOBAL)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libMNN.so"
)

add_library(mnn_probe SHARED mnn_probe.cpp)
target_include_directories(mnn_probe PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/MNN/include"
)
target_link_libraries(mnn_probe MNN ${log-lib} ${android-lib} ${CMAKE_DL_LIBS})
