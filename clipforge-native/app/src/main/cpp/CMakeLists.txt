cmake_minimum_required(VERSION 3.18.1)
project("clipforge_rnnoise")

# RNNoise source files
set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

add_library(
    rnnoise_jni
    SHARED
    rnnoise_jni.c
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/rnn_data.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/kiss_fft.c
)

target_include_directories(
    rnnoise_jni
    PRIVATE
    ${RNNOISE_DIR}/include
    ${RNNOISE_DIR}/src
)

target_link_libraries(
    rnnoise_jni
    log
)
