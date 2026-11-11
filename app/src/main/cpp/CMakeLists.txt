cmake_minimum_required(VERSION 3.22.1)
project(doomgeneric_android C)

set(DG ${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric)

set(DOOM_SRC
    dummy am_map doomdef doomstat dstrings d_event d_items d_iwad d_loop d_main d_mode d_net
    f_finale f_wipe g_game hu_lib hu_stuff info i_cdmus i_endoom i_joystick i_scale i_sound
    i_system i_timer memio m_argv m_bbox m_cheat m_config m_controls m_fixed m_menu m_misc
    m_random p_ceilng p_doors p_enemy p_floor p_inter p_lights p_map p_maputl p_mobj p_plats
    p_pspr p_saveg p_setup p_sight p_spec p_switch p_telept p_tick p_user r_bsp r_data r_draw
    r_main r_plane r_segs r_sky r_things sha1 sounds statdump st_lib st_stuff s_sound tables
    v_video wi_stuff w_checksum w_file w_main w_wad z_zone w_file_stdc i_input i_video
    doomgeneric mus2mid
    doomgeneric_android i_androidsound)

set(SRCS "")
foreach(f ${DOOM_SRC})
    list(APPEND SRCS ${DG}/${f}.c)
endforeach()

add_library(doomgeneric SHARED ${SRCS})

target_include_directories(doomgeneric PRIVATE ${DG})

# Doom is old K&R-ish C: keep it building on modern clang, and chars MUST be signed.
target_compile_definitions(doomgeneric PRIVATE FEATURE_SOUND=1)
target_compile_options(doomgeneric PRIVATE
    -std=gnu11
    -O2
    -fsigned-char
    -fno-strict-aliasing
    -w
    -Wno-error
    -Wno-implicit-function-declaration
    -Wno-int-conversion
    -Wno-incompatible-pointer-types
    -Wno-incompatible-function-pointer-types
    -Wno-implicit-int
    -Wno-return-type)

target_link_libraries(doomgeneric android log aaudio m)

# Android 15+ devices use 16 KB pages
target_link_options(doomgeneric PRIVATE -Wl,-z,max-page-size=16384)
