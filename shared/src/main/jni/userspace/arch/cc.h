#ifndef DIPLAY_CC_H
#define DIPLAY_CC_H
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <sys/time.h>
#ifdef __ANDROID__
#include <sys/types.h>
#define SOCKLEN_T_DEFINED 1
#endif
#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_PLATFORM_DIAG(x) do { fprintf(stderr, "lwIP: "); printf x; } while (0)
#define LWIP_PLATFORM_ASSERT(x) do { fprintf(stderr, "lwIP assertion: %s\n", x); abort(); } while (0)
uint32_t diplay_random(void);
#define LWIP_RAND() diplay_random()
#endif
