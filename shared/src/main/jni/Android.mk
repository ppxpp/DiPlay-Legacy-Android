LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := diplay_userspace
LOCAL_C_INCLUDES := $(LOCAL_PATH)/userspace $(LOCAL_PATH)/vendor/lwip/src/include
LWIP_SOURCES := $(wildcard $(LOCAL_PATH)/vendor/lwip/src/core/*.c) $(wildcard $(LOCAL_PATH)/vendor/lwip/src/core/ipv6/*.c) $(wildcard $(LOCAL_PATH)/vendor/lwip/src/api/*.c)
LOCAL_SRC_FILES := userspace/sys_arch.c userspace/stack.c $(LWIP_SOURCES:$(LOCAL_PATH)/%=%) vendor/lwip/src/netif/ethernet.c
LOCAL_CFLAGS := -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra
include $(BUILD_SHARED_LIBRARY)
