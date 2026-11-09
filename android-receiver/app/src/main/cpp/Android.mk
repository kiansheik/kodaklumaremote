LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := luma_nes
NES_DIR := third_party/quicknes/nes_emu
LOCAL_SRC_FILES := NesEngine.cpp nes_jni.cpp $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/$(NES_DIR)/*.cpp))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(NES_DIR)
LOCAL_CPPFLAGS := -std=c++17 -O3 -fexceptions -DNO_UNALIGNED_ACCESS -Wno-multichar
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
