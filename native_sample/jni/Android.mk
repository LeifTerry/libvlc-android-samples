LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := native

LOCAL_SRC_FILES := main.c

LOCAL_LDFLAGS := -L${LIBVLC_LIBS}
LOCAL_LDLIBS := -llog -lvlc

LOCAL_C_INCLUDES := $(VLC_SRC_DIR)/include

LOCAL_SHARED_LIBRARIES:= libvlc

include $(BUILD_SHARED_LIBRARY)
